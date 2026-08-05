(ns infratomic.datomic-test
  "Verifies the local Datomic database round-trip: connect, transact a
  fixture schema, transact a fact, query it back.

  The schema transacted here is minimal, throwaway test plumbing used only
  to exercise the round-trip - it is not app schema design, which is out
  of scope for this change."
  (:require [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [infratomic.datomic :as datomic]))

(deftest round-trip-connect-transact-query
  (let [conn (datomic/connect "datomic-test")]
    (d/transact conn {:tx-data [{:db/ident :sample/name
                                  :db/valueType :db.type/string
                                  :db/cardinality :db.cardinality/one}]})
    (d/transact conn {:tx-data [{:sample/name "hello, datomic"}]})
    (let [result (d/q '[:find ?name
                         :where [_ :sample/name ?name]]
                       (d/db conn))]
      (is (= #{["hello, datomic"]} (set result))))))
