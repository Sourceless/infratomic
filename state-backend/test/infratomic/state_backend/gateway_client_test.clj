(ns infratomic.state-backend.gateway-client-test
  "Proves `gateway` mode genuinely works end to end: a real Dev-Local
  Gateway HTTP server (`infratomic.dev-local-gateway.main/app-handler`,
  started once for this namespace, backed by its own isolated temp
  storage directory - independent of both `.datomic/` and any other
  test's `:mem` storage) is started on a loopback port, and every
  `GatewayClient`/`GatewayConn`/`GatewayDb` facade operation
  (`create-database`, `connect`, `transact`, `db`, `pull`, `q`, `with`,
  `with-db`, `history`, `as-of`) is exercised through real HTTP+EDN calls
  by re-running the same behaviors already covered against `embedded` mode
  elsewhere (`handler_test.clj`'s POST/GET round trip, a `query.clj`
  function, `policy.clj`'s speculative `evaluate`, and history-based drift
  detection) - see design.md's \"the facade namespace making `gateway`
  mode exercisable against the exact same behavioral tests already written
  for `embedded` mode\" risk mitigation.

  Hermetic: this test starts its own Dev-Local Gateway process in-process
  (a real Jetty server on a loopback port, not a separately-run OS
  process) and talks to it over real HTTP - no external service (Docker,
  LocalStack, a pre-started Dev-Local Gateway) is required. Part of the
  default hermetic `clojure -X:test` suite."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as real-datomic]
            [infratomic.dev-local-gateway.main :as gateway]
            [infratomic.state-backend.datomic :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.policy :as policy]
            [infratomic.state-backend.query :as query]
            [infratomic.state-backend.sync :as sync]
            [ring.adapter.jetty :as jetty]))

(def ^:private gateway-port 8097)
(def ^:private gateway-base-url (str "http://localhost:" gateway-port))
(def ^:private gateway-storage-dir
  (str (System/getProperty "java.io.tmpdir") "/infratomic-gateway-client-test-" (random-uuid)))

(defonce ^:private gateway-real-client
  (real-datomic/client {:server-type :datomic-local
                         :system      "infratomic-gateway-client-test"
                         :storage-dir gateway-storage-dir}))

;; Started once per test JVM (`defonce`), `join? false` so it doesn't block
;; the test run - real network I/O on a loopback port for every operation
;; the tests below make, exactly as a real State Backend process in
;; `gateway` mode would.
(defonce ^:private gateway-server
  (jetty/run-jetty (gateway/app-handler gateway-real-client) {:port gateway-port :join? false}))

(defn- gateway-client
  []
  (d/client {:mode :gateway :base-url gateway-base-url}))

(defn- fresh-gateway-conn
  "A connection to a freshly-created, schema-loaded database via the
  Dev-Local Gateway - the `gateway`-mode equivalent of every other test
  namespace's `fresh-conn`, isolated from any other test by a random
  db name."
  []
  (let [client  (gateway-client)
        db-name (str "gateway-test-" (random-uuid))]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (d/transact conn {:tx-data db/schema})
      conn)))

(defn- resource
  ([type name] (resource type name {}))
  ([type name attributes]
   {"mode"      "managed"
    "type"      type
    "name"      name
    "provider"  "provider[\"registry.terraform.io/hashicorp/aws\"]"
    "instances" [{"schema_version" 0
                  "attributes"     attributes}]}))

(defn- state-body
  [resources]
  (json/generate-string
   {"version"           4
    "terraform_version" "1.9.0"
    "serial"            1
    "lineage"           "gateway-test-lineage"
    "outputs"           {}
    "resources"         resources}))

(deftest gateway-mode-post-get-round-trip
  (testing "POST /state then GET /state through a GatewayClient/GatewayConn round-trips the
    same way it does against an embedded db - exercises transact, db, and pull over HTTP"
    (let [conn (fresh-gateway-conn)
          resources [(resource "aws_s3_bucket" "uploads" {"bucket" "infratomic-gateway-test-uploads"})]]
      (handler/post-state conn (state-body resources))
      (let [body (-> (handler/get-state conn) :body json/parse-string)]
        (is (= 1 (count (get body "resources"))))
        (is (= "infratomic-gateway-test-uploads"
               (get-in (first (get body "resources")) ["instances" 0 "attributes" "bucket"])))))))

(deftest gateway-mode-query-function-runs-over-http
  (testing "a query.clj function (q) runs correctly against a GatewayDb"
    (let [conn (fresh-gateway-conn)]
      (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-gw-1"})]))
      (let [db (d/db conn)]
        (is (= #{"aws_security_group.ssh_open"}
               (into #{} (map :resource/id) (query/resources-by-type db "aws_security_group"))))))))

(deftest gateway-mode-policy-evaluate-uses-with-and-with-db-over-http
  (testing "policy/evaluate's speculative (d/with (d/with-db conn) ...) path works against a
    GatewayConn, and never persists - exercises with-db and with over HTTP"
    (let [conn (fresh-gateway-conn)
          plan {"planned_values"
                {"root_module"
                 {"resources"
                  [{"address" "aws_security_group.ssh_open" "mode" "managed"
                    "type" "aws_security_group" "name" "ssh_open"
                    "provider_name" "registry.terraform.io/hashicorp/aws" "schema_version" 0
                    "values" {"id" "sg-gw-open"}}
                   {"address" "aws_security_group_rule.ssh_open_ingress" "mode" "managed"
                    "type" "aws_security_group_rule" "name" "ssh_open_ingress"
                    "provider_name" "registry.terraform.io/hashicorp/aws" "schema_version" 0
                    "values" {"from_port" 22 "to_port" 22 "protocol" "tcp"
                              "security_group_id" "sg-gw-open" "cidr_blocks" ["0.0.0.0/0"]}}]}}
                "configuration" {"root_module" {"resources" []}}}]
      (is (= [{:rule :security-groups-with-port-22-open
               :resource/id "aws_security_group.ssh_open"
               :resource/type "aws_security_group"}]
             (policy/evaluate conn plan)))
      (testing "the speculative evaluation never persisted"
        (is (= 204 (:status (handler/get-state conn))))))))

(deftest gateway-mode-drift-detection-uses-history-and-as-of-over-http
  (testing "the drift Rule's d/history/d/as-of comparison works against a GatewayDb"
    (let [conn (fresh-gateway-conn)]
      (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open"
                                                        {"id" "sg-gw-drift" "vpc_id" "vpc-gw-a"})]))
      (let [db (d/db conn)
            {:keys [tx-data outcome]} (sync/resource-tx db "aws_security_group" "sg-gw-drift" {"id" "sg-gw-drift" "vpc_id" "vpc-gw-changed"})]
        (is (= :drifted outcome))
        (d/transact conn {:tx-data tx-data}))
      (is (= #{"aws_security_group.ssh_open"}
             (into #{} (map :resource/id) (query/drifted-resources (d/db conn))))))))
