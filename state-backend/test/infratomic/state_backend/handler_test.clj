(ns infratomic.state-backend.handler-test
  "Unit tests for the State Backend's HTTP handlers, exercised against an
  in-memory (non-persistent) Datomic dev-local database so tests are fast
  and isolated from `.datomic/` on disk."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]))

(defn- fresh-conn
  "A connection to a freshly-created, schema-loaded, in-memory dev-local
  database, isolated from any other test by a random db name."
  []
  (let [client (db/client :mem)
        db-name (str "test-" (random-uuid))]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (d/transact conn {:tx-data db/schema})
      conn)))

(defn- resource
  "Build a minimal `resources[]` entry as Terraform's `POST` body would
  contain, for `type.name`, with `attributes` defaulting to `{}`."
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
    "lineage"            "test-lineage"
    "outputs"           {}
    "resources"         resources}))

(defn- resource-ids
  "The `type.name` ids of every resource in a `GET /state` response body."
  [get-response]
  (if (= 204 (:status get-response))
    #{}
    (->> (:body get-response)
         json/parse-string
         (#(get % "resources"))
         (map #(str (get % "type") "." (get % "name")))
         set)))

(deftest post-then-get-round-trips-resources
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")
                                           (resource "aws_iam_role" "lambda_exec")]))
    (is (= #{"aws_s3_bucket.uploads" "aws_iam_role.lambda_exec"}
           (resource-ids (handler/get-state conn))))))

(deftest post-upserts-in-place-not-duplicating
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads" {"bucket" "a"})]))
    (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads" {"bucket" "a"})]))
    (let [resources (-> (handler/get-state conn) :body json/parse-string (get "resources"))]
      (is (= 1 (count resources))))))

(deftest post-retracts-resources-removed-from-the-new-state
  (testing "a resource present in the DB but absent from a subsequent POST is retracted"
    (let [conn (fresh-conn)]
      (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")
                                             (resource "aws_iam_role" "lambda_exec")
                                             (resource "aws_lambda_function" "upload_handler")
                                             (resource "aws_lambda_function_url" "upload_handler")]))
      ;; Simulate destroying `aws_lambda_function_url.upload_handler`: the next
      ;; POST's `resources[]` no longer includes it.
      (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")
                                             (resource "aws_iam_role" "lambda_exec")
                                             (resource "aws_lambda_function" "upload_handler")]))
      (is (= #{"aws_s3_bucket.uploads" "aws_iam_role.lambda_exec" "aws_lambda_function.upload_handler"}
             (resource-ids (handler/get-state conn))))
      (testing "the ghost stays gone across a further no-op POST"
        (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")
                                               (resource "aws_iam_role" "lambda_exec")
                                               (resource "aws_lambda_function" "upload_handler")]))
        (is (= #{"aws_s3_bucket.uploads" "aws_iam_role.lambda_exec" "aws_lambda_function.upload_handler"}
               (resource-ids (handler/get-state conn))))))))

(deftest post-with-no-resources-retracts-all-of-them
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")]))
    (handler/post-state conn (state-body []))
    (is (empty? (resource-ids (handler/get-state conn))))))

