(ns clara.test-rules
  (:require [clara.sample-ruleset :as sample]
            [clara.other-ruleset :as other]
            [clara.rules :refer :all]
            [clojure.test :refer :all]
            [clara.rules.testfacts :refer :all]
            [clara.rules.engine :as eng]
            [clara.rules.compiler :as com]
            [clara.rules.accumulators :as acc]
            [clara.rules.dsl :as dsl]
            [clara.tools.tracing :as t]
            [clojure.set :as s]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            schema.test)
  (import [clara.rules.testfacts Temperature WindSpeed Cold TemperatureHistory
           ColdAndWindy LousyWeather First Second Third Fourth]
          [java.util TimeZone]))

(use-fixtures :once schema.test/validate-schemas)

(defn- has-fact? [token fact]
  (some #{fact} (map first (:matches token))))

(deftest test-simple-rule
  (let [rule-output (atom nil)
        cold-rule (dsl/parse-rule [[Temperature (< temperature 20)]]
                                  (reset! rule-output ?__token__))

        session (-> (mk-session [cold-rule])
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    (is (has-fact? @rule-output (->Temperature 10 "MCI")))))

(deftest test-multiple-condition-rule
  (let [rule-output (atom nil)
        cold-windy-rule (dsl/parse-rule [[Temperature (< temperature 20)]
                                         [WindSpeed (> windspeed 25)]]
                                        (reset! rule-output ?__token__))

        session (-> (mk-session [cold-windy-rule])
                    (insert (->WindSpeed 30 "MCI"))
                    (insert (->Temperature 10 "MCI")))]

    (fire-rules session)

    (is (has-fact? @rule-output (->WindSpeed 30 "MCI")))
    (is (has-fact? @rule-output (->Temperature 10 "MCI")))))

(deftest test-multiple-simple-rules

  (let [cold-rule-output (atom nil)
        windy-rule-output (atom nil)

        cold-rule (dsl/parse-rule [[Temperature (< temperature 20)]]
                                  (reset! cold-rule-output ?__token__))

        windy-rule (dsl/parse-rule [[WindSpeed (> windspeed 25)]]
                                   (reset! windy-rule-output ?__token__))

        session (-> (mk-session [cold-rule windy-rule])
                    (insert (->WindSpeed 30 "MCI"))
                    (insert (->Temperature 10 "MCI")))]

    (fire-rules session)

    ;; Check rule side effects contin the expected token.
    (is (has-fact? @cold-rule-output (->Temperature 10 "MCI")))

    (is (has-fact? @windy-rule-output (->WindSpeed 30 "MCI")))))

(deftest test-multiple-rules-same-fact

  (let [cold-rule-output (atom nil)
        subzero-rule-output (atom nil)
        cold-rule (dsl/parse-rule [[Temperature (< temperature 20)]]
                                  (reset! cold-rule-output ?__token__))

        subzero-rule (dsl/parse-rule [[Temperature (< temperature 0)]]
                                     (reset! subzero-rule-output ?__token__))

        session (-> (mk-session [cold-rule subzero-rule])
                    (insert (->Temperature -10 "MCI")))]

    (fire-rules session)

    (is (has-fact? @cold-rule-output (->Temperature -10 "MCI") ))

    (is (has-fact? @subzero-rule-output (->Temperature -10 "MCI")))))

(deftest test-cancelled-activation
  (let [rule-output (atom nil)
        cold-rule (dsl/parse-rule [[Temperature (< temperature 20)]]
                                  (reset! rule-output ?__token__) )

        session (-> (mk-session [cold-rule])
                    (insert (->Temperature 10 "MCI"))
                    (retract (->Temperature 10 "MCI"))
                    (fire-rules))]

    (is (= nil @rule-output))))

(deftest test-simple-binding
  (let [rule-output (atom nil)
        cold-rule (dsl/parse-rule [[Temperature (< temperature 20) (= ?t temperature)]]
                                  (reset! rule-output ?t) )

        session (-> (mk-session [cold-rule])
                    (insert (->Temperature 10 "MCI")))]


    (fire-rules session)
    (is (= 10 @rule-output))))

(deftest test-simple-join-binding
  (let [rule-output (atom nil)
        same-wind-and-temp (dsl/parse-rule [[Temperature (= ?t temperature)]
                                            [WindSpeed (= ?t windspeed)]]
                                           (reset! rule-output ?t))

        session (-> (mk-session [same-wind-and-temp])
                    (insert (->Temperature 10  "MCI"))
                    (insert (->WindSpeed 10  "MCI")))]

    (fire-rules session)
    (is (= 10 @rule-output))))

(deftest test-simple-join-binding-nomatch
  (let [rule-output (atom nil)
        same-wind-and-temp (dsl/parse-rule [[Temperature (= ?t temperature)]
                                            [WindSpeed (= ?t windspeed)]]
                                           (reset! rule-output ?t) )

        session (-> (mk-session [same-wind-and-temp])
                    (insert (->Temperature 10 "MCI"))
                    (insert (->WindSpeed 20 "MCI")))]

    (fire-rules session)
    (is (= nil @rule-output))))

(deftest test-join-with-fact-binding
  (let [rule-output (atom nil)
        same-wind-and-temp (dsl/parse-rule [[?t <- Temperature]
                                            [?w <- WindSpeed (= ?t windspeed)]]
                                           (reset! rule-output ?w))

        session (mk-session [same-wind-and-temp])]

    ;; The bound item in windspeed does not match the temperature,
    ;; so this should have no result.
    (-> session
        (insert (->Temperature 10  "MCI"))
        (insert (->WindSpeed (->Temperature 20 "MCI") "MCI"))
        (fire-rules))

    (is (= nil @rule-output))

    (reset! rule-output nil)

    (-> session
        (insert (->Temperature 10  "MCI"))
        (insert (->WindSpeed (->Temperature 10 "MCI") "MCI"))
        (fire-rules))

    (is (= (->WindSpeed (->Temperature 10 "MCI") "MCI")
           @rule-output))))

(deftest test-invalid-result-binding
  (let [rule-output (atom nil)
        same-wind-and-temp (dsl/parse-rule [[?t <- (acc/min :temperature :returns-fact true) :from [Temperature]]
                                            [?w <- WindSpeed (= ?t windspeed)]]
                                           (reset! rule-output ?w))]

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"accumulator" (mk-session [same-wind-and-temp])))))

(deftest test-simple-query
  (let [cold-query (dsl/parse-query [] [[Temperature (< temperature 20) (= ?t temperature)]])

        session (-> (mk-session [cold-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI")))]

    ;; The query should identify all items that wer einserted and matchd the
    ;; expected criteria.
    (is (= #{{:?t 15} {:?t 10}}
           (set (query session cold-query))))))

(deftest test-param-query
  (let [cold-query (dsl/parse-query [:?l] [[Temperature (< temperature 50)
                                     (= ?t temperature)
                                     (= ?l location)]])

        session (-> (mk-session [cold-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 20 "MCI")) ; Test multiple items in result.
                    (insert (->Temperature 10 "ORD"))
                    (insert (->Temperature 35 "BOS"))
                    (insert (->Temperature 80 "BOS")))]

    ;; Query by location.
    (is (= #{{:?l "BOS" :?t 35}}
           (set (query session cold-query :?l "BOS"))))

    (is (= #{{:?l "MCI" :?t 15} {:?l "MCI" :?t 20}}
           (set (query session cold-query :?l "MCI"))))

    (is (= #{{:?l "ORD" :?t 10}}
           (set (query session cold-query :?l "ORD"))))))

(deftest test-simple-condition-binding
  (let [cold-query (dsl/parse-query [] [[?t <- Temperature (< temperature 20)]])

        session (-> (mk-session [cold-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI")))]

    (is (= #{{:?t (->Temperature 15 "MCI")}
             {:?t (->Temperature 10 "MCI")}}
           (set (query session cold-query))))))

(deftest test-condition-and-value-binding
  (let [cold-query (dsl/parse-query [] [[?t <- Temperature (< temperature 20) (= ?v temperature)]])

        session (-> (mk-session [cold-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI")))]

    ;; Ensure the condition's fact and values are all bound.
    (is (= #{{:?v 10, :?t (->Temperature 10 "MCI")}
             {:?v 15, :?t (->Temperature 15 "MCI")}}
           (set (query session cold-query))))))

(deftest test-simple-accumulator
  (let [lowest-temp (accumulate
                     :reduce-fn (fn [value item]
                                  (if (or (= value nil)
                                          (< (:temperature item) (:temperature value) ))
                                    item
                                    value)))
        coldest-query (dsl/parse-query [] [[?t <- lowest-temp from [Temperature]]])

        session (-> (mk-session [coldest-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI")))]

    ;; Accumulator returns the lowest value.
    (is (= #{{:?t (->Temperature 10 "MCI")}}
           (set (query session coldest-query))))))

(defn min-fact
  "Function to create a new accumulator for a test."
  [field]
  (accumulate
   :reduce-fn (fn [value item]
                (if (or (= value nil)
                        (< (field item) (field value) ))
                  item
                  value))))

(deftest test-defined-accumulator
  (let [coldest-query (dsl/parse-query [] [[?t <- (min-fact :temperature) from [Temperature]]])

        session (-> (mk-session [coldest-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI")))]

    ;; Accumulator returns the lowest value.
    (is (= #{{:?t (->Temperature 10 "MCI")}}
           (set (query session coldest-query))))))

(deftest test-query-accumulator-output
  (let [set-cold (dsl/parse-rule [[?t <- (min-fact :temperature) from [Temperature]]]
                                 (insert! (->Cold (:temperature ?t))))

        cold-query (dsl/parse-query [] [[?c <- Cold]])

        session (-> (mk-session [set-cold cold-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI"))
                    (fire-rules))]

    ;; Accumulator returns the lowest value.
    (is (= #{{:?c (->Cold 10)}}
           (set (query session cold-query))))))

(defn average-value
  "Test accumulator that returns the average of a field"
  [field]
  (accumulate
   :initial-value [0 0]
   :reduce-fn (fn [[value count] item]
                [(+ value (field item)) (inc count)])
   :combine-fn (fn [[value1 count1] [value2 count2]]
                 [(+ value1 value2) (+ count1 count2)])
   :convert-return-fn (fn [[value count]] (if (= 0 count)
                                            nil
                                            (/ value count)))))

(deftest test-accumulator-with-result

  (let [average-temp-query (dsl/parse-query [] [[?t <- (average-value :temperature) from [Temperature]]])

        session (-> (mk-session [average-temp-query])
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 20 "MCI"))
                    (insert (->Temperature 30 "MCI"))
                    (insert (->Temperature 40 "MCI"))
                    (insert (->Temperature 50 "MCI"))
                    (insert (->Temperature 60 "MCI")))]


    ;; Accumulator returns the lowest value.
    (is (= #{{:?t 35}}
           (set (query session average-temp-query))))))

(deftest test-accumulate-with-retract
  (let [coldest-query (dsl/parse-query
                       [] [[?t <- (accumulate
                                   :initial-value []
                                   :reduce-fn conj
                                   :combine-fn concat

                                   ;; Retract by removing the retracted item.
                                   ;; In general, this would need to remove
                                   ;; only the first matching item to achieve expected semantics.
                                   :retract-fn (fn [reduced item]
                                                 (remove #{item} reduced))

                                   ;; Sort here and return the smallest.
                                   :convert-return-fn (fn [reduced]
                                                        (first
                                                         (sort #(< (:temperature %1) (:temperature %2))
                                                               reduced))))

                            :from (Temperature (< temperature 20))]])

        session (-> (mk-session [coldest-query])
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 17 "MCI"))
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 80 "MCI"))
                    (retract (->Temperature 10 "MCI")))]

    ;; The accumulator result should be
    (is (= #{{:?t (->Temperature 15 "MCI")}}
           (set (query session coldest-query))))))



(deftest test-joined-accumulator

  (let [coldest-query (dsl/parse-query []
                                [(WindSpeed (= ?loc location))
                                 [?t <- (accumulate
                                         :reduce-fn (fn [value item]
                                                      (if (or (= value nil)
                                                              (< (:temperature item) (:temperature value) ))
                                                        item
                                                        value)))
                                  :from (Temperature (= ?loc location))]])

        session (-> (mk-session [coldest-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 5 "SFO"))

                    ;; Insert last to exercise left activation of accumulate node.
                    (insert (->WindSpeed 30 "MCI")))

        session-retracted (retract session (->WindSpeed 30 "MCI"))]


    ;; Only the value that joined to WindSpeed should be visible.
    (is (= #{{:?t (->Temperature 10 "MCI") :?loc "MCI"}}
           (set (query session coldest-query))))

    (is (empty? (query session-retracted coldest-query)))))

(deftest test-bound-accumulator-var
  (let [coldest-query (dsl/parse-query [:?loc]
                                [[?t <- (accumulate
                                         :reduce-fn (fn [value item]
                                                      (if (or (= value nil)
                                                              (< (:temperature item) (:temperature value) ))
                                                        item
                                                        value)))
                                  :from [ Temperature (= ?loc location)]]])

        session (-> (mk-session [coldest-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 5 "SFO")))]

    (is (= #{{:?t (->Temperature 10 "MCI") :?loc "MCI"}}
           (set (query session coldest-query :?loc "MCI"))))

    (is (= #{{:?t (->Temperature 5 "SFO") :?loc "SFO"}}
           (set (query session coldest-query :?loc "SFO"))))))

(deftest test-accumulator-rule-with-no-fact-binding
  (let [fired? (atom false)
        ;; Ensure accumulate works, even without a fact binding given in the rule.
        rule (dsl/parse-rule [[(accumulate :initial-value []
                                           :reduce-fn conj
                                           :combine-fn into)
                               from [WindSpeed]]]
                             (reset! fired? true))
        session (-> (mk-session [rule])
                    (insert (->WindSpeed 20 "MCI")
                            (->WindSpeed 20 "SFO"))
                    (fire-rules))]

    (is (true? @fired?))))

(deftest test-accumulator-with-test-join-single-type
  "Tests an accumulator that does a join based on an arbitrary predicate."
  (let [colder-than-mci-query (dsl/parse-query []
                                               [[Temperature (= "MCI" location) (= ?mci-temp temperature)]
                                                [?colder-temps <- (acc/all)
                                                 :from [Temperature (< temperature ?mci-temp)]]])

        session (mk-session [colder-than-mci-query] :cache false)

        ;; Checks the result of the session for different permutations of input.
        check-result (fn [session]
                       (let [results (query session colder-than-mci-query)]

                         (is (= 1 (count results)))

                         (is (= {:?mci-temp 15
                                 :?colder-temps #{(->Temperature 10 "ORD")
                                                  (->Temperature 5 "LGA")}}

                                ;; Convert temps to a set, since ordering isn't guaranteed.
                                (update-in
                                 (first results)
                                 [:?colder-temps]
                                 set)))))]

    ;; Simple insertion of facts at once.
    (check-result (-> session
                      (insert (->Temperature 15 "MCI")
                              (->Temperature 10 "ORD")
                              (->Temperature 5 "LGA")
                              (->Temperature 30 "SFO"))
                      (fire-rules)))


    ;; Insert facts separately to ensure they are combined.
    (check-result (-> session
                      (insert (->Temperature 15 "MCI")
                              (->Temperature 5 "LGA")
                              (->Temperature 30 "SFO"))
                      (insert (->Temperature 10 "ORD"))
                      (fire-rules)))

    ;; Insert MCI location last to test left activation with token arriving
    ;; after the initial accumulation
    (check-result (-> session
                      (insert (->Temperature 10 "ORD")
                              (->Temperature 5 "LGA")
                              (->Temperature 30 "SFO"))
                      (insert (->Temperature 15 "MCI"))
                      (fire-rules)))

    ;; Test retraction of previously accumulated fact.
    (check-result (-> session
                      (insert (->Temperature 15 "MCI")
                              (->Temperature 10 "ORD")
                              (->Temperature 5 "LGA")
                              (->Temperature 30 "SFO")
                              (->Temperature 0 "IAD"))
                      (retract (->Temperature 0 "IAD"))
                      (fire-rules)))

    ;; Test retraction and re-insertion of token.
    (check-result (-> session
                      (insert (->Temperature 10 "ORD")
                              (->Temperature 5 "LGA")
                              (->Temperature 30 "SFO"))
                      (insert (->Temperature 15 "MCI"))
                      (retract (->Temperature 15 "MCI"))
                      (insert (->Temperature 15 "MCI"))
                      (fire-rules)))))

(deftest ^{:doc "Testing that a join filter accumulate node with no initial value will
                 only propagate results when candidate facts pass the join filter."}
  test-accumulator-with-test-join-multi-type
  (let [get-cold-temp (dsl/parse-query [] [[?cold <- Cold]])

        get-min-temp-under-threshold (dsl/parse-rule [[?threshold <- :temp-threshold]

                                                   [?min-temp <- (acc/min :temperature)
                                                    :from
                                                    [Temperature
                                                     (< temperature (:temperature ?threshold))]]]

                                                     (insert! (->Cold ?min-temp)))

        ;; Test assertion helper.
        assert-query-results (fn [test-name session & expected-results]

                               (is (= (count expected-results)
                                      (count (query session get-cold-temp)))
                                   (str test-name))

                               (is (= (set expected-results)
                                      (set (query session get-cold-temp)))
                                   (str test-name)))

        thresh-10 ^{:type :temp-threshold} {:temperature 10}
        thresh-20 ^{:type :temp-threshold} {:temperature 20}

        temp-5-mci (->Temperature 5 "MCI")
        temp-10-lax (->Temperature 10 "LAX")
        temp-20-mci (->Temperature 20 "MCI")

        session (mk-session [get-cold-temp get-min-temp-under-threshold])]

    ;; No temp tests - no firing

    (assert-query-results 'no-thresh-no-temps
                          session)

    (assert-query-results 'one-thresh-no-temps
                          (-> session
                              (insert thresh-10)
                              fire-rules))

    (assert-query-results 'retract-thresh-no-temps
                          (-> session
                              (insert thresh-10)
                              fire-rules
                              (retract thresh-10)
                              fire-rules))

    (assert-query-results 'two-thresh-no-temps
                          (-> session
                              (insert thresh-10)
                              (insert thresh-20)
                              fire-rules))

    ;; With temps tests.

    (assert-query-results 'one-thresh-one-temp-no-match
                          (-> session
                              (insert thresh-10)
                              (insert temp-10-lax)
                              fire-rules))

    (assert-query-results 'one-thresh-one-temp-no-match-retracted
                          (-> session
                              (insert thresh-10)
                              (insert temp-10-lax)
                              fire-rules
                              (retract temp-10-lax)
                              fire-rules))

    (assert-query-results 'two-thresh-one-temp-no-match
                          (-> session
                              (insert thresh-10)
                              (insert thresh-20)
                              (insert temp-20-mci)
                              fire-rules))

    (assert-query-results 'two-thresh-one-temp-two-match
                          (-> session
                              (insert thresh-10)
                              (insert thresh-20)
                              (insert temp-5-mci)
                              fire-rules)

                          {:?cold (->Cold 5)}
                          {:?cold (->Cold 5)})

    (assert-query-results 'one-thresh-one-temp-one-match
                          (-> session
                              (insert thresh-20)
                              (insert temp-5-mci)
                              fire-rules)

                          {:?cold (->Cold 5)})

    (assert-query-results 'retract-thresh-one-temp-one-match
                          (-> session
                              (insert thresh-20)
                              (insert temp-5-mci)
                              fire-rules
                              (retract thresh-20)
                              fire-rules))

    (assert-query-results 'one-thresh-two-temp-one-match
                          (-> session
                              (insert thresh-20)
                              (insert temp-5-mci)
                              (insert temp-20-mci)
                              fire-rules)

                          {:?cold (->Cold 5)})

    (assert-query-results 'one-thresh-one-temp-one-match-retracted
                          (-> session
                              (insert thresh-20)
                              (insert temp-5-mci)
                              fire-rules
                              (retract temp-5-mci)
                              fire-rules))

    (assert-query-results 'one-thresh-two-temp-two-match
                          (-> session
                              (insert thresh-20)
                              (insert temp-5-mci)
                              (insert temp-10-lax)
                              fire-rules)

                          {:?cold (->Cold 5)})))

(deftest ^{:doc "A test to make sure the appropriate data is held in the memory
                 of accumulate node with join filter is correct upon right-retract."}
  test-accumulator-with-test-join-retract-accumulated-use-new-result
  (let [coldest-temp (dsl/parse-rule [[?thresh <- :temp-threshold]
                                      [?temp <- (acc/max :temperature)
                                       :from [Temperature (< temperature (:temperature ?thresh))]]]

                                     (insert! (->Cold ?temp)))

        find-cold (dsl/parse-query [] [[?c <- Cold]])

        thresh-20 ^{:type :temp-threshold} {:temperature 20}

        temp-10-mci (->Temperature 10 "MCI")
        temp-15-lax (->Temperature 15 "LAX")

        cold-results (-> (mk-session [coldest-temp find-cold])
                         (insert thresh-20
                                 temp-10-mci)
                         ;; Retract it and add it back so that an
                         ;; accumulate happens on the intermediate
                         ;; node memory state.
                         (retract temp-10-mci)
                         (insert temp-10-mci)
                         fire-rules
                         (query find-cold))]

    (is (= [{:?c (->Cold 10)}]
           cold-results))))

(deftest ^{:doc "A test that ensures that when candidate facts are grouped by bindings in a
                 join filter accumulator that has an initial-value to propagate, that the value
                 is propagated for empty groups."}
  test-accumulator-with-init-and-binding-groups
  (let [get-temp-history (dsl/parse-query [] [[?his <- TemperatureHistory]])

        get-temps-under-threshold (dsl/parse-rule [[?threshold <- :temp-threshold]

                                                   [?temps <- (acc/all) :from [Temperature (= ?loc location)
                                                                               (< temperature (:temperature ?threshold))]]]

                                                  (insert! (->TemperatureHistory ?temps)))

        thresh-11 ^{:type :temp-threshold} {:temperature 11}
        thresh-20 ^{:type :temp-threshold} {:temperature 20}

        temp-10-mci (->Temperature 10 "MCI")
        temp-15-lax (->Temperature 15 "LAX")
        temp-20-mci (->Temperature 20 "MCI")

        session (mk-session [get-temp-history get-temps-under-threshold])

        two-groups-one-init (-> session
                                (insert thresh-11
                                        temp-10-mci
                                        temp-15-lax
                                        temp-20-mci)
                                fire-rules
                                (query get-temp-history))

        two-groups-no-init (-> session
                               (insert thresh-20
                                       temp-10-mci
                                       temp-15-lax
                                       temp-20-mci)
                               fire-rules
                               (query get-temp-history))]

    (is (= [{:?his (->TemperatureHistory [])}]
           (-> session
               (insert thresh-11)
               fire-rules
               (query get-temp-history))))

    (is (= 2 (count two-groups-one-init)))
    (is (= #{{:?his (->TemperatureHistory [])}
             {:?his (->TemperatureHistory [temp-10-mci])}}

           (set two-groups-one-init)))

    (is (= 2 (count two-groups-no-init)))
    (is (= #{{:?his (->TemperatureHistory [temp-15-lax])}
             {:?his (->TemperatureHistory [temp-10-mci])}}

           (set two-groups-no-init)))))

(deftest test-retract-initial-value
  (clara.rules.compiler/clear-session-cache!)

  (let [get-temp-history (dsl/parse-query [] [[?his <- TemperatureHistory]])

        get-temps-under-threshold (dsl/parse-rule [[?temps <- (acc/all) :from [Temperature (= ?loc location)]]]

                                                  (insert! (->TemperatureHistory ?temps)))

        temp-10-mci (->Temperature 10 "MCI")

        temp-history (-> (mk-session [get-temp-history get-temps-under-threshold])

                         (insert temp-10-mci)
                         (fire-rules)
                         (query get-temp-history))

        empty-history (-> (mk-session [get-temp-history get-temps-under-threshold])
                          (fire-rules)
                          (query get-temp-history))]

    (is (= 1 (count empty-history)))
    (is (= [{:?his (->TemperatureHistory [])}]
            empty-history))

    (is (= 1 (count temp-history)))
    (is (= [{:?his (->TemperatureHistory [temp-10-mci])}]
            temp-history))))

(deftest test-retract-initial-value-filtered
  (let [get-temp-history (dsl/parse-query [] [[?his <- TemperatureHistory]])

        get-temps-under-threshold (dsl/parse-rule [[?threshold <- :temp-threshold]

                                                   [?temps <- (acc/all) :from [Temperature (= ?loc location)
                                                                               (< temperature (:temperature ?threshold))]]]

                                                  (insert! (->TemperatureHistory ?temps)))

        thresh-11 ^{:type :temp-threshold} {:temperature 11}

        temp-10-mci (->Temperature 10 "MCI")
        temp-15-lax (->Temperature 15 "LAX")
        temp-20-mci (->Temperature 20 "MCI")

        temp-history (-> (mk-session [get-temp-history get-temps-under-threshold])
                         (insert thresh-11) ;; Explicitly insert this first to expose condition.
                         (insert temp-10-mci
                                 temp-15-lax
                                 temp-20-mci)

                         (fire-rules)
                         (query get-temp-history))

        empty-history (-> (mk-session [get-temp-history get-temps-under-threshold])
                          (insert thresh-11)
                          (fire-rules)
                          (query get-temp-history))]

    (is (= 1 (count empty-history)))
    (is (= [{:?his (->TemperatureHistory [])}]
            empty-history))

    (is (= 2 (count temp-history)))
    (is (= #{{:?his (->TemperatureHistory [])}
             {:?his (->TemperatureHistory [temp-10-mci])}}

           (set temp-history)))))


(deftest test-simple-negation
  (let [not-cold-query (dsl/parse-query [] [[:not [Temperature (< temperature 20)]]])

        session  (mk-session [not-cold-query])

        session-with-temp (insert session (->Temperature 10 "MCI"))
        session-retracted (retract session-with-temp (->Temperature 10 "MCI"))
        session-with-partial-retraction (-> session
                                            (insert (->Temperature 10 "MCI")
                                                    (->Temperature 15 "MCI"))
                                            (retract (->Temperature 10 "MCI")))]

    ;; No facts for the above criteria exist, so we should see a positive result
    ;; with no bindings.
    (is (= #{{}}
           (set (query session not-cold-query))))

    ;; Inserting an item into the sesion should invalidate the negation.
    (is (empty? (query session-with-temp
                       not-cold-query)))

    ;; Retracting the inserted item should make the negation valid again.
    (is (= #{{}}
           (set (query session-retracted not-cold-query))))

    ;; Some but not all items were retracted, so the negation
    ;; should still block propagation.
    (is (empty? (query session-with-partial-retraction
                       not-cold-query)))))

(deftest negation-truth-maintenance
  (let [make-hot (dsl/parse-rule [[WindSpeed]] ; Hack to only insert item on rule activation.
                                 (insert! (->Temperature 100 "MCI")))

        not-hot (dsl/parse-rule [[:not [Temperature (> temperature 80)]]]
                                (insert! (->Cold 0)))

        cold-query (dsl/parse-query [] [[?c <- Cold]])

        session (-> (mk-session [make-hot not-hot cold-query] :cache false)
                    (fire-rules))]

    ;; The cold fact should exist because nothing matched the negation.
    (is (= [{:?c (->Cold 0)}] (query session cold-query)))

    ;; The cold fact should be retracted because inserting this
    ;; triggers an insert that matches the negation.
    (is (empty? (-> session
                    (insert (->WindSpeed 100 "MCI"))
                    (fire-rules)
                    (query cold-query))))

    ;; The cold fact should exist again because we are indirectly retracting
    ;; the fact that matched the negation originially
    (is (= [{:?c (->Cold 0)}]
           (-> session
               (insert (->WindSpeed 100 "MCI"))
               (fire-rules)
               (retract (->WindSpeed 100 "MCI"))
               (fire-rules)
               (query cold-query))))))

(deftest test-negation-with-other-conditions
  (let [windy-but-not-cold-query (dsl/parse-query [] [[WindSpeed (> windspeed 30) (= ?w windspeed)]
                                                      [:not [ Temperature (< temperature 20)]]])

        session  (mk-session [windy-but-not-cold-query])

        ;; Make it windy, so our query should indicate that.
        session (insert session (->WindSpeed 40 "MCI"))
        windy-result  (set (query session windy-but-not-cold-query))

        ;; Make it hot and windy, so our query should still succeed.
        session (insert session (->Temperature 80 "MCI"))
        hot-and-windy-result (set (query session windy-but-not-cold-query))

        ;; Make it cold, so our query should return nothing.
        session (insert session (->Temperature 10 "MCI"))
        cold-result  (set (query session windy-but-not-cold-query))]


    (is (= #{{:?w 40}} windy-result))
    (is (= #{{:?w 40}} hot-and-windy-result))

    (is (empty? cold-result))))

(deftest test-negated-conjunction
  (let [not-cold-and-windy (dsl/parse-query [] [[:not [:and
                                                       [WindSpeed (> windspeed 30)]
                                                       [Temperature (< temperature 20)]]]])

        session  (mk-session [not-cold-and-windy])

        session-with-data (-> session
                              (insert (->WindSpeed 40 "MCI"))
                              (insert (->Temperature 10 "MCI")))]

    ;; It is not cold and windy, so we should have a match.
    (is (= #{{}}
           (set (query session not-cold-and-windy))))

    ;; Make it cold and windy, so there should be no match.
    (is (empty? (query session-with-data not-cold-and-windy)))))

(deftest test-negated-disjunction
  (let [not-cold-or-windy (dsl/parse-query [] [[:not [:or [WindSpeed (> windspeed 30)]
                                                          [Temperature (< temperature 20)]]]])

        session  (mk-session [not-cold-or-windy])

        session-with-temp (insert session (->WindSpeed 40 "MCI"))
        session-retracted (retract session-with-temp (->WindSpeed 40 "MCI"))]

    ;; It is not cold and windy, so we should have a match.
    (is (= #{{}}
           (set (query session not-cold-or-windy))))

    ;; Make it cold and windy, so there should be no match.
    (is (empty? (query session-with-temp not-cold-or-windy)))

    ;; Retract the added fact and ensure we now match something.
    (is (= #{{}}
           (set (query session-retracted not-cold-or-windy))))))

(deftest test-negation-with-complex-retractions
  (let [;; Non-blocked rule, where "blocked" means there is a
        ;; negated condition "guard".
        first-to-second (dsl/parse-rule [[First]]

                                        (insert! (->Second)))

        ;; Single blocked rule.
        blocked-first-to-fourth-third (dsl/parse-rule [[:not [Second]]
                                                       [First]]

                                                      (insert! (->Fourth)
                                                               (->Third)))

        ;; Double blocked rule.
        double-blocked-fourth (dsl/parse-query [] [[:not [Second]]
                                                   [:not [Third]]
                                                   [?fourth <- Fourth]])

        ;; Just to ensure the query can be matched sometimes.
        session-with-results (-> (mk-session [first-to-second
                                              blocked-first-to-fourth-third
                                              double-blocked-fourth]
                                             :cache false)
                                 (insert (->Fourth))
                                 fire-rules)

        ;; Let the rules engine perform rule activations in any order.
        session-no-salience (-> (mk-session [first-to-second
                                             blocked-first-to-fourth-third
                                             double-blocked-fourth]
                                            :cache false)
                                (insert (->First))
                                (insert (->Fourth))
                                fire-rules)

        ;; The simplest path is to evaluate the rule activations starting with
        ;; non-blocked -> blocked -> double blocked.
        session-best-order-salience (-> (mk-session [(assoc first-to-second :props {:salience 2})
                                                     (assoc blocked-first-to-fourth-third :props {:salience 1})
                                                     double-blocked-fourth]
                                                    :cache false)
                                        (insert (->First))
                                        (insert (->Fourth))
                                        fire-rules)

        ;; The most taxing path on the TMS is to evaluate the rule activations starting with
        ;; double blocked -> blocked -> non-blocked.
        session-worst-order-salience (-> (mk-session [(assoc first-to-second :props {:salience -2})
                                                      (assoc blocked-first-to-fourth-third :props {:salience -1})
                                                      double-blocked-fourth]
                                                     :cache false)
                                         (insert (->First))
                                         (insert (->Fourth))
                                         fire-rules)]

    (is (= #{{:?fourth (->Fourth)}}
           (set (query session-with-results double-blocked-fourth))))

    ;; All of these should return the same thing - :salience shouldn't
    ;; affect outcomes for logical inserts.

    (is (empty? (query session-no-salience double-blocked-fourth)))
    (is (empty? (query session-best-order-salience double-blocked-fourth)))
    (is (empty? (query session-worst-order-salience double-blocked-fourth)))))

(deftest test-simple-retraction
  (let [cold-query (dsl/parse-query [] [[Temperature (< temperature 20) (= ?t temperature)]])

        temp (->Temperature 10 "MCI")

        session (-> (mk-session [cold-query])
                    (insert temp))]

    ;; Ensure the item is there as expected.
    (is (= #{{:?t 10}}
           (set (query session cold-query))))

    ;; Ensure the item is retracted as expected.
    (is (= #{}
           (set (query (retract session temp) cold-query))))))

(deftest test-noop-retraction
  (let [cold-query (dsl/parse-query [] [[Temperature (< temperature 20) (= ?t temperature)]])

        session (-> (mk-session [cold-query])
                    (insert (->Temperature 10 "MCI"))
                    (retract (->Temperature 15 "MCI")))] ; Ensure retracting a non-existant item has no ill effects.

    (is (= #{{:?t 10}}
           (set (query session cold-query))))))

(deftest test-retraction-of-join
  (let [same-wind-and-temp (dsl/parse-query [] [[Temperature (= ?t temperature)]
                                         (WindSpeed (= ?t windspeed))])

        session (-> (mk-session [same-wind-and-temp])
                    (insert (->Temperature 10 "MCI"))
                    (insert (->WindSpeed 10 "MCI")))]

    ;; Ensure expected join occurred.
    (is (= #{{:?t 10}}
           (set (query session same-wind-and-temp))))

    ;; Ensure item was removed as viewed by the query.

    (is (= #{}
           (set (query
                 (retract session (->Temperature 10 "MCI"))
                 same-wind-and-temp))))))

(deftest test-retraction-of-equal-elements
  (let [insert-cold (dsl/parse-rule [[Temperature (= ?temp temperature)]]

                                    ;; Insert 2 colds that have equal
                                    ;; values to ensure they are both
                                    ;;retracted
                                    (insert! (->Cold ?temp)
                                             (->Cold ?temp)))

        find-cold (dsl/parse-query [] [[?c <- Cold]])

        ;; Each temp should insert 2 colds.
        session-inserted (-> (mk-session [insert-cold find-cold])
                             (insert (->Temperature 50 "LAX"))
                             (insert (->Temperature 50 "MCI"))
                             fire-rules)

        ;; Retracting one temp should retract both of its
        ;; logically inserted colds, but leave the others, even though
        ;; they are equal.
        session-retracted (-> session-inserted
                              (retract (->Temperature 50 "MCI"))
                              fire-rules)]

    (is (= 4 (count (query session-inserted find-cold))))

    (is (= [{:?c (->Cold 50)}
            {:?c (->Cold 50)}
            {:?c (->Cold 50)}
            {:?c (->Cold 50)}]

           (query session-inserted find-cold)))

    (is (= 2 (count (query session-retracted find-cold))))

    (is (= [{:?c (->Cold 50)}
            {:?c (->Cold 50)}]

           (query session-retracted find-cold)))))

(deftest test-simple-disjunction
  (let [or-query (dsl/parse-query [] [[:or [Temperature (< temperature 20) (= ?t temperature)]
                                           [WindSpeed (> windspeed 30) (= ?w windspeed)]]])

        session (mk-session [or-query])

        cold-session (insert session (->Temperature 15 "MCI"))
        windy-session (insert session (->WindSpeed 50 "MCI"))  ]

    (is (= #{{:?t 15}}
           (set (query cold-session or-query))))

    (is (= #{{:?w 50}}
           (set (query windy-session or-query))))))

(deftest test-disjunction-with-nested-and


  (let [really-cold-or-cold-and-windy
        (dsl/parse-query [] [[:or [Temperature (< temperature 0) (= ?t temperature)]
                                  [:and [Temperature (< temperature 20) (= ?t temperature)]
                                        [WindSpeed (> windspeed 30) (= ?w windspeed)]]]])

        rulebase [really-cold-or-cold-and-windy]

        cold-session (-> (mk-session rulebase)
                         (insert (->Temperature -10 "MCI")))

        windy-session (-> (mk-session rulebase)
                          (insert (->Temperature 15 "MCI"))
                          (insert (->WindSpeed 50 "MCI")))]

    (is (= #{{:?t -10}}
           (set (query cold-session really-cold-or-cold-and-windy))))

    (is (= #{{:?w 50 :?t 15}}
           (set (query windy-session really-cold-or-cold-and-windy))))))

(deftest test-simple-insert

  (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.
        cold-rule (dsl/parse-rule [[Temperature (< temperature 20) (= ?t temperature)]]
                                  (insert! (->Cold ?t)) )

        cold-query (dsl/parse-query [] [[Cold (= ?c temperature)]])

        session (-> (mk-session [cold-rule cold-query])
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    (is (= #{{:?c 10}}
           (set (query session cold-query))))))

(deftest test-simple-insert-all

  (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.
        cold-lousy-rule (dsl/parse-rule [[Temperature (< temperature 20) (= ?t temperature)]]
                                  (insert-all! [(->Cold ?t) (->LousyWeather)]))

        cold-lousy-query (dsl/parse-query [] [[Cold (= ?c temperature)]
                                        [LousyWeather]])

        session (-> (mk-session [cold-lousy-rule cold-lousy-query])
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    (is (= #{{:?c 10}}
           (set (query session cold-lousy-query))))))


(deftest test-insert-and-retract
  (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.
        cold-rule (dsl/parse-rule [[Temperature (< temperature 20) (= ?t temperature)]]
                                  (insert! (->Cold ?t)) )

        cold-query (dsl/parse-query [] [[Cold (= ?c temperature)]])

        session (-> (mk-session [cold-rule cold-query])
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    (is (= #{{:?c 10}}
           (set (query session cold-query))))

    ;; Ensure retracting the temperature also removes the logically inserted fact.
    (is (empty?
         (query
          (fire-rules (retract session (->Temperature 10 "MCI")))
          cold-query)))))

(deftest test-insert-retract-join ;; Test for issue #67
  (let [cold-not-windy-query (dsl/parse-query [] [[Temperature (< temperature 20) (= ?t temperature)]
                                                  [:not [WindSpeed]]])

        session (-> (mk-session [cold-not-windy-query])
                    (insert (->WindSpeed 30 "MCI"))
                    (retract (->WindSpeed 30 "MCI"))
                    (fire-rules))]

    (is (= [{:?t 10}]
           (-> session
               (insert (->Temperature 10 "MCI"))
               (fire-rules)
               (query cold-not-windy-query))))))

(deftest test-unconditional-insert
  (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.
        cold-rule (dsl/parse-rule [[Temperature (< temperature 20) (= ?t temperature)]]
                                  (insert-unconditional! (->Cold ?t)) )

        cold-query (dsl/parse-query [] [[Cold (= ?c temperature)]])

        session (-> (mk-session [cold-rule cold-query])
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    (is (= #{{:?c 10}}
           (set (query session cold-query))))

    ;; The derived fact should continue to exist after a retraction
    ;; since we used an unconditional insert.
    (is (= #{{:?c 10}}
           (set (query
                 (retract session (->Temperature 10 "MCI"))
                 cold-query))))))

(deftest test-unconditional-insert-all
  (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.
        cold-lousy-rule (dsl/parse-rule [[Temperature (< temperature 20) (= ?t temperature)]]
                                  (insert-all-unconditional! [(->Cold ?t) (->LousyWeather)]))

        cold-query (dsl/parse-query [] [[Cold (= ?c temperature)]])

        lousy-query (dsl/parse-query [] [[?l <- LousyWeather]])

        session (-> (mk-session [cold-lousy-rule cold-query lousy-query])
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    (is (= #{{:?c 10}}
           (set (query session cold-query))))

    (is (= #{{:?l (->LousyWeather)}}
           (set (query session lousy-query))))

    ;; The derived fact should continue to exist after a retraction
    ;; since we used an unconditional insert.
    (is (= #{{:?c 10}}
           (set (query
                 (retract session (->Temperature 10 "MCI"))
                 cold-query))))

    (is (= #{{:?l (->LousyWeather)}}
           (set (query
                 (retract session (->Temperature 10 "MCI"))
                 lousy-query))))))

(deftest test-insert-and-retract-multi-input
  (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.
        cold-rule (dsl/parse-rule [[Temperature (< temperature 20) (= ?t temperature)]
                                   [WindSpeed (> windspeed 30) (= ?w windspeed)]]
                           (insert! (->ColdAndWindy ?t ?w)) )

        cold-query (dsl/parse-query [] [[ColdAndWindy (= ?ct temperature) (= ?cw windspeed)]])

        session (-> (mk-session [cold-rule cold-query])
                    (insert (->Temperature 10 "MCI"))
                    (insert (->WindSpeed 40 "MCI"))
                    (fire-rules))]

    (is (= #{{:?ct 10 :?cw 40}}
           (set (query session cold-query))))

    ;; Ensure retracting the temperature also removes the logically inserted fact.
    (is (empty?
         (query
          (fire-rules (retract session (->Temperature 10 "MCI")))
          cold-query)))))

(deftest test-expression-to-dnf

  ;; Test simple condition.
  (is (= {:type Temperature :constraints []}
         (com/to-dnf {:type Temperature :constraints []})))

  ;; Test single-item conjunction removes unnecessary operator.
  (is (=  {:type Temperature :constraints []}
          (com/to-dnf [:and {:type Temperature :constraints []}])))

  ;; Test multi-item conjunction.
  (is (= [:and
          {:type Temperature :constraints ['(> 2 1)]}
          {:type Temperature :constraints ['(> 3 2)]}
          {:type Temperature :constraints ['(> 4 3)]}]
         (com/to-dnf
          [:and
           {:type Temperature :constraints ['(> 2 1)]}
           {:type Temperature :constraints ['(> 3 2)]}
           {:type Temperature :constraints ['(> 4 3)]}])))

   ;; Test simple disjunction
  (is  (= [:or
           {:type Temperature :constraints ['(> 2 1)]}
           {:type Temperature :constraints ['(> 3 2)]}
           {:type Temperature :constraints ['(> 4 3)]}]
         (com/to-dnf
          [:or
           {:type Temperature :constraints ['(> 2 1)]}
           {:type Temperature :constraints ['(> 3 2)]}
           {:type Temperature :constraints ['(> 4 3)]}])))


   ;; Test simple disjunction with nested conjunction.
  (is (= [:or
          {:type Temperature :constraints ['(> 2 1)]}
          [:and
           {:type Temperature :constraints ['(> 3 2)]}
           {:type Temperature :constraints ['(> 4 3)]}]]
         (com/to-dnf
          [:or
           {:type Temperature :constraints ['(> 2 1)]}
           [:and
            {:type Temperature :constraints ['(> 3 2)]}
            {:type Temperature :constraints ['(> 4 3)]}]])))

  ;; Test simple distribution of a nested or expression.
  (is (= [:or
          [:and
           {:type Temperature :constraints ['(> 2 1)]}
           {:type Temperature :constraints ['(> 4 3)]}]
          [:and
           {:type Temperature :constraints ['(> 3 2)]}
           {:type Temperature :constraints ['(> 4 3)]}]]
         (com/to-dnf
          [:and
           [:or
            {:type Temperature :constraints ['(> 2 1)]}
            {:type Temperature :constraints ['(> 3 2)]}]
           {:type Temperature :constraints ['(> 4 3)]}])))

  ;; Test push negation to edges.
  (is (= [:and
          [:not {:type Temperature :constraints ['(> 2 1)]}]
          [:not {:type Temperature :constraints ['(> 3 2)]}]
          [:not {:type Temperature :constraints ['(> 4 3)]}]]
         (com/to-dnf
          [:not
           [:or
            {:type Temperature :constraints ['(> 2 1)]}
            {:type Temperature :constraints ['(> 3 2)]}
            {:type Temperature :constraints ['(> 4 3)]}]])))

  ;; Remove unnecessary and.
  (is (= [:and
          [:not {:type Temperature :constraints ['(> 2 1)]}]
          [:not {:type Temperature :constraints ['(> 3 2)]}]
          [:not {:type Temperature :constraints ['(> 4 3)]}]]
         (com/to-dnf
          [:and
           [:not
            [:or
             {:type Temperature :constraints ['(> 2 1)]}
             {:type Temperature :constraints ['(> 3 2)]}
             {:type Temperature :constraints ['(> 4 3)]}]]])))

  (is (= [:or
            {:type Temperature :constraints ['(> 2 1)]}
            [:and
             {:type Temperature :constraints ['(> 3 2)]}
             {:type Temperature :constraints ['(> 4 3)]}]]

         (com/to-dnf
          [:and
           [:or
            {:type Temperature :constraints ['(> 2 1)]}
            [:and
             {:type Temperature :constraints ['(> 3 2)]}
             {:type Temperature :constraints ['(> 4 3)]}]]])))

  ;; Test push negation to edges.
  (is (= [:or
          [:not {:type Temperature :constraints ['(> 2 1)]}]
          [:not {:type Temperature :constraints ['(> 3 2)]}]
          [:not {:type Temperature :constraints ['(> 4 3)]}]]
         (com/to-dnf
          [:not
           [:and
            {:type Temperature :constraints ['(> 2 1)]}
            {:type Temperature :constraints ['(> 3 2)]}
            {:type Temperature :constraints ['(> 4 3)]}]])))

  ;; Test simple identity disjunction.
  (is (= [:or
          [:not {:type Temperature :constraints ['(> 2 1)]}]
          [:not {:type Temperature :constraints ['(> 3 2)]}]]
         (com/to-dnf
          [:or
           [:not {:type Temperature :constraints ['(> 2 1)]}]
           [:not {:type Temperature :constraints ['(> 3 2)]}]])))

  ;; Test distribution over multiple and expressions.
  (is (= [:or
          [:and
           {:type Temperature :constraints ['(> 2 1)]}
           {:type Temperature :constraints ['(> 5 4)]}
           {:type Temperature :constraints ['(> 6 5)]}]
          [:and
           {:type Temperature :constraints ['(> 3 2)]}
           {:type Temperature :constraints ['(> 5 4)]}
           {:type Temperature :constraints ['(> 6 5)]}]
          [:and
           {:type Temperature :constraints ['(> 4 3)]}
           {:type Temperature :constraints ['(> 5 4)]}
           {:type Temperature :constraints ['(> 6 5)]}]]
         (com/to-dnf
          [:and
           [:or
            {:type Temperature :constraints ['(> 2 1)]}
            {:type Temperature :constraints ['(> 3 2)]}
            {:type Temperature :constraints ['(> 4 3)]}]
           {:type Temperature :constraints ['(> 5 4)]}
           {:type Temperature :constraints ['(> 6 5)]}]))))

(def simple-defrule-side-effect (atom nil))

(defrule test-rule
  [Temperature (< temperature 20)]
  =>
  (reset! simple-defrule-side-effect ?__token__))

(deftest test-simple-defrule
  (let [session (-> (mk-session [test-rule])
                    (insert (->Temperature 10 "MCI")))]

    (fire-rules session)

    (is (has-fact? @simple-defrule-side-effect (->Temperature 10 "MCI") ))))

(defquery cold-query
  [:?l]
  [Temperature (< temperature 50)
               (= ?t temperature)
               (= ?l location)])

(deftest test-defquery
  (let [session (-> (mk-session [cold-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 20 "MCI")) ; Test multiple items in result.
                    (insert (->Temperature 10 "ORD"))
                    (insert (->Temperature 35 "BOS"))
                    (insert (->Temperature 80 "BOS")))]


    ;; Query by location.
    (is (= #{{:?l "BOS" :?t 35}}
           (set (query session cold-query :?l "BOS"))))

    (is (= #{{:?l "MCI" :?t 15} {:?l "MCI" :?t 20}}
           (set (query session cold-query :?l "MCI"))))

    (is (= #{{:?l "ORD" :?t 10}}
           (set (query session cold-query :?l "ORD"))))))

(deftest test-rules-from-ns

  (is (= #{{:?loc "MCI"} {:?loc "BOS"}}
       (set (-> (mk-session 'clara.sample-ruleset)
                (insert (->Temperature 15 "MCI"))
                (insert (->Temperature 22 "BOS"))
                (insert (->Temperature 50 "SFO"))
                (query sample/freezing-locations)))))

  (let [session (-> (mk-session 'clara.sample-ruleset)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->WindSpeed 45 "MCI"))
                    (fire-rules))]

    (is (= #{{:?fact (->ColdAndWindy 15 45)}}
           (set
            (query session sample/find-cold-and-windy))))))

(deftest test-rules-from-multi-namespaces

  (let [session (-> (mk-session 'clara.sample-ruleset 'clara.other-ruleset)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "BOS"))
                    (insert (->Temperature 50 "SFO"))
                    (insert (->Temperature -10 "CHI")))]

    (is (= #{{:?loc "MCI"} {:?loc "BOS"} {:?loc "CHI"}}
           (set (query session sample/freezing-locations))))

    (is (= #{{:?loc "CHI"}}
           (set (query session other/subzero-locations))))))

(deftest test-transitive-rule

  (is (= #{{:?fact (->LousyWeather)}}
         (set (-> (mk-session 'clara.sample-ruleset 'clara.other-ruleset)
                  (insert (->Temperature 15 "MCI"))
                  (insert (->WindSpeed 45 "MCI"))
                  (fire-rules)
                  (query sample/find-lousy-weather))))))


(deftest test-mark-as-fired
  (let [rule-output (atom nil)
        cold-rule (dsl/parse-rule [[Temperature (< temperature 20)]]
                                  (reset! rule-output ?__token__) )

        session (-> (mk-session [cold-rule])
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    (is (has-fact? @rule-output (->Temperature 10 "MCI")))

    ;; Reset the side effect then re-fire the rules
    ;; to ensure the same one isn't fired twice.
    (reset! rule-output nil)
    (fire-rules session)
    (is (= nil @rule-output))

    ;; Retract and re-add the item to yield a new execution of the rule.
    (-> session
        (retract (->Temperature 10 "MCI"))
        (insert (->Temperature 10 "MCI"))
        (fire-rules))

    (is (has-fact? @rule-output (->Temperature 10 "MCI")))))


(deftest test-chained-inference
  (let [item-query (dsl/parse-query [] [[?item <- Fourth]])

        session (-> (mk-session [(dsl/parse-rule [[Third]] (insert! (->Fourth)))
                                 (dsl/parse-rule [[First]] (insert! (->Second)))
                                 (dsl/parse-rule [[Second]] (insert! (->Third)))
                                 item-query])
                    (insert (->First))
                    (fire-rules))]

    ;; The query should identify all items that wer einserted and matchd the
    ;; expected criteria.
    (is (= #{{:?item (->Fourth)}}
           (set (query session item-query))))))


(comment ;; FIXME: node ids are currently not consistent...
  (deftest test-node-id-map
    (let [cold-rule (dsl/parse-rule [[Temperature (< temperature 20)]]
                             (println "Placeholder"))
          windy-rule (dsl/parse-rule [[WindSpeed (> windspeed 25)]]
                              (println "Placeholder"))

          rulebase  (mk-session [cold-rule windy-rule])

          cold-rule2 (dsl/parse-rule [[Temperature (< temperature 20)]]
                              (println "Placeholder"))
          windy-rule2 (dsl/parse-rule [[WindSpeed (> windspeed 25)]]
                               (println "Placeholder"))

          rulebase2 (mk-session [cold-rule2 windy-rule2])]

      ;; The keys should be consistent between maps since the rules are identical.
      (is (= (keys (:id-to-node rulebase))
             (keys (:id-to-node rulebase2))))

      ;; Ensure there are beta and production nodes as expected.
      (is (= 4 (count (:id-to-node rulebase)))))))

(deftest test-simple-test
  (let [distinct-temps-query (dsl/parse-query [] [[Temperature (< temperature 20) (= ?t1 temperature)]
                                                  [Temperature (< temperature 20) (= ?t2 temperature)]
                                                  [:test (< ?t1 ?t2)]])

        session (-> (mk-session [distinct-temps-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI")))]

    ;; Finds two temperatures such that t1 is less than t2.
    (is (= #{ {:?t1 10, :?t2 15}}
           (set (query session distinct-temps-query))))))

(deftest test-bean-support

  ;; Use TimeZone for this test as it is an available JavaBean-like object.
  (let [tz-offset-query (dsl/parse-query [:?offset]
                                  [[TimeZone (= ?offset rawOffset)
                                             (= ?id ID)]])

        session (-> (mk-session [tz-offset-query])
                    (insert (TimeZone/getTimeZone "America/Chicago")
                            (TimeZone/getTimeZone "UTC")))]

    (is (= #{{:?id "America/Chicago" :?offset -21600000}}
           (set (query session tz-offset-query :?offset -21600000))))

    (is (= #{{:?id "UTC" :?offset 0}}
           (set (query session tz-offset-query :?offset 0))))))


(deftest test-multi-insert-retract

  (is (= #{{:?loc "MCI"} {:?loc "BOS"}}
         (set (-> (mk-session 'clara.sample-ruleset)
                  (insert (->Temperature 15 "MCI"))
                  (insert (->Temperature 22 "BOS"))

                  ;; Insert a duplicate and then retract it.
                  (insert (->Temperature 22 "BOS"))
                  (retract (->Temperature 22 "BOS"))
                  (fire-rules)
                  (query sample/freezing-locations)))))

  ;; Normal retractions should still work.
  (is (= #{}
         (set (-> (mk-session 'clara.sample-ruleset)
                  (insert (->Temperature 15 "MCI"))
                  (insert (->Temperature 22 "BOS"))
                  (retract (->Temperature 22 "BOS") (->Temperature 15 "MCI"))
                  (fire-rules)
                  (query sample/freezing-locations)))))


  (let [session (-> (mk-session 'clara.sample-ruleset)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->WindSpeed 45 "MCI"))

                    ;; Insert a duplicate and then retract it.
                    (insert (->WindSpeed 45 "MCI"))
                    (retract (->WindSpeed 45 "MCI"))
                    (fire-rules))]

    (is (= #{{:?fact (->ColdAndWindy 15 45)}}
           (set
            (query session sample/find-cold-and-windy))))))

(deftest test-retract!
  (let [not-cold-rule (dsl/parse-rule [[Temperature (> temperature 50)]]
                               (retract! (->Cold 20)))

        cold-query (dsl/parse-query [] [[Cold (= ?t temperature)]])

        session (-> (mk-session [not-cold-rule cold-query])
                    (insert (->Cold 20))
                    (fire-rules))]

    ;; The session should contain our initial cold reading.
    (is (= #{{:?t 20}}
           (set (query session cold-query))))

    ;; Insert a higher temperature and ensure the cold fact was retracted.
    (is (= #{}
           (set (query (-> session
                           (insert (->Temperature 80 "MCI"))
                           (fire-rules))
                       cold-query))))))

(deftest test-destructured-args

  (comment
    (let [cold-query (dsl/parse-query [] [[Temperature [{temp-arg :temperature}] (< temp-arg 20) (= ?t temp-arg)]])

          session (-> (mk-session [cold-query])
                      (insert (->Temperature 15 "MCI"))
                      (insert (->Temperature 10 "MCI"))
                      (insert (->Temperature 80 "MCI")))]

      (is (= #{{:?t 15} {:?t 10}}
             (set (query session cold-query)))))))

(deftest test-general-map
  (let [cold-query (dsl/parse-query []
                             [[:temperature [{temp :value}] (< temp 20) (= ?t temp)]])

        session (-> (mk-session [cold-query] :fact-type-fn :type)
                    (insert {:type :temperature :value 15 :location "MCI"}
                            {:type :temperature :value 10 :location "MCI"}
                            {:type :windspeed :value 5 :location "MCI"}
                            {:type :temperature :value 80 :location "MCI"}))]

    (is (= #{{:?t 15} {:?t 10}}
           (set (query session cold-query))))))

(defrecord RecordWithDash [test-field])

(deftest test-bean-with-dash
  (let [test-query (dsl/parse-query [] [[RecordWithDash (= ?f test-field)]])

        session (-> (mk-session [test-query])
                    (insert (->RecordWithDash 15)))]

    (is (= #{{:?f 15}}
           (set (query session test-query))))))


(deftest test-no-loop
  (let [reduce-temp (dsl/parse-rule [[?t <- Temperature (> temperature 0) (= ?v temperature)]]
                             (do
                               (retract! ?t)
                               (insert! (->Temperature (- ?v 1) "MCI")))
                             {:no-loop true})

        temp-query (dsl/parse-query [] [[Temperature (= ?t temperature)]])


        session (-> (mk-session [reduce-temp temp-query])
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    ;; Only one reduced temperature should be present.
    (is (= [{:?t 9}] (query session temp-query)))))

;; Test to ensure :no-loop applies to retractions as well as activations of rules.
;; For https://github.com/rbrush/clara-rules/issues/99
(deftest test-no-loop-retraction
  (let [counter (atom 0)
        looper (dsl/parse-rule [[:not [:marker]]]

                               (do
                                 ;; Safety net to avoid infinite loop if test fails.
                                 (swap! counter inc)
                                 (when (> @counter 1)
                                   (throw (ex-info "No loop counter should not activate more than once!" {})))

                                 (insert! ^{:type :marker} {:has-marker true}))

                               {:no-loop true})
        has-marker (dsl/parse-query [] [[?marker <- :marker]])]

    (is (= [{:?marker {:has-marker true}}]
           (-> (mk-session [looper has-marker])
               (fire-rules)
               (query has-marker))))))

(defrule reduce-temp-no-loop
  "Example rule to reduce temperature."
  {:no-loop true}
  [?t <- Temperature (= ?v temperature)]
  =>
  (do
    (retract! ?t)
    (insert-unconditional! (->Temperature (- ?v 1) "MCI"))))

(deftest test-no-temp

  (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.

        temp-query (dsl/parse-query [] [[Temperature (= ?t temperature)]])

        session (-> (mk-session [reduce-temp-no-loop temp-query])
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    ;; Only one reduced temperature should be present.
    (is (= [{:?t 9}] (query session temp-query)))))


;; Test behavior discussed in https://github.com/rbrush/clara-rules/issues/35
(deftest test-identical-facts
  (let [ident-query (dsl/parse-query [] [[?t1 <- Temperature (= ?loc location)]
                                         [?t2 <- Temperature (= ?loc location)]
                                         [:test (not (identical? ?t1 ?t2))]])

        temp (->Temperature 15 "MCI")
        temp2 (->Temperature 15 "MCI")

        session (-> (mk-session [ident-query])
                    (insert temp
                            temp))

        session-with-dups (-> (mk-session [ident-query])
                              (insert temp
                                      temp2))]

    ;; The inserted facts are identical, so there cannot be a non-identicial match.
    (is (empty? (query session ident-query)))


    ;; Duplications should have two matches, since either fact can bind to either condition.
    (is (= [{:?t1 #clara.rules.testfacts.Temperature{:temperature 15, :location "MCI"}
             :?t2 #clara.rules.testfacts.Temperature{:temperature 15, :location "MCI"}
             :?loc  "MCI"}
            {:?t2 #clara.rules.testfacts.Temperature{:temperature 15, :location "MCI"}
             :?t1 #clara.rules.testfacts.Temperature{:temperature 15, :location "MCI"}
             :?loc "MCI"}]
           (query session-with-dups ident-query)))))


;; An EDN string for testing. This would normally be stored in an external file. The structure simply needs to be a
;; sequence of maps matching the clara.rules.schema/Production schema.
(def external-rules "[{:name \"cold-query\", :params #{:?l}, :lhs [{:type clara.rules.testfacts.Temperature, :constraints [(< temperature 50) (= ?t temperature) (= ?l location)]}]}]")

(deftest test-external-rules
  (let [session (-> (mk-session (edn/read-string external-rules))
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 20 "MCI")) ; Test multiple items in result.
                    (insert (->Temperature 10 "ORD"))
                    (insert (->Temperature 35 "BOS"))
                    (insert (->Temperature 80 "BOS")))]

    ;; Query by location.
    (is (= #{{:?l "BOS" :?t 35}}
           (set (query session "cold-query" :?l "BOS"))))

    (is (= #{{:?l "MCI" :?t 15} {:?l "MCI" :?t 20}}
           (set (query session "cold-query" :?l "MCI"))))

    (is (= #{{:?l "ORD" :?t 10}}
           (set (query session "cold-query" :?l "ORD"))))))


(defsession my-session 'clara.sample-ruleset)

(deftest test-defsession

  (is (= #{{:?loc "MCI"} {:?loc "BOS"}}
       (set (-> my-session
                (insert (->Temperature 15 "MCI"))
                (insert (->Temperature 22 "BOS"))
                (insert (->Temperature 50 "SFO"))
                (query sample/freezing-locations)))))

  (let [session (-> (mk-session 'clara.sample-ruleset)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->WindSpeed 45 "MCI"))
                    (fire-rules))]

    (is (= #{{:?fact (->ColdAndWindy 15 45)}}
           (set
            (query session sample/find-cold-and-windy))))))

;; Test for issue 46
(deftest test-different-return-type
  (let [q1 (dsl/parse-query [] [[?var1 <- (acc/count) :from [Temperature]]])
        q2 (dsl/parse-query [] [[?var2 <- (acc/count) :from [Temperature]]])

        session (-> (mk-session [q1 q2])
                    (insert (->Temperature 40 "MCI")
                            (->Temperature 50 "SFO"))
                    (fire-rules))]

    (is (= [{:?var1 2}] (query session q1)))
    (is (= [{:?var2 2}] (query session q2)))))

;; Test for overridding how type ancestors are determined.
(deftest test-override-ancestors
  (let [special-ancestor-query (dsl/parse-query [] [[?result <- :my-ancestor]])
        type-ancestor-query (dsl/parse-query [] [[?result <- Object]])

        session (-> (mk-session [special-ancestor-query type-ancestor-query] :ancestors-fn (fn [type] [:my-ancestor]))
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI")))]

    ;; The special ancestor query should match everything since our trivial
    ;; ancestry function treats :my-ancestor as an ancestor of everything.
    (is (= #{{:?result (->Temperature 15 "MCI") }
             {:?result (->Temperature 10 "MCI") }
             {:?result (->Temperature 80 "MCI") }}
           (set (query session special-ancestor-query))))

    ;; There shouldn't be anything that matches our typical ancestor here.
    (is (empty? (query session type-ancestor-query)))))


(deftest test-shared-condition
  (let [cold-query (dsl/parse-query [] [[Temperature (< temperature 20) (= ?t temperature)]])
        cold-windy-query (dsl/parse-query [] [[Temperature (< temperature 20) (= ?t temperature)]
                                              [WindSpeed (> windspeed 25)]])

        beta-roots (com/to-beta-tree [cold-query cold-windy-query])]

    ;; Since the conditions are shared, there should only be one beta root in the network.
    (is (= 1 (count beta-roots)))))

;; Tests to insure an item inserted and retracted during a rule fire sequence
;; is properly removed.
(deftest test-retract-inserted-during-rule
  (let [history (dsl/parse-rule [[?temps <- (acc/distinct) :from [Temperature]]]
                                (insert! (->TemperatureHistory ?temps)))

        temp-query (dsl/parse-query [] [[?t <- TemperatureHistory]])

        ;; Rule only for creating data when fired to expose this bug.
        create-data (dsl/parse-rule []
                                    (insert! (->Temperature 20 "MCI")
                                             (->Temperature 25 "MCI")
                                             (->Temperature 30 "SFO")))

        ;; The bug this is testing dependend on rule order, so we test
        ;; multiple orders.
        session1  (-> (mk-session [create-data temp-query history])
                      (t/with-tracing)
                      (fire-rules))

        session2 (-> (mk-session [history create-data temp-query])
                     (fire-rules))

        session3 (-> (mk-session [history temp-query create-data])
                     (fire-rules))

        session4 (-> (mk-session [temp-query create-data history])
                     (fire-rules))]

    ;; We should match an empty list to start.
    (is (= [{:?t (->TemperatureHistory #{(->Temperature 20 "MCI")
                                         (->Temperature 25 "MCI")
                                         (->Temperature 30 "SFO")})}]
           (query session1 temp-query)
           (query session2 temp-query)
           (query session3 temp-query)
           (query session4 temp-query)))))


(deftest test-retract-inserted-during-rule-with-salience
  (let [history (dsl/parse-rule [[?temps <- (acc/distinct) :from [Temperature]]]
                                (insert! (->TemperatureHistory ?temps))
                                {:salience -10})

        temp-query (dsl/parse-query [] [[?t <- TemperatureHistory]])

        ;; Rule only for creating data when fired to expose this bug.
        create-data (dsl/parse-rule []
                                    (insert! (->Temperature 20 "MCI")
                                             (->Temperature 25 "MCI")
                                             (->Temperature 30 "SFO")))

        ;; The bug this is testing dependend on rule order, so we test
        ;; multiple orders.
        session1  (-> (mk-session [create-data temp-query history])
                      (t/with-tracing)
                      (fire-rules))

        session2 (-> (mk-session [history create-data temp-query])
                                    (fire-rules))

        session3 (-> (mk-session [history temp-query create-data])
                                    (fire-rules))

        session4 (-> (mk-session [temp-query create-data history])
                                    (fire-rules))]


    ;; We should match an empty list to start.
    (is (= [{:?t (->TemperatureHistory #{(->Temperature 20 "MCI")
                                         (->Temperature 25 "MCI")
                                         (->Temperature 30 "SFO")})}]
           (query session1 temp-query)
           (query session2 temp-query)
           (query session3 temp-query)
           (query session4 temp-query)))))

(deftest test-query-for-many-added-elements
  (let [n 6000
        temp-query (dsl/parse-query [] [[Temperature (= ?t temperature)]])

        ;; Do not batch insert to expose any StackOverflowError potential
        ;; of stacking lazy evaluations in working memory.
        session (reduce insert (mk-session [temp-query])
                        (for [i (range n)] (->Temperature i "MCI")))
        session (fire-rules session)]

    (is (= n
           (count (query session temp-query))))))

(deftest test-query-for-many-added-tokens
  (let [n 6000
        cold-temp (dsl/parse-rule [[Temperature (< temperature 30) (= ?t temperature)]]
                                  (insert! (->Cold ?t)))
        cold-query (dsl/parse-query [] [[Cold (= ?t temperature)]])

        ;; Do not batch insert to expose any StackOverflowError potential
        ;; of stacking lazy evaluations in working memory.
        session (reduce insert (mk-session [cold-temp cold-query])
                        (for [i (range n)] (->Temperature (- i) "MCI")))

        session (fire-rules session)]

    (is (= n
           (count (query session cold-query))))))

(deftest test-retracting-many-logical-insertions-for-same-rule
  (let [n 6000
        ;; Do a lot of individual logical insertions for a single rule firing to
        ;; expose any StackOverflowError potential of stacking lazy evaluations in working memory.
        cold-temp (dsl/parse-rule [[Temperature (< temperature 30) (= ?t temperature)]]
                                  ;; Many insertions based on the Temperature fact.
                                  (dotimes [i n]
                                    (insert! (->Cold (- ?t i)))))
        ;; Note: Adding a binding to this query, such as [Cold (= ?t temperature)]
        ;; will cause poor performance (really slow) for 6K retractions.
        ;; This is due to an issue with how retractions on elements is done at a per-grouped
        ;; on :bindings level.  If every Cold fact has a different temperature, none of them
        ;; share a :bindings when retractions happen.  This causes a lot of seperate, expensive
        ;; retractions in the network.
        ;; We need to find a way to do this in batch when/if possible.
        cold-query (dsl/parse-query [] [[Cold]])

        session (-> (mk-session [cold-temp cold-query])
                    (insert (->Temperature 10 "MCI"))
                    fire-rules)]

    ;; Show the initial state for a sanity check.
    (is (= n
           (count (query session cold-query))))

    ;; Retract the Temperature fact that supports all of the
    ;; logical insertions.  This would trigger a StackOverflowError
    ;; if the insertions were stacked lazily "from the head".
    (is (= 0
           (count (query (-> session
                             (retract (->Temperature 10 "MCI"))
                             fire-rules)
                         cold-query))))))

(deftest test-many-retract-accumulated-for-same-accumulate-with-join-filter-node
  (let [n 6000

        count-cold-temps (dsl/parse-rule [[Cold (= ?cold-temp temperature)]
                                          [?temp-count <- (acc/count) :from [Temperature (some? temperature) (<= temperature ?cold-temp)]]]
                                         (insert! ^{:type :temp-counter} {:count ?temp-count}))
        cold-temp-count-query (dsl/parse-query [] [[:temp-counter [{:keys [count]}] (= ?count count)]])


        session  (reduce insert
                         (mk-session [count-cold-temps
                                      cold-temp-count-query])
                         ;; Insert all temperatures one at a time to ensure the
                         ;; accumulate node will continuously re-accumulate via
                         ;; `right-activate-reduced` to expose any StackOverflowError
                         ;; potential of stacking lazy evaluations in working memory.
                         (for [t (range n)] (->Temperature (- t) "MCI")))
        session (-> session
                    (insert (->Cold 30))
                    fire-rules)]

    ;; All temperatures are under the Cold temperature threshold.
    (is (= #{{:?count 6000}} (set (query session cold-temp-count-query))))))

(def maybe-nil-min-temp (accumulate
                         :reduce-fn (fn [value item]
                                      (let [t (:temperature item)]
                                        ;; When no :temperature return `value`.
                                        ;; Note: `value` could be nil.
                                        (if (and t
                                                 (or (= value nil)
                                                     (< t (:temperature value))))
                                          item
                                          value)))))

(deftest test-nil-accum-reduced-has-tokens-retracted-when-new-item-inserted
  ;;
  ;; Using a simple AccumulateNode.
  ;;
  (let [coldest-temp-rule (dsl/parse-rule [[?coldest <- maybe-nil-min-temp :from [Temperature]]]

                                          (insert! (->Cold (:temperature ?coldest))))

        coldest-temp-query (dsl/parse-query [] [[?cold <- Cold]])

        temp-nil (->Temperature nil "MCI")
        temp-10 (->Temperature 10 "MCI")

        session (mk-session [coldest-temp-rule coldest-temp-query])
        insert-nil-first-session (-> session
                                     (insert temp-nil)
                                     (insert temp-10)
                                     fire-rules)
        insert-nil-second-session (-> session
                                      (insert temp-10)
                                      (insert temp-nil)
                                      fire-rules)]

    (is (= (count (query insert-nil-first-session coldest-temp-query))
           (count (query insert-nil-second-session coldest-temp-query)))
        "Failed expected counts when flipping insertion order for AccumulateNode.")

    (is (= #{{:?cold (->Cold 10)}}
           (set (query insert-nil-first-session coldest-temp-query))
           (set (query insert-nil-second-session coldest-temp-query)))
        "Failed expected query results when flipping insertion order for AccumulateNode.")

    ;;
    ;; Using a special AccumulateWithJoinNode.
    ;;
    (let [coldest-temp-rule (dsl/parse-rule [[:max-threshold [{:keys [temperature]}]
                                              (= ?max-temp temperature)]

                                             ;; Note a non-equality based unification.
                                             ;; Gets max temp under a given max threshold.
                                             [?coldest <- maybe-nil-min-temp :from [Temperature
                                                                                    ;; Gracefully handle nil.
                                                                                    (< (or temperature 0)
                                                                                       ?max-temp)]]]

                                            (insert! (->Cold (:temperature ?coldest))))

        session (mk-session [coldest-temp-rule coldest-temp-query])
        insert-nil-first-session (-> session
                                     (insert (with-meta {:temperature 15} {:type :max-threshold}))
                                     (insert temp-nil)
                                     (insert temp-10)
                                     fire-rules)
        insert-nil-second-session (-> session
                                      (insert (with-meta {:temperature 15} {:type :max-threshold}))
                                      (insert temp-10)
                                      (insert temp-nil)
                                      fire-rules)]

    (is (= (count (query insert-nil-first-session coldest-temp-query))
           (count (query insert-nil-second-session coldest-temp-query)))
        "Failed expected counts when flipping insertion order for AccumulateWithJoinNode.")

    (is (= #{{:?cold (->Cold 10)}}
           (set (query insert-nil-first-session coldest-temp-query))
           (set (query insert-nil-second-session coldest-temp-query)))
        "Failed expected query results when flipping insertion order for AccumulateWithJoinNode."))))

(deftest test-nil-accum-reduced-has-tokens-retracted-when-item-retracted
  ;;
  ;; Using a simple AccumulateNode.
  ;;
  (let [coldest-temp-rule (dsl/parse-rule [[?coldest <- maybe-nil-min-temp :from [Temperature]]]

                                          (insert! (->Cold (:temperature ?coldest))))

        coldest-temp-query (dsl/parse-query [] [[?cold <- Cold]])

        nil-temp (->Temperature nil "MCI")
        session (-> (mk-session [coldest-temp-rule coldest-temp-query])
                    (insert nil-temp)
                    (retract nil-temp)
                    fire-rules)]

    (is (empty? (set (query session coldest-temp-query)))
        "Failed expected empty query results for AccumulateNode.")

    ;;
    ;; Using a special AccumulateWithJoinNode.
    ;;
    (let [coldest-temp-rule (dsl/parse-rule [[:max-threshold [{:keys [temperature]}]
                                              (= ?max-temp temperature)]

                                             ;; Note a non-equality based unification.
                                             ;; Gets max temp under a given max threshold.
                                             [?coldest <- maybe-nil-min-temp :from [Temperature
                                                                                    ;; Gracefully handle nil.
                                                                                    (< (or temperature 0)
                                                                                       ?max-temp)]]]

                                            (insert! (->Cold (:temperature ?coldest))))

          session (-> (mk-session [coldest-temp-rule coldest-temp-query])
                      (insert (with-meta {:temperature 10} {:type :max-threshold}))
                      (insert nil-temp)
                      (retract nil-temp)
                      fire-rules)]

      (is (empty? (set (query session coldest-temp-query)))
          "Failed expected empty query results for AccumulateWithJoinNode."))))

(def external-type :temperature)

(def external-constant 20)

(defquery match-external
  []
  [external-type [{temp :value}] (< temp external-constant) (= ?t temp)])

(deftest test-match-external-type
  (let [session (-> (mk-session [match-external] :fact-type-fn :type)
                    (insert {:type :temperature :value 15 :location "MCI"}
                            {:type :temperature :value 10 :location "MCI"}
                            {:type :windspeed :value 5 :location "MCI"}
                            {:type :temperature :value 80 :location "MCI"}))]

    (is (= #{{:?t 15} {:?t 10}}
           (set (query session match-external))))))

(deftest test-extract-simple-test
  (let [distinct-temps-query (dsl/parse-query [] [[Temperature (< temperature 20)
                                                               (= ?t1 temperature)]

                                                  [Temperature (< temperature 20)
                                                               (= ?t2 temperature)
                                                   (< ?t1 temperature)]])

        session  (-> (mk-session [distinct-temps-query])
                     (insert (->Temperature 15 "MCI"))
                     (insert (->Temperature 10 "MCI"))
                     (insert (->Temperature 80 "MCI")))]

    ;; Finds two temperatures such that t1 is less than t2.
    (is (= #{ {:?t1 10, :?t2 15}}
           (set (query session distinct-temps-query))))))

(deftest test-extract-nested-test
  (let [distinct-temps-query (dsl/parse-query [] [[Temperature (< temperature 20)
                                                               (= ?t1 temperature)]

                                                  [Temperature (< temperature 20)
                                                               (= ?t2 temperature)
                                                   (< (- ?t1 0) temperature)]])

        session  (-> (mk-session [distinct-temps-query])
                     (insert (->Temperature 15 "MCI"))
                     (insert (->Temperature 10 "MCI"))
                     (insert (->Temperature 80 "MCI")))]

    ;; Finds two temperatures such that t1 is less than t2.
    (is (= #{ {:?t1 10, :?t2 15}}
           (set (query session distinct-temps-query))))))


;; External structure to ensure that salience works with defrule as well.
(def salience-rule-output (atom []))

(defrule salience-rule4
  {:salience -50}
  [Temperature]
  =>
  (swap! salience-rule-output conj -50))

(deftest test-salience

  (let [rule1 (assoc
                  (dsl/parse-rule [[Temperature]]
                                  (swap! salience-rule-output conj 100))
                :props {:salience 100})

        rule2 (assoc
                  (dsl/parse-rule [[Temperature ]]
                                  (swap! salience-rule-output conj 50))
                :props {:salience 50})

        rule3 (assoc
                  (dsl/parse-rule [[Temperature ]]
                                  (swap! salience-rule-output conj 0))
                :props {:salience 0})]

    ;; Ensure the rule output reflects the salience-defined order.
    ;; independently of the order of the rules.
    (dotimes [n 10]

      (reset! salience-rule-output [])

      (-> (mk-session (shuffle [rule1 rule3 rule2 salience-rule4]) :cache false)
          (insert (->Temperature 10 "MCI"))
          (fire-rules))

      (is (= [100 50 0 -50] @salience-rule-output)))))

(deftest test-variable-visibility
  (let [temps-for-locations (dsl/parse-rule [[:location (= ?loc (:loc this))]

                                             [Temperature
                                              (= ?temp temperature)
                                              ;; This can only have one binding right
                                              ;; now due to work that needs to be done
                                              ;; still in clara.rules.compiler/extract-from-constraint
                                              ;; around support multiple bindings in a condition.
                                              (contains? #{?loc} location)]]

                                            (insert! (->Cold ?temp)))

        find-cold (dsl/parse-query [] [[?c <- Cold]])

        session (-> (mk-session [temps-for-locations find-cold])
                    (insert ^{:type :location} {:loc "MCI"})
                    (insert (->Temperature 10 "MCI"))
                    fire-rules)]

    (is (= #{{:?c (->Cold 10)}}
           (set (query session find-cold))))))

(deftest test-nested-binding
  (let [same-wind-and-temp (dsl/parse-query []
                                            [[Temperature (= ?t temperature)]
                                             [WindSpeed (or (= ?t windspeed)
                                                            (= "MCI" location))]])

        session (mk-session [same-wind-and-temp])]

    ;; Matches because temperature and windspeed match.
    (is (= [{:?t 10}]
           (-> session
               (insert (->Temperature 10  "MCI")
                       (->WindSpeed 10  "SFO"))
               (fire-rules)
               (query same-wind-and-temp))))

    ;; Matches because cities match.
    (is (= [{:?t 10}]
           (-> session
               (insert (->Temperature 10  "MCI")
                       (->WindSpeed 20  "MCI"))
               (fire-rules)
               (query same-wind-and-temp))))

    ;; No match because neither city nor temperature/windspeed match.
    (is (empty? (-> session
                    (insert (->Temperature 10  "MCI")
                            (->WindSpeed 20  "SFO"))
                    (fire-rules)
                    (query same-wind-and-temp))))))

;; Test for: https://github.com/rbrush/clara-rules/issues/97
(deftest test-nested-binding-with-disjunction
  (let [any-cold? (dsl/parse-rule [[Temperature (= ?t temperature)]
                                   [:or
                                    [Cold (< temperature ?t)]
                                    [Cold (< temperature 5)]]]

                                  (insert! ^{:type :found-cold} {:found true}))

        found-cold (dsl/parse-query [] [[?f <- :found-cold]])

        session (-> (mk-session [any-cold? found-cold])
                    (insert (->Temperature 10 "MCI")
                            (->Cold 5))
                    fire-rules)

        results (query session found-cold)]

    (is (= 1 (count results)))

    (is (= ^{:type found-cold} {:?f {:found true}}
           (first results)))))

(deftest test-negation-with-extracted-test
    (let [colder-temp (dsl/parse-rule [[Temperature (= ?t temperature)]
                                       [:not [Cold (or (< temperature ?t)
                                                       (< temperature 0))]]]

                                    (insert! ^{:type :found-colder} {:found true}))

          find-colder (dsl/parse-query [] [[?f <- :found-colder]])

          session (-> (mk-session [colder-temp find-colder] :cache false))]

      ;; Test no token.
      (is (empty? (-> session
                      (insert (->Cold 11))
                      (fire-rules)
                      (query find-colder))))

      ;; Test simple insertion.
      (is (= [{:?f {:found true}}]
             (-> session
                 (insert (->Temperature 10 "MCI"))
                 (insert (->Cold 11))
                 (fire-rules)
                 (query find-colder))))

      ;; Test insertion with right-hand match first.
      (is (= [{:?f {:found true}}]
             (-> session
                 (insert (->Cold 11))
                 (insert (->Temperature 10 "MCI"))
                 (fire-rules)
                 (query find-colder))))

      ;; Test no fact matching not.
      (is (= [{:?f {:found true}}]
             (-> session
                 (insert (->Temperature 10 "MCI"))
                 (fire-rules)
                 (query find-colder))))

      ;; Test violate negation.
      (is (empty? (-> session
                      (insert (->Cold 9))
                      (insert (->Temperature 10 "MCI"))
                      (fire-rules)
                      (query find-colder))))

      ;; Test violate negation alternate order.
      (is (empty? (-> session
                      (insert (->Temperature 10 "MCI"))
                      (insert (->Cold 9))
                      (fire-rules)
                      (query find-colder))))

      ;; Test retract violation.
      (is (= [{:?f {:found true}}]
           (-> session
               (insert (->Cold 9))
               (insert (->Temperature 10 "MCI"))
               (fire-rules)
               (retract (->Cold 9))
               (fire-rules)
               (query find-colder))))

      ;; Test only partial retraction of violation,
      ;; ensuring the remaining violation holds.
      (is (empty? (-> session
                      (insert (->Cold 9))
                      (insert (->Cold 9))
                      (insert (->Temperature 10 "MCI"))
                      (fire-rules)
                      (retract (->Cold 9))
                      (fire-rules)
                      (query find-colder))))

      ;; Test violate negation after success.
      (is (empty? (-> session
                      (insert (->Cold 11))
                      (insert (->Temperature 10 "MCI"))
                      (fire-rules)
                      (insert (->Cold 9))
                      (fire-rules)
                      (query find-colder))))))

(deftest test-accumulator-with-extracted-test
  (let [q1 (dsl/parse-query []
                            [[?res <- (acc/all) :from [Temperature (= ?t temperature)]]
                             [:test (and ?t (< ?t 10))]])

        q2 (dsl/parse-query []
                            [[?res <- (acc/all) :from [Temperature]]
                             [:test (not (empty? ?res))]])]

    (is (= [{:?res [(->Temperature 9 "MCI")] :?t 9}]
           (-> (mk-session [q1])
               (insert (->Temperature 9 "MCI"))
               (fire-rules)
               (query q1))))

    (is (= [{:?res [(->Temperature 9 "MCI")]}]
           (-> (mk-session [q2])
               (insert (->Temperature 9 "MCI"))
               (fire-rules)
               (query q2))))))

(deftest test-unmatched-nested-binding
  ;; This should throw an exception because ?w may not be bound. There is no
  ;; ancestor of the constraint that includes ?w, so there isn't a consitent value
  ;; that can be associated with ?w, therefore we throw an exception when
  ;; compiling the rules.
  (let [same-wind-and-temp (dsl/parse-query []
                                            [[WindSpeed (or (= "MCI" location)
                                                            (= ?w windspeed))]])]

    (is (thrown? clojure.lang.ExceptionInfo
                 (mk-session [same-wind-and-temp])))))

;; Test for: https://github.com/rbrush/clara-rules/issues/96
(deftest test-destructured-binding
  (let [rule-output (atom nil)
        rule {:name "clara.test-destructured-binding/test-destructured-binding"
              :env {:rule-output rule-output} ; Rule environment so we can check its output.
              :lhs '[{:args [[e a v]]
                     :type :foo
                     :constraints [(= e 1) (= v ?value)]}]
              :rhs '(reset! rule-output ?value)}]

    (-> (mk-session [rule] :fact-type-fn second)
        (insert [1 :foo 42])
        (fire-rules))

    (is (= 42 @rule-output))))

(deftest test-qualified-equals-for-fact-binding
  (let [get-accum-nodes #(->> %
                              .rulebase
                              :beta-roots
                              first
                              :children
                              (filter (partial instance? clara.rules.engine.AccumulateNode)))

        with-qualified-accum-nodes (get-accum-nodes
                                    (mk-session [(dsl/parse-query [] [[Temperature (= ?t temperature)]

                                                                      ;; Use fully-qualified = here.
                                                                      [?c <- (acc/all) :from [Cold (clojure.core/= temperature ?t)]]])]))

        without-qualified-accum-nodes (get-accum-nodes
                                       (mk-session [(dsl/parse-query [] [[Temperature (= ?t temperature)]

                                                                         ;; Use fully-qualified = here.
                                                                         [?c <- (acc/all) :from [Cold (= temperature ?t)]]])]))]

    (is (= (count with-qualified-accum-nodes)
           (count without-qualified-accum-nodes)))))

(deftest test-handle-exception
  (let [int-value (atom nil)
        to-int-rule (dsl/parse-rule [[?s <- String]]
                                    (try
                                      (reset! int-value (Integer/parseInt ?s))
                                      (catch NumberFormatException e
                                        (reset! int-value -1))))]

    ;; the RHS should resolve to the qualified exception name.
    (is (some #{'java.lang.NumberFormatException}
              (flatten (:rhs to-int-rule))))

    ;; The static method call should be qualified
    (is (some #{'java.lang.Integer/parseInt}
              (flatten (:rhs to-int-rule))))

    ;; Test successful integer parse.
    (-> (mk-session [to-int-rule])
        (insert "100")
        (fire-rules))

    (is (= 100 @int-value))

    ;; Test failed integer parse.
    (-> (mk-session [to-int-rule])
        (insert "NotANumber")
        (fire-rules))

    (is (= -1 @int-value))))
