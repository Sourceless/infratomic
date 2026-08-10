(ns infratomic.state-backend.main-test
  "Unit tests for `main.clj`'s `app-handler` routing - specifically that
  `/policy-check` with a method other than `POST` gets an explicit `405`
  rather than falling through to `handler.clj`'s `/state`-only dispatch
  and getting a misleading `404` (see the routing `cond` in `app-handler`).
  `handler_test.clj`/`policy_test.clj` already cover each handler's own
  behavior in depth; this file only covers the dispatch between them."
  (:require [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.main :as main]))

(defn- fresh-conn
  "A connection to a freshly-created, schema-loaded, in-memory dev-local
  database, isolated from any other test by a random db name."
  []
  (let [client  (db/client :mem)
        db-name (str "test-" (random-uuid))]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (d/transact conn {:tx-data db/schema})
      conn)))

(deftest policy-check-with-a-non-post-method-is-405-not-404
  (let [handler (main/app-handler (fresh-conn) nil)]
    (is (= 405 (:status (handler {:request-method :get :uri "/policy-check"}))))
    (is (= 405 (:status (handler {:request-method :delete :uri "/policy-check"}))))))

(deftest sync-with-a-non-post-method-is-405-not-404
  ;; nil ec2-client is safe here - sync/sync-endpoint (which would need a
  ;; real client) is never reached for a non-POST method.
  (let [handler (main/app-handler (fresh-conn) nil)]
    (is (= 405 (:status (handler {:request-method :get :uri "/sync"}))))
    (is (= 405 (:status (handler {:request-method :delete :uri "/sync"}))))))

(deftest unknown-uri-still-404s
  (let [handler (main/app-handler (fresh-conn) nil)]
    (is (= 404 (:status (handler {:request-method :get :uri "/nope"}))))))
