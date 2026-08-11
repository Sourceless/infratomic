(ns infratomic.state-backend.policy-test
  "Unit tests for the Policy Check's plan-decomposition/Address-Stand-in/
  Rule-evaluation logic and its `POST /policy-check` handler, exercised
  against an in-memory (non-persistent) Datomic dev-local database -
  mirroring `handler_test.clj`'s fixture pattern."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.policy :as policy]
            [infratomic.state-backend.sync :as sync]))

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

(defn- planned-resource
  "One `planned_values.root_module.resources[]` entry."
  [type name values]
  {"address"        (str type "." name)
   "mode"           "managed"
   "type"           type
   "name"           name
   "provider_name"  "registry.terraform.io/hashicorp/aws"
   "schema_version"  0
   "values"         values})

(defn- config-resource
  "One `configuration.root_module.resources[]` entry."
  ([type name] (config-resource type name {}))
  ([type name expressions]
   {"address"             (str type "." name)
    "mode"                "managed"
    "type"                type
    "name"                name
    "provider_config_key" "aws"
    "expressions"         expressions
    "schema_version"       0}))

(defn- plan-doc
  [planned-resources config-resources]
  {"format_version"    "1.2"
   "terraform_version" "1.9.0"
   "planned_values"    {"root_module" {"resources" planned-resources}}
   "configuration"     {"root_module" {"resources" config-resources}}})

(defn- security-group-id-ref
  "The `expressions` map for an `aws_security_group_rule`'s
  `security_group_id` referencing `sg-address` directly."
  [sg-address]
  {"security_group_id" {"references" [(str sg-address ".id") sg-address]}})

(deftest plan-with-no-violations-has-no-violations
  (let [conn (fresh-conn)
        plan (plan-doc
              [(planned-resource "aws_security_group" "https_only" {"id" nil})
               (planned-resource "aws_security_group_rule" "https_only_ingress"
                                 {"from_port" 443 "to_port" 443 "protocol" "tcp"
                                  "security_group_id" nil "cidr_blocks" ["0.0.0.0/0"]})]
              [(config-resource "aws_security_group" "https_only")
               (config-resource "aws_security_group_rule" "https_only_ingress"
                                 (security-group-id-ref "aws_security_group.https_only"))])]
    (is (empty? (policy/evaluate conn plan)))))

(deftest plan-violating-port-22-rule-via-an-already-known-id-is-flagged
  (testing "an existing security group being edited (its id already known at plan time)"
    (let [conn (fresh-conn)
          plan (plan-doc
                [(planned-resource "aws_security_group" "ssh_open" {"id" "sg-123"})
                 (planned-resource "aws_security_group_rule" "ssh_open_ingress"
                                   {"from_port" 22 "to_port" 22 "protocol" "tcp"
                                    "security_group_id" "sg-123" "cidr_blocks" ["0.0.0.0/0"]})]
                [(config-resource "aws_security_group" "ssh_open")
                 (config-resource "aws_security_group_rule" "ssh_open_ingress"
                                   (security-group-id-ref "aws_security_group.ssh_open"))])]
      (is (= [{:rule :security-groups-with-port-22-open
               :resource/id "aws_security_group.ssh_open"
               :resource/type "aws_security_group"}]
             (policy/evaluate conn plan))))))

(deftest plan-violating-port-22-rule-via-address-stand-ins-is-flagged
  (testing "a brand-new security group and rule, both ids null at plan time"
    (let [conn (fresh-conn)
          plan (plan-doc
                [(planned-resource "aws_security_group" "ssh_open" {"id" nil})
                 (planned-resource "aws_security_group_rule" "ssh_open_ingress"
                                   {"from_port" 22 "to_port" 22 "protocol" "tcp"
                                    "security_group_id" nil "cidr_blocks" ["0.0.0.0/0"]})]
                [(config-resource "aws_security_group" "ssh_open")
                 (config-resource "aws_security_group_rule" "ssh_open_ingress"
                                   (security-group-id-ref "aws_security_group.ssh_open"))])]
      (is (= [{:rule :security-groups-with-port-22-open
               :resource/id "aws_security_group.ssh_open"
               :resource/type "aws_security_group"}]
             (policy/evaluate conn plan))))))

(deftest policy-check-responds-200-with-empty-violations-for-a-clean-plan
  (let [conn (fresh-conn)
        plan (plan-doc
              [(planned-resource "aws_security_group" "https_only" {"id" nil})]
              [(config-resource "aws_security_group" "https_only")])
        resp (policy/policy-check conn (json/generate-string plan))]
    (is (= 200 (:status resp)))
    (is (= [] (get (json/parse-string (:body resp)) "violations")))))

