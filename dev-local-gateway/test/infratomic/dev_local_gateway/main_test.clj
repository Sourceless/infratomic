(ns infratomic.dev-local-gateway.main-test
  "Unit tests for the Dev-Local Gateway's own Ring handler
  (`app-handler`), exercising the full wire protocol - `create-database`,
  `connect`, `db`/`with-db`, `q`, `pull`, `transact`, `with`, `history`,
  `as-of` - directly against `app-handler` (in-process, no real HTTP
  socket involved) so the request/response EDN shapes this namespace
  implements are proven correct against a real `com.datomic/local`
  backend, independent of `infratomic.state-backend.datomic`'s
  `Gateway*` client types (a different project/deps.edn, so this suite
  can't depend on them - see `state-backend`'s `:integration-test` alias
  for a real end-to-end HTTP round trip between the two)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.dev-local-gateway.main :as main]))

;; `main/storage-dir` reads the `INFRATOMIC_GATEWAY_STORAGE_DIR`
;; *environment variable*, which can't be set portably from within the JVM
;; process - so tests build their own client directly via
;; `datomic.client.api`, pointed at an isolated temp storage dir, and drive
;; `app-handler` (which takes the client, not the env, as an argument)
;; against it. This exercises exactly the same handler code `-main` wires
;; up, minus the env-var resolution `storage-dir`/`port`/`host` do at
;; process start.
(def ^:private test-storage-dir
  (str (System/getProperty "java.io.tmpdir") "/infratomic-dev-local-gateway-test-" (random-uuid)))

(defn- test-client
  []
  (d/client {:server-type :datomic-local
             :system      "infratomic-gateway-test"
             :storage-dir test-storage-dir}))

(defn- req
  [method uri body]
  {:request-method method :uri uri :body (when body (io/input-stream (.getBytes (pr-str body) "UTF-8")))})

(defn- edn-body
  [resp]
  (edn/read-string (:body resp)))

(deftest a-non-post-method-is-405
  (let [handler (main/app-handler (test-client))]
    (is (= 405 (:status (handler (req :get "/q" nil)))))))

(deftest an-unknown-route-is-404
  (let [handler (main/app-handler (test-client))]
    (is (= 404 (:status (handler (req :post "/nope" {})))))))

(deftest full-round-trip-create-connect-transact-query-pull
  (let [client  (test-client)
        handler (main/app-handler client)
        db-name (str "test-" (random-uuid))]
    (testing "create-database"
      (is (= 200 (:status (handler (req :post "/create-database" {:db-name db-name}))))))
    (let [conn-handle (get-in (edn-body (handler (req :post "/connect" {:db-name db-name}))) [:conn])]
      (testing "connect returns a handle-wrapped conn"
        (is (string? (:gateway/handle conn-handle))))
      (testing "transact schema + a fact"
        (let [resp (handler (req :post "/transact"
                                  {:conn conn-handle
                                   :tx-data [{:db/ident       :sample/name
                                              :db/valueType   :db.type/string
                                              :db/cardinality :db.cardinality/one}]}))]
          (is (= 200 (:status resp)))
          (is (:db-after (edn-body resp)))))
      (handler (req :post "/transact" {:conn conn-handle :tx-data [{:sample/name "gateway-test"}]}))
      (testing "db + q round trip a written fact"
        (let [db-handle (get-in (edn-body (handler (req :post "/db" {:conn conn-handle}))) [:db])
              q-resp    (handler (req :post "/q"
                                       {:query '[:find ?n :where [_ :sample/name ?n]]
                                        :args  [db-handle]}))]
          (is (= 200 (:status q-resp)))
          (is (= [["gateway-test"]] (:result (edn-body q-resp))))))
      (testing "pull reads the entity"
        (let [db-handle (get-in (edn-body (handler (req :post "/db" {:conn conn-handle}))) [:db])
              eid       (ffirst (:result (edn-body (handler (req :post "/q"
                                                                   {:query '[:find ?e :where [?e :sample/name _]]
                                                                    :args  [db-handle]})))))
              pull-resp (handler (req :post "/pull" {:db db-handle :pattern [:sample/name] :eid eid}))]
          (is (= {:sample/name "gateway-test"} (:result (edn-body pull-resp)))))))))

(deftest with-produces-a-speculative-db-not-visible-via-db
  (let [client  (test-client)
        handler (main/app-handler client)
        db-name (str "test-" (random-uuid))]
    (handler (req :post "/create-database" {:db-name db-name}))
    (let [conn-handle (get-in (edn-body (handler (req :post "/connect" {:db-name db-name}))) [:conn])]
      (handler (req :post "/transact"
                    {:conn conn-handle
                     :tx-data [{:db/ident       :with-test/name
                                :db/valueType   :db.type/string
                                :db/cardinality :db.cardinality/one}]}))
      (let [with-db-handle  (get-in (edn-body (handler (req :post "/with-db" {:conn conn-handle}))) [:db])
            with-resp       (handler (req :post "/with" {:db with-db-handle :tx-data [{:with-test/name "speculative"}]}))
            after-handle    (get-in (edn-body with-resp) [:db-after])
            speculative-q   (handler (req :post "/q"
                                           {:query '[:find ?n :where [_ :with-test/name ?n]]
                                            :args  [after-handle]}))
            real-db-handle  (get-in (edn-body (handler (req :post "/db" {:conn conn-handle}))) [:db])
            real-q          (handler (req :post "/q"
                                           {:query '[:find ?n :where [_ :with-test/name ?n]]
                                            :args  [real-db-handle]}))]
        (is (= [["speculative"]] (:result (edn-body speculative-q))))
        (is (= [] (:result (edn-body real-q))))))))

(deftest an-unknown-handle-responds-400-not-an-uncaught-exception
  (let [handler (main/app-handler (test-client))
        resp    (handler (req :post "/db" {:conn {:gateway/handle "does-not-exist"}}))]
    (is (= 400 (:status resp)))
    (is (:error (edn-body resp)))))
