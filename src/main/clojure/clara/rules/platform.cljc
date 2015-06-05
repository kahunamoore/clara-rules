(ns clara.rules.platform
  "Code specific to the JVM and JavaScript platform.")

#?(:clj
   (defn throw-error
     "Throw an error with the given description string."
     [^String description]
     (throw (IllegalArgumentException. description)))
   :cljs
   (defn throw-error
     "Throw an error with the given description string."
     [description]
     (throw (js/Error. description))))

#?(:clj
   (defn tuned-group-by
  "Equivalent of the built-in group-by, but tuned for when there are many values per key."
  [f coll]
  (->> coll
       (reduce (fn [map value]
                 (let [k (f value)
                       items (or (.get ^java.util.HashMap map k)
                                 (transient []))]
                   (.put ^java.util.HashMap map k (conj! items value)))
                 map)
               (java.util.HashMap.))
      (reduce (fn [map [key value]]
                  (assoc! map key (persistent! value)))
                (transient {}))
      (persistent!)))
   :cljs
   ;; The tuned group-by function is JVM-specific,
   ;; so just defer to provided group-by for ClojureScript.
   (def tuned-group-by clojure.core/group-by))
