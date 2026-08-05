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
