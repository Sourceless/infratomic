(ns infratomic.state-backend.query-test
  "Unit tests for the query namespace's 4 functions, exercised against an
  in-memory (non-persistent) Datomic dev-local database seeded via the
  handler's `POST` path - mirroring `handler_test.clj`'s fixture pattern -
  so query results are checked against realistically-decomposed data."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.query :as query]))

(defn- fresh-conn
  []
  (let [client  (db/client :mem)
        db-name (str "test-" (random-uuid))]
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
    "lineage"           "test-lineage"
    "outputs"           {}
    "resources"         resources}))

(defn- ids
  [results]
  (into #{} (map :resource/id) results))

(def ^:private sample-resources
  [(resource "aws_s3_bucket" "uploads" {"bucket" "infratomic-test-app-uploads" "force_destroy" true})
   (resource "aws_iam_role" "lambda_exec" {"name" "infratomic-test-app-lambda-exec"})
   (resource "aws_security_group" "ssh_open" {"id" "sg-ssh-open"})
   (resource "aws_security_group_rule" "ssh_open_ingress"
             {"from_port"         22
              "to_port"            22
              "protocol"           "tcp"
              "security_group_id"  "sg-ssh-open"
              "cidr_blocks"        ["0.0.0.0/0"]})
   (resource "aws_security_group" "https_only" {"id" "sg-https-only"})
   (resource "aws_security_group_rule" "https_only_ingress"
             {"from_port"         443
              "to_port"            443
              "protocol"           "tcp"
              "security_group_id"  "sg-https-only"
              "cidr_blocks"        ["0.0.0.0/0"]})
   (resource "aws_security_group" "ssh_restricted" {"id" "sg-ssh-restricted"})
   (resource "aws_security_group_rule" "ssh_restricted_ingress"
             {"from_port"         22
              "to_port"            22
              "protocol"           "tcp"
              "security_group_id"  "sg-ssh-restricted"
              "cidr_blocks"        ["10.0.0.0/16"]})])

(defn- seeded-db
  []
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body sample-resources))
    (d/db conn)))

(deftest all-deployed-resources-lists-every-resource
  (let [db (seeded-db)]
    (is (= (into #{} (map (fn [r] (str (get r "type") "." (get r "name")))) sample-resources)
          (ids (query/all-deployed-resources db))))))

(deftest resources-by-type-filters-to-matching-type-only
  (let [db (seeded-db)]
    (testing "a type with matches"
      (is (= #{"aws_security_group.ssh_open" "aws_security_group.https_only" "aws_security_group.ssh_restricted"}
             (ids (query/resources-by-type db "aws_security_group")))))
    (testing "a type with no matches"
      (is (empty? (query/resources-by-type db "aws_lambda_function"))))))

(deftest resources-by-attribute-value-matches-generic-attribute
  (let [db (seeded-db)]
    (is (= #{"aws_s3_bucket.uploads"}
           (ids (query/resources-by-attribute-value db "bucket" "infratomic-test-app-uploads"))))))

(deftest resources-by-attribute-value-matches-modeled-attribute-with-either-representation
  (let [db (seeded-db)]
    (testing "queried with the attribute's native type"
      (is (= #{"aws_security_group_rule.ssh_open_ingress" "aws_security_group_rule.ssh_restricted_ingress"}
             (ids (query/resources-by-attribute-value db "from_port" 22)))))
    (testing "queried with an equivalent string representation"
      (is (= #{"aws_security_group_rule.ssh_open_ingress" "aws_security_group_rule.ssh_restricted_ingress"}
             (ids (query/resources-by-attribute-value db "from_port" "22")))))))

(deftest resources-by-attribute-value-returns-empty-for-no-match
  (let [db (seeded-db)]
    (is (empty? (query/resources-by-attribute-value db "bucket" "does-not-exist")))
    (is (empty? (query/resources-by-attribute-value db "from_port" 9999)))))

(deftest security-groups-with-port-22-open-includes-only-insecure-groups
  (let [db (seeded-db)]
    (is (= #{"aws_security_group.ssh_open"}
           (ids (query/security-groups-with-port-22-open db))))))
