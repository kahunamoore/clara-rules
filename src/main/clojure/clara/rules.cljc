(ns clara.rules
  "Forward-chaining rules for Clojure. The primary API is in this namespace."
  #?(:clj (:require
            [clara.rules.engine :as eng]
            [clara.rules.memory :as mem]
            [clara.rules.compiler :as com]
            [clara.rules.schema :as schema]
            [clara.rules.dsl :as dsl]
            [clara.rules.listener :as l]
            [schema.core :as sc])
          (import [clara.rules.engine LocalTransport LocalSession]))

  #?(:cljs (:require [clara.rules.engine :as eng]
             [clara.rules.memory :as mem]
             [clara.rules.listener :as l])))

; from macros.cljs
#?@(:cljs [
;; Store production in cljs.env/*compiler* under ::productions seq?
(defn- add-production [name production]
  (swap! env/*compiler* assoc-in [::productions (com/cljs-ns) name] production))

(defn- get-productions-from-namespace
  "Returns a map of names to productions in the given namespace."
  [namespace]
  ;; TODO: remove need for ugly eval by changing our quoting strategy.
  (let [productions (get-in @env/*compiler* [::productions namespace])]
    (map eval (vals productions))))

(defn- get-productions
  "Return the productions from the source"
  [source]
  (cond
    (symbol? source) (get-productions-from-namespace source)
    (coll? source) (seq source)
    :else (throw (IllegalArgumentException. "Unknown source value type passed to defsession"))))

])

(defn insert
  "Inserts one or more facts into a working session. It does not modify the given
   session, but returns a new session with the facts added."
  [session & facts]
  (eng/insert session facts))

(defn insert-all
  "Inserts a sequence of facts into a working session. It does not modify the given
   session, but returns a new session with the facts added."
  [session fact-seq]
  (eng/insert session fact-seq))

(defn retract
  "Retracts a fact from a working session. It does not modify the given session,
   but returns a new session with the facts retracted."
  [session & facts]
  (eng/retract session facts))

(defn fire-rules
  "Fires are rules in the given session. Once a rule is fired, it is labeled in a fired
   state and will not be re-fired unless facts affecting the rule are added or retracted.

   This function does not modify the given session to mark rules as fired. Instead, it returns
   a new session in which the rules are marked as fired."
  [session]
  (eng/fire-rules session))

(defn query
  "Runs the given query with the optional given parameters against the session.
   The optional parameters should be in map form. For example, a query call might be:

   (query session get-by-last-name :last-name \"Jones\")

   The query itself may be either the var created by a defquery statement,
   or the actual name of the query.
   "
  [session query & params]
  (eng/query session query (apply hash-map params)))



(defn insert!
  "To be executed within a rule's right-hand side, this inserts a new fact or facts into working memory.

   Inserted facts are logical, in that if the support for the insertion is removed, the fact
   will automatically be retracted. For instance, if there is a rule that inserts a \"Cold\" fact
   if a \"Temperature\" fact is below a threshold, and the \"Temperature\" fact that triggered
   the rule is retracted, the \"Cold\" fact the rule inserted is also retracted. This is the underlying
   truth maintenance facillity.

   This truth maintenance is also transitive: if a rule depends on some criteria to fire, and a
   criterion becomes invalid, it may retract facts that invalidate other rules, which in turn
   retract their conclusions. This way we can ensure that information inferred by rules is always
   in a consistent state."
  [& facts]
  (eng/insert-facts! facts false))

(defn insert-all!
  "Behaves the same as insert!, but accepts a sequence of facts to be inserted. This can be simpler and more efficient for
   rules needing to insert multiple facts.

   See the doc in insert! for details on insert behavior.."
  [facts]
  (eng/insert-facts! facts false))

(defn insert-unconditional!
  "To be executed within a rule's right-hand side, this inserts a new fact or facts into working memory.

   This differs from insert! in that it is unconditional. The facts inserted will not be retracted
   even if the rule activation doing the insert becomes false.  Most users should prefer the simple insert!
   function as described above, but this function is available for use cases that don't wish to use
   Clara's truth maintenance."
  [& facts]
  (eng/insert-facts! facts true))

(defn insert-all-unconditional!
  "Behaves the same as insert-unconditional!, but accepts a sequence of facts to be inserted rather than individual facts.

   See the doc in insert-unconditional! for details on uncondotional insert behavior."
  [facts]
  (eng/insert-facts! facts true))

(defn retract!
  "To be executed within a rule's right-hand side, this retracts a fact or facts from the working memory.

   Retracting facts from the right-hand side has slightly different semantics than insertion. As described
   in the insert! documentation, inserts are logical and will automatically be retracted if the rule
   that inserted them becomes false. This retract! function does not follow the inverse; retracted items
   are simply removed, and not re-added if the rule that retracted them becomes false.

   The reason for this is that retractions remove information from the knowledge base, and doing truth
   maintenance over retractions would require holding onto all retracted items, which would be an issue
   in some use cases. This retract! method is included to help with certain use cases, but unless you
   have a specific need, it is better to simply do inserts on the rule's right-hand side, and let
   Clara's underlying truth maintenance retract inserted items if their support becomes false."
  [& facts]
  (let [{:keys [rulebase transient-memory transport insertions get-alphas-fn listener]} eng/*current-session*]

    ;; Update the count so the rule engine will know when we have normalized.
    (swap! insertions + (count facts))

    (doseq [[alpha-roots fact-group] (get-alphas-fn facts)
            root alpha-roots]

      (eng/alpha-retract root fact-group transient-memory transport listener))))

#?@(:clj [




;; Cache of sessions for fast reloading.
(def ^:private session-cache (atom {}))

(defmacro mk-session
   "Creates a new session using the given rule sources. Thew resulting session
   is immutable, and can be used with insert, retract, fire-rules, and query functions.

   If no sources are provided, it will attempt to load rules from the caller's namespace.

   The caller may also specify keyword-style options at the end of the parameters. Currently four
   options are supported:

   * :fact-type-fn, which must have a value of a function used to determine the logical type of a given
     cache. Defaults to Clojures type function.
   * :cache, indicating whether the session creation can be cached, effectively memoizing mk-session.
     Defaults to true. Callers may wish to set this to false when needing to dynamically reload rules.
   * :activation-group-fn, a function applied to productio structures and returns the group they should be activated with.
     It defaults to checking the :salience property, or 0 if none exists.
   * :activation-group-sort-fn, a comparator function used to sort the values returned by the above :activation-group-fn.
     defaults to >, so rules with a higher salience are executed first.

   This is not supported in ClojureScript, since it requires eval to dynamically build a session. ClojureScript
   users must use pre-defined rulesessions using defsession."
  [& args]
  (if (and (seq args) (not (keyword? (first args))))
    `(com/mk-session ~(vec args)) ; At least one namespace given, so use it.
    `(com/mk-session (concat [(ns-name *ns*)] ~(vec args))))) ; No namespace given, so use the current one.
sdf
;; Treate a symbol as a rule source, loading all items in its namespace.
(extend-type clojure.lang.Symbol
  com/IRuleSource
  (load-rules [sym]

    ;; Find the rules and queries in the namespace, shred them,
    ;; and compile them into a rule base.
    (->> (ns-interns sym)
         (vals) ; Get the references in the namespace.
         (filter #(or (:rule (meta %)) (:query (meta %)))) ; Filter down to rules and queries.
         (map deref))))  ; Get the rules from the symbols.

(defmacro defsession
  "Creates a sesson given a list of sources and keyword-style options, which are typically Clojure namespaces.

  Typical usage would be like this, with a session defined as a var:

(defsession my-session 'example.namespace)

That var contains an immutable session that then can be used as a starting point to create sessions with
caller-provided data. Since the session itself is immutable, it can be safely used from multiple threads
and will not be modified by callers. So a user might grab it, insert facts, and otherwise
use it as follows:

   (-> my-session
     (insert (->Temperature 23))
     (fire-rules))

   "
  [name & sources-and-options]

  `(def ~name (com/mk-session ~(vec sources-and-options))))
])

#?(:clj
   (defmacro defrule
     "Defines a rule and stores it in the given var. For instance, a simple rule would look like this:

   (defrule hvac-approval
     \"HVAC repairs need the appropriate paperwork, so insert a validation error if approval is not present.\"
     [WorkOrder (= type :hvac)]
     [:not [ApprovalForm (= formname \"27B-6\")]]
     =>
     (insert! (->ValidationError
               :approval
               \"HVAC repairs must include a 27B-6 form.\")))

     See the guide at https://github.com/rbrush/clara-rules/wiki/Guide for details."

     [name & body]
     (let [doc (if (string? (first body)) (first body) nil)
           body (if doc (rest body) body)
           properties (if (map? (first body)) (first body) nil)
           definition (if properties (rest body) body)
           {:keys [lhs rhs]} (dsl/split-lhs-rhs definition)]
       (when-not rhs
         (throw (ex-info (str "Invalid rule " name ". No RHS (missing =>?).")
                         {})))
       `(def ~(vary-meta name assoc :rule true :doc doc)
          (cond-> ~(dsl/parse-rule* lhs rhs properties {})
                  ~name (assoc :name ~(str (clojure.core/name (ns-name *ns*)) "/" (clojure.core/name name)))
                  ~doc (assoc :doc ~doc)))))
   :cljs
   (defmacro defrule
     [name & body]
     (let [doc (if (string? (first body)) (first body) nil)
           body (if doc (rest body) body)
           properties (if (map? (first body)) (first body) nil)
           definition (if properties (rest body) body)
           {:keys [lhs rhs]} (dsl/split-lhs-rhs definition)

           production (cond-> (dsl/parse-rule* lhs rhs properties {})
                              name (assoc :name (str (clojure.core/name (com/cljs-ns)) "/" (clojure.core/name name)))
                              doc (assoc :doc doc))]
       (add-production name production)
       `(def ~name
          ~production)))
   )

#?(:clj
   (defmacro defquery
     "Defines a query and stored it in the given var. For instance, a simple query that accepts no
      parameters would look like this:

   (defquery check-job
     \"Checks the job for validation errors.\"
     []
     [?issue <- ValidationError])

      See the guide at https://github.com/rbrush/clara-rules/wiki/Guide for details."

     [name & body]
     (let [doc (if (string? (first body)) (first body) nil)
           binding (if doc (second body) (first body))
           definition (if doc (drop 2 body) (rest body) )]
       `(def ~(vary-meta name assoc :query true :doc doc)
          (cond-> ~(dsl/parse-query* binding definition {})
                  ~name (assoc :name ~(str (clojure.core/name (ns-name *ns*)) "/" (clojure.core/name name)))
                  ~doc (assoc :doc ~doc)))))
   :cljs
   (defmacro defquery
     [name & body]
     (let [doc (if (string? (first body)) (first body) nil)
           binding (if doc (second body) (first body))
           definition (if doc (drop 2 body) (rest body) )

           query (cond-> (dsl/parse-query* binding definition {})
                         name (assoc :name (str (clojure.core/name (com/cljs-ns)) "/" (clojure.core/name name)))
                         doc (assoc :doc doc))]
       (add-production name query)
       `(def ~name
          ~query)))
   )

#?@(:cljs [
   (defn- gen-beta-network
     "Generates the beta network from the beta tree. "
     ([beta-nodes
       parent-bindings]
      (vec
        (for [beta-node beta-nodes
              :let [{:keys [condition children id production query join-bindings]} beta-node

                    constraint-bindings (com/variables-as-keywords (:constraints condition))

                    ;; Get all bindings from the parent, condition, and returned fact.
                    all-bindings (cond-> (s/union parent-bindings constraint-bindings)
                                         ;; Optional fact binding from a condition.
                                         (:fact-binding condition) (conj (:fact-binding condition))
                                         ;; Optional accumulator result.
                                         (:result-binding beta-node) (conj (:result-binding beta-node)))]]

          (case (:node-type beta-node)

            :join
            `(eng/->JoinNode
               ~id
               '~condition
               ~(gen-beta-network children all-bindings)
               ~join-bindings)

            :negation
            `(eng/->NegationNode
               ~id
               '~condition
               ~(gen-beta-network children all-bindings)
               ~join-bindings)

            :test
            `(eng/->TestNode
               ~id
               ~(com/compile-test (:constraints condition))
               ~(gen-beta-network children all-bindings))

            :accumulator
            `(eng/->AccumulateNode
               ~id
               {:accumulator '~(:accumulator beta-node)
                :from '~condition}
               ~(:accumulator beta-node)
               ~(:result-binding beta-node)
               ~(gen-beta-network children all-bindings)
               ~join-bindings)

            :production
            `(eng/->ProductionNode
               ~id
               '~production
               ~(com/compile-action all-bindings (:rhs production) (:env production)))

            :query
            `(eng/->QueryNode
               ~id
               '~query
               ~(:params query))
            )))))

   (defn- compile-alpha-nodes
     [alpha-nodes]
     (vec
       (for [{:keys [condition beta-children env]} alpha-nodes
             :let [{:keys [type constraints fact-binding args]} condition]]

         {:type (com/effective-type type)
          :alpha-fn (com/compile-condition type (first args) constraints fact-binding env)
          :children (vec beta-children)
          })))

   (defmacro defsession
     "Creates a sesson given a list of sources and keyword-style options, which are typically ClojureScript namespaces.

     Each source is eval'ed at compile time, in Clojure (not ClojureScript.)

     If the eval result is a symbol, it is presumed to be a ClojureScript
     namespace, and all rules and queries defined in that namespace will
     be found and used.

     If the eval result is a collection, it is presumed to be a
     collection of productions. Note that although the collection must
     exist in the compiling Clojure runtime (since the eval happens at
     macro-expansion time), any expressions in the rule or query
     definitions will be executed in ClojureScript.

     Typical usage would be like this, with a session defined as a var:

   (defsession my-session 'example.namespace)

   That var contains an immutable session that then can be used as a starting point to create sessions with
   caller-provided data. Since the session itself is immutable, it can be safely used from multiple threads
   and will not be modified by callers. So a user might grab it, insert facts, and otherwise
   use it as follows:

      (-> my-session
        (insert (->Temperature 23))
        (fire-rules))
   "
     [name & sources-and-options]
     (let [sources (take-while #(not (keyword? %)) sources-and-options)
           options (apply hash-map (drop-while #(not (keyword? %)) sources-and-options))
           ;; Eval to unquote ns symbols, and to eval exprs to look up
           ;; explicit rule sources
           sources (eval (vec sources))
           productions (vec (for [source sources
                                  production (get-productions source)]
                              production))

           beta-tree (com/to-beta-tree productions)
           beta-network (gen-beta-network beta-tree #{})

           alpha-tree (com/to-alpha-tree beta-tree)
           alpha-nodes (compile-alpha-nodes alpha-tree)]

       `(let [beta-network# ~beta-network
              alpha-nodes# ~alpha-nodes
              productions# '~productions
              options# ~options]
          (def ~name (clara.rules/assemble-session beta-network# alpha-nodes# productions# options#)))))

])

#?(:clj
   (defn accumulate
     "Creates a new accumulator based on the given properties:

      * An initial-value to be used with the reduced operations.
      * A reduce-fn that can be used with the Clojure Reducers library to reduce items.
      * A combine-fn that can be used with the Clojure Reducers library to combine reduced items.
      * An optional retract-fn that can remove a retracted fact from a previously reduced computation
      * An optional convert-return-fn that converts the reduced data into something useful to the caller.
        Simply uses identity by default.
       "
     [& {:keys [initial-value reduce-fn combine-fn retract-fn convert-return-fn] :as args}]
     (eng/map->Accumulator
       (merge
         {:combine-fn reduce-fn ; Default combine function is simply the reduce.
          :convert-return-fn identity ; Default conversion does nothing, so use identity.
          :retract-fn (fn [reduced retracted] reduced) ; Retractions do nothing by default.
          }
         args)))

   :cljs
   (defn accumulate
     "Creates a new accumulator based on the given properties:

      * An initial-value to be used with the reduced operations.
      * A reduce-fn that can be used with the Clojure Reducers library to reduce items.
      * A combine-fn that can be used with the Clojure Reducers library to combine reduced items.
      * An optional retract-fn that can remove a retracted fact from a previously reduced computation
      * An optional convert-return-fn that converts the reduced data into something useful to the caller.
        Simply uses identity by default.
       "
     [& {:keys [initial-value reduce-fn combine-fn retract-fn convert-return-fn] :as args}]
     (eng/map->Accumulator
       (merge
         {:combine-fn        reduce-fn                    ; Default combine function is simply the reduce.
          :convert-return-fn identity                     ; Default conversion does nothing, so use identity.
          :retract-fn        (fn [reduced retracted] reduced) ; Retractions do nothing by default.
          }
         args)))
   )


#?@(:cljs [

(defrecord Rulebase [alpha-roots beta-roots productions queries production-nodes query-nodes id-to-node])

(defn- create-get-alphas-fn
 "Returns a function that given a sequence of facts,
 returns a map associating alpha nodes with the facts they accept."
 [fact-type-fn merged-rules]

 ;; We preserve a map of fact types to alpha nodes for efficiency,
 ;; effectively memoizing this operation.
 (let [alpha-map (atom {})]
   (fn [facts]
     (for [[fact-type facts] (group-by fact-type-fn facts)]

       (if-let [alpha-nodes (get @alpha-map fact-type)]

         ;; If the matching alpha nodes are cached, simply return them.
         [alpha-nodes facts]

         ;; The alpha nodes weren't cached for the type, so get them now.
         (let [ancestors (conj (ancestors fact-type) fact-type)

               ;; Get all alpha nodes for all ancestors.
               new-nodes (distinct
                           (reduce
                             (fn [coll ancestor]
                               (concat
                                 coll
                                 (get-in merged-rules [:alpha-roots ancestor])))
                             []
                             ancestors))]

           (swap! alpha-map assoc fact-type new-nodes)
           [new-nodes facts]))))))

(defn- mk-rulebase
 [beta-roots alpha-fns productions]

 (let [beta-nodes (for [root beta-roots
                        node (tree-seq :children :children root)]
                    node)

       production-nodes (for [node beta-nodes
                              :when (= eng/ProductionNode (type node))]
                          node)

       query-nodes (for [node beta-nodes
                         :when (= eng/QueryNode (type node))]
                     node)

       query-map (into {} (for [query-node query-nodes

                                ;; Queries can be looked up by reference or by name;
                                entry [[(:query query-node) query-node]
                                       [(:name (:query query-node)) query-node]]]
                            entry))

       ;; Map of node ids to beta nodes.
       id-to-node (into {} (for [node beta-nodes]
                             [(:id node) node]))

       ;; type, alpha node tuples.
       alpha-nodes (for [{:keys [type alpha-fn children env]} alpha-fns
                         :let [beta-children (map id-to-node children)]]
                     [type (eng/->AlphaNode env beta-children alpha-fn)])

       ;; Merge the alpha nodes into a multi-map
       alpha-map (reduce
                   (fn [alpha-map [type alpha-node]]
                     (update-in alpha-map [type] conj alpha-node))
                   {}
                   alpha-nodes)]

   (map->Rulebase
     {:alpha-roots      alpha-map
      :beta-roots       beta-roots
      :productions      (filter :rhs productions)
      :queries          (remove :rhs productions)
      :production-nodes production-nodes
      :query-nodes      query-map
      :id-to-node       id-to-node})))

(defn assemble-session
 "This is used by tools to create a session; most users won't use this function."
 [beta-roots alpha-fns productions options]
 (let [rulebase (mk-rulebase beta-roots alpha-fns productions)
       transport (eng/LocalTransport.)

       ;; The fact-type uses Clojure's type function unless overridden.
       fact-type-fn (get options :fact-type-fn type)

       ;; Create a function that groups a sequence of facts by the collection
       ;; of alpha nodes they target.
       ;; We cache an alpha-map for facts of a given type to avoid computing
       ;; them for every fact entered.
       get-alphas-fn (create-get-alphas-fn fact-type-fn rulebase)

       ;; Default sort by higher to lower salience.
       activation-group-sort-fn (get options :activation-group-sort-fn >)

       ;; Activation groups use salience, with zero
       ;; as the default value.
       activation-group-fn (get options
                                :activation-group-fn
                                (fn [production]
                                  (or (some-> production :props :salience)
                                      0)))

       listener (if-let [listeners (:listeners options)]
                  (l/delegating-listener listeners)
                  l/default-listener)]

   ;; ClojureScript implementation doesn't support salience yet, so
   ;; no activation group functions are used.
   (eng/LocalSession. rulebase (eng/local-memory rulebase transport activation-group-sort-fn activation-group-fn) transport listener get-alphas-fn)))
])