(deftest policy-check-response-identifies-the-rule-and-resource-for-a-violation
  (let [conn (fresh-conn)
        plan (plan-doc
              [(planned-resource "aws_security_group" "ssh_open" {"id" nil})
               (planned-resource "aws_security_group_rule" "ssh_open_ingress"
                                 {"from_port" 22 "to_port" 22 "protocol" "tcp"
                                  "security_group_id" nil "cidr_blocks" ["0.0.0.0/0"]})]
              [(config-resource "aws_security_group" "ssh_open")
               (config-resource "aws_security_group_rule" "ssh_open_ingress"
                                 (security-group-id-ref "aws_security_group.ssh_open"))])
        resp (policy/policy-check conn (json/generate-string plan))]
    (is (= 200 (:status resp)))
    (is (= [{"rule"     "security-groups-with-port-22-open"
             "resource" {"id" "aws_security_group.ssh_open" "type" "aws_security_group"}}]
           (get (json/parse-string (:body resp)) "violations")))))

(deftest policy-check-invalid-json-returns-400
  (let [conn (fresh-conn)]
    (is (= 400 (:status (policy/policy-check conn "not json"))))))

(deftest plan-with-null-to-port-does-not-crash
  (testing "to_port null at plan time (e.g. computed from a not-yet-known value) decomposes normally
  instead of getting an Address Stand-in substituted into its :db.type/long attribute - no datom for
  it, so the rule's join has nothing to match and reports no violation, degrading to today's behavior
  rather than throwing"
    (let [conn (fresh-conn)
          plan (plan-doc
                [(planned-resource "aws_security_group" "ssh_open" {"id" nil})
                 (planned-resource "aws_security_group_rule" "ssh_open_ingress"
                                   {"from_port" 22 "to_port" nil "protocol" "tcp"
                                    "security_group_id" nil "cidr_blocks" ["0.0.0.0/0"]})]
                [(config-resource "aws_security_group" "ssh_open")
                 (config-resource "aws_security_group_rule" "ssh_open_ingress"
                                   (security-group-id-ref "aws_security_group.ssh_open"))])]
      (is (empty? (policy/evaluate conn plan))))))

(deftest plan-with-null-cidr-blocks-does-not-crash
  (testing "cidr_blocks null at plan time decomposes normally instead of getting an Address Stand-in
  substituted into its cardinality-many :db.type/string attribute (which would otherwise be iterated
  character-by-character and rejected by Datomic) - no datom for it, so no violation is reported"
    (let [conn (fresh-conn)
          plan (plan-doc
                [(planned-resource "aws_security_group" "ssh_open" {"id" nil})
                 (planned-resource "aws_security_group_rule" "ssh_open_ingress"
                                   {"from_port" 22 "to_port" 22 "protocol" "tcp"
                                    "security_group_id" nil "cidr_blocks" nil})]
                [(config-resource "aws_security_group" "ssh_open")
                 (config-resource "aws_security_group_rule" "ssh_open_ingress"
                                   (security-group-id-ref "aws_security_group.ssh_open"))])]
      (is (empty? (policy/evaluate conn plan))))))

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

(deftest policy-check-does-not-affect-real-state
  (testing "a Policy Check call leaves GET /state's result unchanged, whether or not it reports violations"
    (let [conn (fresh-conn)]
      (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads")]))
      (let [before (handler/get-state conn)
            plan   (plan-doc
                    [(planned-resource "aws_security_group" "ssh_open" {"id" nil})
                     (planned-resource "aws_security_group_rule" "ssh_open_ingress"
                                       {"from_port" 22 "to_port" 22 "protocol" "tcp"
                                        "security_group_id" nil "cidr_blocks" ["0.0.0.0/0"]})]
                    [(config-resource "aws_security_group" "ssh_open")
                     (config-resource "aws_security_group_rule" "ssh_open_ingress"
                                       (security-group-id-ref "aws_security_group.ssh_open"))])]
        (policy/policy-check conn (json/generate-string plan))
        (is (= before (handler/get-state conn)))))))

(deftest policy-check-response-is-unaffected-by-existing-drift
  (testing "an existing drifted Terraform-managed resource never appears as a Policy Check Violation (issue #27 - the drift Rule is deliberately not registered in policy.clj's rules)"
    (let [conn (fresh-conn)]
      (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1" "vpc_id" "vpc-a"})]))
      (let [db (d/db conn)
            {:keys [tx-data outcome]} (sync/resource-tx db "aws_security_group" "sg-1" {"id" "sg-1" "vpc_id" "vpc-changed"})]
        (is (= :drifted outcome))
        (d/transact conn {:tx-data tx-data}))
      (let [plan (plan-doc
                  [(planned-resource "aws_security_group" "https_only" {"id" nil})]
                  [(config-resource "aws_security_group" "https_only")])]
        (is (empty? (policy/evaluate conn plan)))))))
