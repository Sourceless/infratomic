(ns infratomic.state-backend.main-test
  "Unit tests for `main.clj`'s `app-handler` routing - specifically that
  `/policy-check` with a method other than `POST` gets an explicit `405`
  rather than falling through to `handler.clj`'s `/state`-only dispatch
  and getting a misleading `404` (see the routing `cond` in `app-handler`).
  `handler_test.clj`/`policy_test.clj` already cover each handler's own
  behavior in depth; this file only covers the dispatch between them."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.main :as main]
            [infratomic.state-backend.policy :as policy]))

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

(deftest drift-with-a-non-get-method-is-405-not-404
  ;; nil ec2-client is safe here - /drift never touches it.
  (let [handler (main/app-handler (fresh-conn) nil)]
    (is (= 405 (:status (handler {:request-method :post :uri "/drift"}))))
    (is (= 405 (:status (handler {:request-method :delete :uri "/drift"}))))))

(deftest drift-with-get-returns-200-with-an-empty-list-on-a-fresh-db
  (let [handler (main/app-handler (fresh-conn) nil)
        response (handler {:request-method :get :uri "/drift"})]
    (is (= 200 (:status response)))
    (is (= [] (get (json/parse-string (:body response)) "drifted")))))

(deftest query-with-a-non-post-method-is-405-not-404
  (let [handler (main/app-handler (fresh-conn) nil)]
    (is (= 405 (:status (handler {:request-method :get :uri "/query"}))))
    (is (= 405 (:status (handler {:request-method :delete :uri "/query"}))))))

(deftest query-with-post-runs-the-query-against-the-live-db
  (let [handler (main/app-handler (fresh-conn) nil)
        body    (java.io.ByteArrayInputStream.
                 (.getBytes (pr-str '{:find [?e] :where [[?e :resource/id ?id]]}) "UTF-8"))
        response (handler {:request-method :post :uri "/query" :body body})]
    (is (= 200 (:status response)))))

(deftest rules-with-a-non-post-method-is-405-not-404
  (let [handler (main/app-handler (fresh-conn) nil)]
    (is (= 405 (:status (handler {:request-method :get :uri "/rules"}))))
    (is (= 405 (:status (handler {:request-method :delete :uri "/rules"}))))))

(deftest apply-with-a-non-post-method-is-405-not-404
  (let [handler (main/app-handler (fresh-conn) nil)]
    (is (= 405 (:status (handler {:request-method :get :uri "/apply"}))))
    (is (= 405 (:status (handler {:request-method :delete :uri "/apply"}))))))

(deftest import-with-a-non-post-method-is-405-not-404
  (let [handler (main/app-handler (fresh-conn) nil)]
    (is (= 405 (:status (handler {:request-method :get :uri "/import"}))))
    (is (= 405 (:status (handler {:request-method :delete :uri "/import"}))))))

(deftest destroy-with-a-non-post-method-is-405-not-404
  (let [handler (main/app-handler (fresh-conn) nil)]
    (is (= 405 (:status (handler {:request-method :get :uri "/destroy"}))))
    (is (= 405 (:status (handler {:request-method :delete :uri "/destroy"}))))))

(deftest rules-with-post-registers-a-rule
  (let [handler (main/app-handler (fresh-conn) nil)
        rule-id (keyword (str "main-test-rule-" (random-uuid)))
        body    (java.io.ByteArrayInputStream.
                 (.getBytes (pr-str {:rule/id rule-id :rule/find '[?e] :rule/in '[$]
                                      :rule/where '[[?e :resource/id ?id]]})
                            "UTF-8"))]
    (try
      (let [response (handler {:request-method :post :uri "/rules" :body body})]
        (is (= 200 (:status response))))
      (finally
        (swap! policy/rule-registry dissoc rule-id)))))
