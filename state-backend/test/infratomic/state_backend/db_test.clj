(ns infratomic.state-backend.db-test
  "Unit tests for `db.clj`'s connection-and-bootstrap path
  (`ensure-db!`/`client`) against the facade
  (`infratomic.state-backend.datomic`), as opposed to every other test
  namespace's `fresh-conn` helper, which builds its db directly via
  `datomic.client.api` and so never exercises `db/client`/`db/ensure-db!`
  themselves. `db/ensure-db!` is what `-main` (both the normal startup
  path and `bootstrap`) actually calls - a facade dispatch gap here (e.g.
  a multimethod missing a `:default` method for the raw, unwrapped
  embedded client `db/client` returns) would pass every other test in
  this suite yet crash the real `-main` entrypoint immediately, which is
  exactly what happened here before these tests existed (see git
  history) - `-main`/`bootstrap` are otherwise only exercised by
  `query-integration-test`/`sync-integration-test`, excluded from the
  default hermetic `clojure -X:test` run."
  (:require [clojure.test :refer [deftest is]]
            [infratomic.state-backend.datomic :as d]
            [infratomic.state-backend.db :as db]))

(deftest ensure-db!-against-the-embedded-client-creates-a-usable-connected-db
  (let [client (db/client (str (System/getProperty "java.io.tmpdir") "/infratomic-db-test-" (random-uuid)))
        conn   (db/ensure-db! client)]
    (is (some? conn))
    (is (= [] (d/q '[:find ?e :where [?e :resource/id]] (d/db conn))))))

(deftest ensure-db!-is-idempotent
  (let [client (db/client (str (System/getProperty "java.io.tmpdir") "/infratomic-db-test-" (random-uuid)))]
    (db/ensure-db! client)
    (let [conn (db/ensure-db! client)]
      (is (some? conn)))))