(deftest post-invalid-json-returns-400-and-does-not-touch-state
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")]))
    (is (= 400 (:status (handler/post-state conn "not json"))))
    (is (= #{"aws_s3_bucket.uploads"} (resource-ids (handler/get-state conn))))))

(defn- attributes-of
  "The `attributes` map of the single resource named `name` in a `GET
  /state` response body."
  [get-response name]
  (->> get-response
       :body
       json/parse-string
       (#(get % "resources"))
       (filter #(= name (get % "name")))
       first
       (#(get-in % ["instances" 0 "attributes"]))))

(deftest modeled-attributes-round-trip
  (testing "aws_security_group/aws_security_group_rule's modeled attributes survive POST/GET, typed and joinable"
    (let [conn (fresh-conn)]
      (handler/post-state
       conn
       (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-123"})
                    (resource "aws_security_group_rule" "ssh_open_ingress"
                              {"from_port"         22
                               "to_port"            22
                               "protocol"           "tcp"
                               "security_group_id"  "sg-123"
                               "cidr_blocks"        ["0.0.0.0/0"]})]))
      (let [get-resp (handler/get-state conn)]
        (is (= {"id" "sg-123"} (attributes-of get-resp "ssh_open")))
        (is (= {"from_port"         22
                "to_port"            22
                "protocol"           "tcp"
                "security_group_id"  "sg-123"
                "cidr_blocks"        ["0.0.0.0/0"]}
               (attributes-of get-resp "ssh_open_ingress")))))))

(deftest unmodeled-attributes-round-trip-including-nested-values-and-types
  (testing "an unmodeled resource type's attributes - scalars, nested maps/vectors, and value types - round-trip"
    (let [conn (fresh-conn)]
      (handler/post-state
       conn
       (state-body [(resource "aws_s3_bucket" "uploads"
                               {"bucket"        "infratomic-test-app-uploads"
                                "force_destroy" true
                                "tags"          {"Environment" "dev"}
                                "versions"      ["v1" "v2"]
                                "unset"         nil})]))
      (is (= {"bucket"        "infratomic-test-app-uploads"
              "force_destroy" true
              "tags"          {"Environment" "dev"}
              "versions"      ["v1" "v2"]}
             (attributes-of (handler/get-state conn) "uploads"))))))

(deftest upsert-in-place-replaces-attributes-not-merges-them
  (testing "modeled cardinality-many and generic attributes are replaced wholesale on a subsequent POST, not accumulated"
    (let [conn (fresh-conn)]
      (handler/post-state
       conn
       (state-body [(resource "aws_security_group_rule" "rule"
                               {"from_port"         22
                                "to_port"            22
                                "protocol"           "tcp"
                                "security_group_id"  "sg-123"
                                "cidr_blocks"        ["10.0.0.0/16" "10.0.1.0/16"]})
                    (resource "aws_s3_bucket" "uploads" {"tags" {"Environment" "dev"}})]))
      (handler/post-state
       conn
       (state-body [(resource "aws_security_group_rule" "rule"
                               {"from_port"         443
                                "to_port"            443
                                "protocol"           "tcp"
                                "security_group_id"  "sg-123"
                                "cidr_blocks"        ["0.0.0.0/0"]})
                    (resource "aws_s3_bucket" "uploads" {"tags" {"Owner" "team-a"}})]))
      (let [get-resp (handler/get-state conn)]
        (is (= {"from_port"         443
                "to_port"            443
                "protocol"           "tcp"
                "security_group_id"  "sg-123"
                "cidr_blocks"        ["0.0.0.0/0"]}
               (attributes-of get-resp "rule")))
        (is (= {"tags" {"Owner" "team-a"}}
               (attributes-of get-resp "uploads")))))))

(deftest reposting-an-unchanged-cardinality-many-value-does-not-error
  (testing "a cardinality-many modeled attribute whose value set is identical between two POSTs doesn't hit a Datomic same-transaction retract+assert conflict on the unchanged value(s)"
    (let [conn (fresh-conn)
          post! (fn [] (handler/post-state
                        conn
                        (state-body [(resource "aws_security_group_rule" "rule"
                                                {"from_port"         22
                                                 "to_port"            22
                                                 "protocol"           "tcp"
                                                 "security_group_id"  "sg-123"
                                                 "cidr_blocks"        ["0.0.0.0/0"]})])))]
      (post!)
      (is (= 200 (:status (post!))))
      (is (= {"from_port"         22
              "to_port"            22
              "protocol"           "tcp"
              "security_group_id"  "sg-123"
              "cidr_blocks"        ["0.0.0.0/0"]}
             (attributes-of (handler/get-state conn) "rule"))))))

(deftest oversized-attribute-value-does-not-block-persisting-the-resource
  (testing "one attribute value over the 4096-byte limit falls back to opaque storage without failing the transaction or affecting other attributes"
    (let [conn  (fresh-conn)
          huge  (apply str (repeat 5000 "x"))]
      (handler/post-state
       conn
       (state-body [(resource "aws_s3_bucket" "uploads" {"bucket" "small" "policy" huge})]))
      (is (= {"bucket" "small" "policy" huge}
             (attributes-of (handler/get-state conn) "uploads"))))))

;; ---------------------------------------------------------------------------
;; :resource/managed? tagging and read-path filters (issue #26)
;; ---------------------------------------------------------------------------

(defn- discovered-resource-tx
  "A minimal Discovered Resource entity tx-map, as Sync would transact -
  `:resource/managed? false`, a synthesized discovered-style `:resource/id`."
  [type aws-id]
  {:resource/id       (str type ".discovered-" aws-id)
   :resource/type     type
   :resource/managed? false})

(deftest posted-resource-is-tagged-managed
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")]))
    (let [db (d/db conn)]
      (is (= true (:resource/managed? (d/pull db [:resource/managed?] [:resource/id "aws_s3_bucket.uploads"])))))))

(deftest discovered-resource-is-excluded-from-get
  (testing "a :resource/managed? false entity never appears in GET /state, even alongside managed resources"
    (let [conn (fresh-conn)]
      (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")]))
      (d/transact conn {:tx-data [(discovered-resource-tx "aws_security_group" "sg-999")]})
      (is (= #{"aws_s3_bucket.uploads"} (resource-ids (handler/get-state conn)))))))

(deftest discovered-resource-survives-a-post-that-does-not-mention-it
  (testing "POST's stale-resource retraction sweep never retracts a Discovered Resource"
    (let [conn (fresh-conn)]
      (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")]))
      (d/transact conn {:tx-data [(discovered-resource-tx "aws_security_group" "sg-999")]})
      ;; A subsequent POST that doesn't mention the Discovered Resource at all.
      (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")]))
      (let [db (d/db conn)]
        (is (some? (d/pull db [:resource/id] [:resource/id "aws_security_group.discovered-sg-999"])))))))

(deftest backfill-tags-pre-existing-untagged-entities-and-is-idempotent
  (testing "db/backfill-managed-flag! (the step ensure-db! runs on every startup) sets :resource/managed? true on resources that predate the attribute, and is a no-op on a second call"
    (let [conn (fresh-conn)]
      ;; Simulate a pre-existing (pre-issue-#26) database: transact a
      ;; resource entity directly, bypassing resource->tx, so it has no
      ;; :resource/managed? value at all.
      (d/transact conn {:tx-data [{:resource/id   "aws_s3_bucket.legacy"
                                    :resource/type "aws_s3_bucket"}]})
      (db/backfill-managed-flag! conn)
      (is (= true (:resource/managed? (d/pull (d/db conn) [:resource/managed?] [:resource/id "aws_s3_bucket.legacy"]))))
      ;; Calling the backfill again (as a subsequent process start would)
      ;; must not error and must leave the now-tagged entity's value
      ;; unchanged (a no-op).
      (db/backfill-managed-flag! conn)
      (is (= true (:resource/managed? (d/pull (d/db conn) [:resource/managed?] [:resource/id "aws_s3_bucket.legacy"])))))))
