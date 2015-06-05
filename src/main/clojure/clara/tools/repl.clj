(ns clara.tools.repl
  ;(:refer-clojure )
  ;(:require-macros [clara.macros :refer [defrule defquery defsession]])
  (:require [clara.rules.engine :as eng]
            [clara.rules.accumulators :as acc]
            [clara.rules :refer [insert retract fire-rules query insert! retract!]]
            [clojure.main :refer [repl]]))

; (def insert (partial ))

(defn clara-repl []
  (let [session {:a 1}]
    (repl)))

(defn -main []
  (clara-repl)
  ;(clojure.main/repl)
  )
