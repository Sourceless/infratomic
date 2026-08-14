(ns infratomic.state-backend.query-test
  "Unit tests for the query namespace's functions, exercised against an
  in-memory (non-persistent) Datomic dev-local database seeded via the
  handler's `POST` path - mirroring `handler_test.clj`'s fixture pattern -
  so query results are checked against realistically-decomposed data."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.query :as query]
            [infratomic.state-backend.sync :as sync]))

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

;; ---------------------------------------------------------------------------
;; drifted-resources - the drift Rule (issue #27)
;; ---------------------------------------------------------------------------

(deftest drifted-resources-a-terraform-only-resource-is-never-flagged
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1" "vpc_id" "vpc-a"})]))
    (is (empty? (query/drifted-resources (d/db conn))))))

(deftest drifted-resources-a-discovered-only-resource-is-never-flagged
  (let [conn (fresh-conn)
        db0  (d/db conn)
        {:keys [tx-data]} (sync/resource-tx db0 "aws_security_group" "sg-1" {"id" "sg-1" "vpc_id" "vpc-a"})]
    (d/transact conn {:tx-data tx-data})
    (is (empty? (query/drifted-resources (d/db conn))))))

(deftest drifted-resources-a-sync-update-matching-terraforms-value-is-not-flagged
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1" "vpc_id" "vpc-a"})]))
    ;; Sync observes the exact same value already stored - no drift, no
    ;; write at all.
    (let [db (d/db conn)
          {:keys [tx-data outcome]} (sync/resource-tx db "aws_security_group" "sg-1" {"id" "sg-1" "vpc_id" "vpc-a"})]
      (is (= :skipped-already-managed outcome))
      (is (empty? tx-data)))
    (is (empty? (query/drifted-resources (d/db conn))))))

(deftest drifted-resources-a-sync-update-with-a-different-value-is-flagged
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1" "vpc_id" "vpc-a"})]))
    (let [db (d/db conn)
          {:keys [tx-data outcome]} (sync/resource-tx db "aws_security_group" "sg-1" {"id" "sg-1" "vpc_id" "vpc-changed"})]
      (is (= :drifted outcome))
      (d/transact conn {:tx-data tx-data}))
    (is (= #{"aws_security_group.ssh_open"} (ids (query/drifted-resources (d/db conn)))))))

;; A cardinality-many modeled attribute (`vpc_security_group_ids`) is stored
;; as independent datoms with no preserved ordinal, so `d/pull`'s order is
;; independent of the order a freshly Sync-observed value is built in (issue
;; #29 review finding). The Rule must not flag a same-set-different-order
;; value as drift, but must still flag a genuinely different set.

(deftest drifted-resources-cardinality-many-attribute-same-set-different-order-is-not-flagged
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_instance" "web"
                                                      {"id" "i-1" "subnet_id" "subnet-1"
                                                       "vpc_security_group_ids" ["sg-aaa" "sg-bbb"]})]))
    (let [db (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_instance" "i-1"
                             {"id" "i-1" "subnet_id" "subnet-1"
                              "vpc_security_group_ids" ["sg-bbb" "sg-aaa"]})]
      (is (= :skipped-already-managed outcome))
      (is (empty? tx-data)))
    (is (empty? (query/drifted-resources (d/db conn))))))

(deftest drifted-resources-cardinality-many-attribute-actual-change-is-flagged
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_instance" "web"
                                                      {"id" "i-1" "subnet_id" "subnet-1"
                                                       "vpc_security_group_ids" ["sg-aaa" "sg-bbb"]})]))
    (let [db (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_instance" "i-1"
                             {"id" "i-1" "subnet_id" "subnet-1"
                              "vpc_security_group_ids" ["sg-aaa" "sg-ccc"]})]
      (is (= :drifted outcome))
      (d/transact conn {:tx-data tx-data}))
    (is (= #{"aws_instance.web"} (ids (query/drifted-resources (d/db conn)))))))

;; A regression test (issue #32 task 8.5): an `aws_instance`'s
;; `vpc_security_group_ids` membership change remains covered by the
;; existing attribute-diff drift mechanism above, with no
;; new-child/removed-child mechanism involved - it produces a plain entry,
;; no `:new-children`/`:removed-children` keys at all.
(deftest drifted-resources-vpc-security-group-ids-change-has-no-new-or-removed-children
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_instance" "web"
                                                      {"id" "i-1" "subnet_id" "subnet-1"
                                                       "vpc_security_group_ids" ["sg-aaa" "sg-bbb"]})]))
    (let [db (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_instance" "i-1"
                             {"id" "i-1" "subnet_id" "subnet-1"
                              "vpc_security_group_ids" ["sg-aaa" "sg-ccc"]})]
      (is (= :drifted outcome))
      (d/transact conn {:tx-data tx-data}))
    (let [[result] (query/drifted-resources (d/db conn))]
      (is (= "aws_instance.web" (:resource/id result)))
      (is (nil? (:new-children result)))
      (is (nil? (:removed-children result))))))

;; ---------------------------------------------------------------------------
;; new-children-by-parent / removed-children-by-parent - the new-child and
;; removed-child drift Rules (issue #32), generalized across every
;; FK-bearing child type via `child-parent-joins`.
;; ---------------------------------------------------------------------------

(defn- eid-by-ident
  [db ident value]
  (ffirst (d/q '[:find ?e :in $ ?ident ?v :where [?e ?ident ?v]] db ident value)))

(defn- child-ids
  [children]
  (into #{} (map :resource/id) children))

(deftest new-children-by-parent-flags-a-hand-added-security-group-rule
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1"})]))
    (let [db0 (d/db conn)
          {:keys [tx-data]} (sync/resource-tx db0 "aws_security_group_rule" "sgr-1"
                                               {"id" "sgr-1" "security_group_id" "sg-1"
                                                "from_port" 22 "to_port" 22})]
      (d/transact conn {:tx-data tx-data}))
    (let [db     (d/db conn)
          parent (eid-by-ident db :aws-security-group/id "sg-1")]
      (is (= #{"aws_security_group_rule.discovered-sgr-1"}
             (child-ids (get (query/new-children-by-parent db) parent)))))))

(deftest new-children-by-parent-does-not-flag-a-terraform-managed-child
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1"})
                                           (resource "aws_security_group_rule" "ssh_open_ingress"
                                                     {"security_group_id" "sg-1" "from_port" 22 "to_port" 22})]))
    (is (empty? (query/new-children-by-parent (d/db conn))))))

(deftest new-children-by-parent-flags-a-hand-added-route
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_route_table" "rt_a" {"id" "rt-1"})]))
    (let [db0 (d/db conn)
          {:keys [tx-data]} (sync/resource-tx db0 "aws_route" "rt-1-10.0.0.0/16"
                                               {"id" "rt-1-10.0.0.0/16" "route_table_id" "rt-1"
                                                "destination_cidr_block" "10.0.0.0/16"})]
      (d/transact conn {:tx-data tx-data}))
    (let [db     (d/db conn)
          parent (eid-by-ident db :aws-route-table/id "rt-1")]
      (is (= #{"aws_route.discovered-rt-1-10.0.0.0/16"}
             (child-ids (get (query/new-children-by-parent db) parent)))))))

(deftest new-children-by-parent-flags-a-hand-added-association-under-both-parents
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_route_table" "rt_a" {"id" "rt-1"})
                                           (resource "aws_subnet" "subnet_a" {"id" "subnet-1" "vpc_id" "vpc-1"})]))
    (let [db0 (d/db conn)
          {:keys [tx-data]} (sync/resource-tx db0 "aws_route_table_association" "rtbassoc-1"
                                               {"id" "rtbassoc-1" "route_table_id" "rt-1" "subnet_id" "subnet-1"})]
      (d/transact conn {:tx-data tx-data}))
    (let [db     (d/db conn)
          rt-eid (eid-by-ident db :aws-route-table/id "rt-1")
          sn-eid (eid-by-ident db :aws-subnet/id "subnet-1")
          result (query/new-children-by-parent db)]
      (is (= #{"aws_route_table_association.discovered-rtbassoc-1"} (child-ids (get result rt-eid))))
      (is (= #{"aws_route_table_association.discovered-rtbassoc-1"} (child-ids (get result sn-eid)))))))

(deftest new-children-by-parent-flags-a-hand-attached-iam-policy
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_iam_role" "lambda_exec" {"name" "lambda-exec"})]))
    (let [db0 (d/db conn)
          {:keys [tx-data]}
          (sync/resource-tx db0 "aws_iam_role_policy_attachment" nil
                             {"role" "lambda-exec" "policy_arn" "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess"})]
      (d/transact conn {:tx-data tx-data}))
    (let [db     (d/db conn)
          parent (eid-by-ident db :aws-iam-role/name "lambda-exec")]
      (is (= #{(str "aws_iam_role_policy_attachment.discovered-lambda-exec-"
                    "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess")}
             (child-ids (get (query/new-children-by-parent db) parent)))))))

(deftest removed-children-by-parent-flags-a-missing-managed-security-group-rule
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1"})
                                           (resource "aws_security_group_rule" "ssh_open_ingress"
                                                     {"security_group_id" "sg-1" "from_port" 22 "to_port" 22})]))
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_security_group_rule.ssh_open_ingress"]
                                  :resource/sync-present? false]]})
    (let [db     (d/db conn)
          parent (eid-by-ident db :aws-security-group/id "sg-1")]
      (is (= #{"aws_security_group_rule.ssh_open_ingress"}
             (child-ids (get (query/removed-children-by-parent db) parent)))))))

(deftest removed-children-by-parent-does-not-flag-a-present-or-not-yet-checked-child
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1"})
                                           (resource "aws_security_group_rule" "ssh_open_ingress"
                                                     {"security_group_id" "sg-1" "from_port" 22 "to_port" 22})]))
    (is (empty? (query/removed-children-by-parent (d/db conn))))
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_security_group_rule.ssh_open_ingress"]
                                  :resource/sync-present? true]]})
    (is (empty? (query/removed-children-by-parent (d/db conn))))))

(deftest removed-children-by-parent-no-longer-flags-a-reappeared-child
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1"})
                                           (resource "aws_security_group_rule" "ssh_open_ingress"
                                                     {"security_group_id" "sg-1" "from_port" 22 "to_port" 22})]))
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_security_group_rule.ssh_open_ingress"]
                                  :resource/sync-present? false]]})
    (is (seq (query/removed-children-by-parent (d/db conn))))
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_security_group_rule.ssh_open_ingress"]
                                  :resource/sync-present? true]]})
    (is (empty? (query/removed-children-by-parent (d/db conn))))))

(deftest removed-children-by-parent-flags-a-missing-managed-route
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_route_table" "rt_a" {"id" "rt-1"})
                                           (resource "aws_route" "rt_a_igw"
                                                     {"id" "rt-1-0.0.0.0/0" "route_table_id" "rt-1"
                                                      "destination_cidr_block" "0.0.0.0/0"})]))
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_route.rt_a_igw"] :resource/sync-present? false]]})
    (let [db     (d/db conn)
          parent (eid-by-ident db :aws-route-table/id "rt-1")]
      (is (= #{"aws_route.rt_a_igw"} (child-ids (get (query/removed-children-by-parent db) parent)))))))

(deftest removed-children-by-parent-flags-a-missing-managed-association-under-both-parents
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_route_table" "rt_a" {"id" "rt-1"})
                                           (resource "aws_subnet" "subnet_a" {"id" "subnet-1" "vpc_id" "vpc-1"})
                                           (resource "aws_route_table_association" "assoc_a"
                                                     {"id" "rtbassoc-1" "route_table_id" "rt-1" "subnet_id" "subnet-1"})]))
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_route_table_association.assoc_a"]
                                  :resource/sync-present? false]]})
    (let [db      (d/db conn)
          rt-eid  (eid-by-ident db :aws-route-table/id "rt-1")
          sn-eid  (eid-by-ident db :aws-subnet/id "subnet-1")
          result  (query/removed-children-by-parent db)]
      (is (= #{"aws_route_table_association.assoc_a"} (child-ids (get result rt-eid))))
      (is (= #{"aws_route_table_association.assoc_a"} (child-ids (get result sn-eid)))))))

(deftest removed-children-by-parent-flags-a-missing-managed-iam-role-policy-attachment
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_iam_role" "lambda_exec" {"name" "lambda-exec"})
                                           (resource "aws_iam_role_policy_attachment" "lambda_s3"
                                                     {"role" "lambda-exec"
                                                      "policy_arn" "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess"})]))
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_iam_role_policy_attachment.lambda_s3"]
                                  :resource/sync-present? false]]})
    (let [db     (d/db conn)
          parent (eid-by-ident db :aws-iam-role/name "lambda-exec")]
      (is (= #{"aws_iam_role_policy_attachment.lambda_s3"}
             (child-ids (get (query/removed-children-by-parent db) parent)))))))

;; ---------------------------------------------------------------------------
;; GET /drift response shape (issue #32): new_children/removed_children are
;; present only when a parent actually has one or the other.
;; ---------------------------------------------------------------------------

(deftest drift-endpoint-includes-new-children-only-when-present
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1"})]))
    (let [db0 (d/db conn)
          {:keys [tx-data]} (sync/resource-tx db0 "aws_security_group_rule" "sgr-1"
                                               {"id" "sgr-1" "security_group_id" "sg-1"
                                                "from_port" 22 "to_port" 22})]
      (d/transact conn {:tx-data tx-data}))
    (let [response (query/drift-endpoint conn)
          body     (json/parse-string (:body response))
          drifted  (get body "drifted")]
      (is (= 200 (:status response)))
      (is (= 1 (count drifted)))
      (let [entry (first drifted)]
        (is (= "aws_security_group.ssh_open" (get entry "id")))
        (is (= [{"type" "aws_security_group_rule" "id" "aws_security_group_rule.discovered-sgr-1"}]
               (get entry "new_children")))
        (is (not (contains? entry "removed_children")))))))

(deftest drift-endpoint-includes-removed-children-only-when-present
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1"})
                                           (resource "aws_security_group_rule" "ssh_open_ingress"
                                                     {"security_group_id" "sg-1" "from_port" 22 "to_port" 22})]))
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_security_group_rule.ssh_open_ingress"]
                                  :resource/sync-present? false]]})
    (let [body    (json/parse-string (:body (query/drift-endpoint conn)))
          entry   (first (get body "drifted"))]
      (is (= "aws_security_group.ssh_open" (get entry "id")))
      (is (= [{"type" "aws_security_group_rule" "id" "aws_security_group_rule.ssh_open_ingress"}]
             (get entry "removed_children")))
      (is (not (contains? entry "new_children"))))))

(deftest drift-endpoint-attribute-drift-only-entry-keeps-the-existing-flat-shape
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1" "vpc_id" "vpc-a"})]))
    (let [db (d/db conn)
          {:keys [tx-data]} (sync/resource-tx db "aws_security_group" "sg-1" {"id" "sg-1" "vpc_id" "vpc-changed"})]
      (d/transact conn {:tx-data tx-data}))
    (let [body  (json/parse-string (:body (query/drift-endpoint conn)))
          entry (first (get body "drifted"))]
      (is (= {"type" "aws_security_group" "id" "aws_security_group.ssh_open"} entry)))))

(deftest drift-endpoint-with-no-drift-returns-an-empty-list
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1"})]))
    (is (= [] (get (json/parse-string (:body (query/drift-endpoint conn))) "drifted")))))

;; ---------------------------------------------------------------------------
;; reachable? - network reachability via graph traversal
;; ---------------------------------------------------------------------------

;; A small multi-VPC network graph, purpose-built to exercise every
;; `reachable?` path and its paired negative case:
;;
;; vpc-a (10.0.0.0/16)                    vpc-b (10.1.0.0/16)     vpc-c (10.2.0.0/16)
;;   subnet-a1 (rt-a: IGW + peering)         subnet-b1 (rt-b)       subnet-c1 (rt-c)
;;     instance-1, instance-2 (sg-open)        instance-6 (sg-open)   instance-8 (sg-open)
;;     instance-3, instance-4 (sg-restricted)
;;   subnet-a2 (rt-a: IGW + peering)
;;     instance-5 (sg-open)
;;   subnet-a3 (rt-a-isolated: no IGW, no peering route)
;;     instance-7 (sg-open), instance-9 (sg-open)
;;
;; pcx-ab peers vpc-a <-> vpc-b. vpc-c has no peering connection at all.
;; igw-a is vpc-a's internet gateway, routed only from rt-a (not
;; rt-a-isolated).
(def ^:private network-resources
  [(resource "aws_vpc" "vpc_a" {"id" "vpc-a" "cidr_block" "10.0.0.0/16"})
   (resource "aws_vpc" "vpc_b" {"id" "vpc-b" "cidr_block" "10.1.0.0/16"})
   (resource "aws_vpc" "vpc_c" {"id" "vpc-c" "cidr_block" "10.2.0.0/16"})

   (resource "aws_subnet" "subnet_a1" {"id" "subnet-a1" "vpc_id" "vpc-a" "cidr_block" "10.0.1.0/24"})
   (resource "aws_subnet" "subnet_a2" {"id" "subnet-a2" "vpc_id" "vpc-a" "cidr_block" "10.0.2.0/24"})
   (resource "aws_subnet" "subnet_a3" {"id" "subnet-a3" "vpc_id" "vpc-a" "cidr_block" "10.0.3.0/24"})
   (resource "aws_subnet" "subnet_b1" {"id" "subnet-b1" "vpc_id" "vpc-b" "cidr_block" "10.1.1.0/24"})
   (resource "aws_subnet" "subnet_c1" {"id" "subnet-c1" "vpc_id" "vpc-c" "cidr_block" "10.2.1.0/24"})

   (resource "aws_route_table" "rt_a" {"id" "rt-a" "vpc_id" "vpc-a"})
   (resource "aws_route_table" "rt_a_isolated" {"id" "rt-a-isolated" "vpc_id" "vpc-a"})
   (resource "aws_route_table" "rt_b" {"id" "rt-b" "vpc_id" "vpc-b"})
   (resource "aws_route_table" "rt_c" {"id" "rt-c" "vpc_id" "vpc-c"})

   (resource "aws_internet_gateway" "igw" {"id" "igw-a" "vpc_id" "vpc-a"})
   (resource "aws_vpc_peering_connection" "pcx_ab" {"id" "pcx-ab" "vpc_id" "vpc-a" "peer_vpc_id" "vpc-b"})

   (resource "aws_route" "rt_a_igw" {"id" "route-a-igw" "route_table_id" "rt-a"
                                      "destination_cidr_block" "0.0.0.0/0" "gateway_id" "igw-a"})
   (resource "aws_route" "rt_a_pcx" {"id" "route-a-pcx" "route_table_id" "rt-a"
                                      "destination_cidr_block" "10.1.0.0/16" "vpc_peering_connection_id" "pcx-ab"})
   (resource "aws_route" "rt_b_pcx" {"id" "route-b-pcx" "route_table_id" "rt-b"
                                      "destination_cidr_block" "10.0.0.0/16" "vpc_peering_connection_id" "pcx-ab"})

   (resource "aws_route_table_association" "assoc_a1" {"id" "assoc-a1" "subnet_id" "subnet-a1" "route_table_id" "rt-a"})
   (resource "aws_route_table_association" "assoc_a2" {"id" "assoc-a2" "subnet_id" "subnet-a2" "route_table_id" "rt-a"})
   (resource "aws_route_table_association" "assoc_a3" {"id" "assoc-a3" "subnet_id" "subnet-a3" "route_table_id" "rt-a-isolated"})
   (resource "aws_route_table_association" "assoc_b1" {"id" "assoc-b1" "subnet_id" "subnet-b1" "route_table_id" "rt-b"})
   (resource "aws_route_table_association" "assoc_c1" {"id" "assoc-c1" "subnet_id" "subnet-c1" "route_table_id" "rt-c"})

   (resource "aws_security_group" "sg_open" {"id" "sg-open" "vpc_id" "vpc-a"})
   (resource "aws_security_group_rule" "sg_open_egress"
             {"type" "egress" "from_port" 0 "to_port" 0 "protocol" "-1"
              "security_group_id" "sg-open" "cidr_blocks" ["0.0.0.0/0"]})
   (resource "aws_security_group_rule" "sg_open_ingress"
             {"type" "ingress" "from_port" 0 "to_port" 0 "protocol" "-1"
              "security_group_id" "sg-open" "cidr_blocks" ["0.0.0.0/0"]})

   (resource "aws_security_group" "sg_restricted" {"id" "sg-restricted" "vpc_id" "vpc-a"})
   (resource "aws_security_group_rule" "sg_restricted_egress"
             {"type" "egress" "from_port" 0 "to_port" 0 "protocol" "-1"
              "security_group_id" "sg-restricted" "cidr_blocks" ["192.168.99.0/24"]})

   (resource "aws_instance" "instance_1" {"id" "instance-1" "subnet_id" "subnet-a1" "vpc_security_group_ids" ["sg-open"]})
   (resource "aws_instance" "instance_2" {"id" "instance-2" "subnet_id" "subnet-a1" "vpc_security_group_ids" ["sg-open"]})
   (resource "aws_instance" "instance_3" {"id" "instance-3" "subnet_id" "subnet-a1" "vpc_security_group_ids" ["sg-restricted"]})
   (resource "aws_instance" "instance_4" {"id" "instance-4" "subnet_id" "subnet-a1" "vpc_security_group_ids" ["sg-restricted"]})
   (resource "aws_instance" "instance_5" {"id" "instance-5" "subnet_id" "subnet-a2" "vpc_security_group_ids" ["sg-open"]})
   (resource "aws_instance" "instance_6" {"id" "instance-6" "subnet_id" "subnet-b1" "vpc_security_group_ids" ["sg-open"]})
   (resource "aws_instance" "instance_7" {"id" "instance-7" "subnet_id" "subnet-a1" "vpc_security_group_ids" ["sg-open"]})
   (resource "aws_instance" "instance_8" {"id" "instance-8" "subnet_id" "subnet-c1" "vpc_security_group_ids" ["sg-open"]})
   (resource "aws_instance" "instance_9" {"id" "instance-9" "subnet_id" "subnet-a3" "vpc_security_group_ids" ["sg-open"]})])

(defn- network-db
  []
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body network-resources))
    (d/db conn)))

(deftest reachable?-same-subnet
  (let [db (network-db)]
    (testing "positive: same subnet, SG permits"
      (is (query/reachable? db "instance-1" "instance-2")))
    (testing "negative: same subnet, SG blocks"
      (is (not (query/reachable? db "instance-3" "instance-4"))))))

(deftest reachable?-cross-vpc-via-peering
  (let [db (network-db)]
    (is (query/reachable? db "instance-5" "instance-6"))))

(deftest reachable?-cross-vpc-negative-no-peering-connection
  (let [db (network-db)]
    (is (not (query/reachable? db "instance-7" "instance-8")))))

(deftest reachable?-cross-vpc-negative-missing-route-despite-peering
  (let [db (network-db)]
    (is (not (query/reachable? db "instance-9" "instance-6")))))

(deftest reachable?-internet-bound
  (let [db (network-db)]
    (testing "positive: route to IGW, egress permits"
      (is (query/reachable? db "instance-1" "0.0.0.0/0")))
    (testing "negative: no route to IGW"
      (is (not (query/reachable? db "instance-9" "0.0.0.0/0"))))))

(deftest reachable?-self
  (let [db (network-db)]
    (is (query/reachable? db "instance-3" "instance-3"))))

;; ---------------------------------------------------------------------------
;; reachable-within-hops? - multi-hop VPC peering chain reachability
;; ---------------------------------------------------------------------------

;; A 4-VPC A-B-C-D peering chain, independent from `network-resources` (so
;; `vpc-c`'s role there as `reachable?`'s own deliberately-unpeered
;; negative case is untouched). No direct shortcuts exist - only
;; A<->B, B<->C, C<->D - so reaching A from D (or vice versa) requires
;; walking the full 3-hop chain. Each VPC has one subnet, one `vpc_id`-owned
;; route table (chain hops are joined at VPC granularity, not anchored to
;; any particular subnet - see design.md), and one workload instance using
;; a fresh permissive security group (`sg-chain-open`, not `network-resources`'s
;; `sg-open`), so these tests exercise chain traversal, not SG logic.
;; `vpc-chain-d` also has an internet gateway + default route, for the
;; CIDR-target scenario.
;;
;; vpc-chain-a <-> vpc-chain-b <-> vpc-chain-c <-> vpc-chain-d (igw-chain)
;;  instance-chain-a  instance-chain-b  instance-chain-c  instance-chain-d
(defn- chain-network-resources
  "The resources for the 4-VPC A-B-C-D peering chain fixture. When
  `include-b-c-route?` is false, the B-side route naming the B<->C peering
  connection is omitted, breaking the chain partway through without
  mutating the full fixture (a separate fixture-building call, not a
  mutation)."
  [include-b-c-route?]
  (cond-> [(resource "aws_vpc" "vpc_chain_a" {"id" "vpc-chain-a" "cidr_block" "10.20.0.0/16"})
           (resource "aws_vpc" "vpc_chain_b" {"id" "vpc-chain-b" "cidr_block" "10.21.0.0/16"})
           (resource "aws_vpc" "vpc_chain_c" {"id" "vpc-chain-c" "cidr_block" "10.22.0.0/16"})
           (resource "aws_vpc" "vpc_chain_d" {"id" "vpc-chain-d" "cidr_block" "10.23.0.0/16"})

           (resource "aws_subnet" "subnet_chain_a" {"id" "subnet-chain-a" "vpc_id" "vpc-chain-a" "cidr_block" "10.20.1.0/24"})
           (resource "aws_subnet" "subnet_chain_b" {"id" "subnet-chain-b" "vpc_id" "vpc-chain-b" "cidr_block" "10.21.1.0/24"})
           (resource "aws_subnet" "subnet_chain_c" {"id" "subnet-chain-c" "vpc_id" "vpc-chain-c" "cidr_block" "10.22.1.0/24"})
           (resource "aws_subnet" "subnet_chain_d" {"id" "subnet-chain-d" "vpc_id" "vpc-chain-d" "cidr_block" "10.23.1.0/24"})

           (resource "aws_route_table" "rt_chain_a" {"id" "rt-chain-a" "vpc_id" "vpc-chain-a"})
           (resource "aws_route_table" "rt_chain_b" {"id" "rt-chain-b" "vpc_id" "vpc-chain-b"})
           (resource "aws_route_table" "rt_chain_c" {"id" "rt-chain-c" "vpc_id" "vpc-chain-c"})
           (resource "aws_route_table" "rt_chain_d" {"id" "rt-chain-d" "vpc_id" "vpc-chain-d"})

           (resource "aws_internet_gateway" "igw_chain" {"id" "igw-chain" "vpc_id" "vpc-chain-d"})

           (resource "aws_vpc_peering_connection" "pcx_chain_ab" {"id" "pcx-chain-ab" "vpc_id" "vpc-chain-a" "peer_vpc_id" "vpc-chain-b"})
           (resource "aws_vpc_peering_connection" "pcx_chain_bc" {"id" "pcx-chain-bc" "vpc_id" "vpc-chain-b" "peer_vpc_id" "vpc-chain-c"})
           (resource "aws_vpc_peering_connection" "pcx_chain_cd" {"id" "pcx-chain-cd" "vpc_id" "vpc-chain-c" "peer_vpc_id" "vpc-chain-d"})

           (resource "aws_route" "rt_chain_a_pcx_ab" {"id" "route-chain-a-pcx-ab" "route_table_id" "rt-chain-a"
                                                        "destination_cidr_block" "10.21.0.0/16" "vpc_peering_connection_id" "pcx-chain-ab"})
           (resource "aws_route" "rt_chain_b_pcx_ab" {"id" "route-chain-b-pcx-ab" "route_table_id" "rt-chain-b"
                                                        "destination_cidr_block" "10.20.0.0/16" "vpc_peering_connection_id" "pcx-chain-ab"})
           (resource "aws_route" "rt_chain_c_pcx_bc" {"id" "route-chain-c-pcx-bc" "route_table_id" "rt-chain-c"
                                                        "destination_cidr_block" "10.21.0.0/16" "vpc_peering_connection_id" "pcx-chain-bc"})
           (resource "aws_route" "rt_chain_c_pcx_cd" {"id" "route-chain-c-pcx-cd" "route_table_id" "rt-chain-c"
                                                        "destination_cidr_block" "10.23.0.0/16" "vpc_peering_connection_id" "pcx-chain-cd"})
           (resource "aws_route" "rt_chain_d_pcx_cd" {"id" "route-chain-d-pcx-cd" "route_table_id" "rt-chain-d"
                                                        "destination_cidr_block" "10.22.0.0/16" "vpc_peering_connection_id" "pcx-chain-cd"})
           (resource "aws_route" "rt_chain_d_igw" {"id" "route-chain-d-igw" "route_table_id" "rt-chain-d"
                                                     "destination_cidr_block" "0.0.0.0/0" "gateway_id" "igw-chain"})

           (resource "aws_security_group" "sg_chain_open" {"id" "sg-chain-open" "vpc_id" "vpc-chain-a"})
           (resource "aws_security_group_rule" "sg_chain_open_egress"
                     {"type" "egress" "from_port" 0 "to_port" 0 "protocol" "-1"
                      "security_group_id" "sg-chain-open" "cidr_blocks" ["0.0.0.0/0"]})
           (resource "aws_security_group_rule" "sg_chain_open_ingress"
                     {"type" "ingress" "from_port" 0 "to_port" 0 "protocol" "-1"
                      "security_group_id" "sg-chain-open" "cidr_blocks" ["0.0.0.0/0"]})

           (resource "aws_instance" "instance_chain_a" {"id" "instance-chain-a" "subnet_id" "subnet-chain-a" "vpc_security_group_ids" ["sg-chain-open"]})
           (resource "aws_instance" "instance_chain_b" {"id" "instance-chain-b" "subnet_id" "subnet-chain-b" "vpc_security_group_ids" ["sg-chain-open"]})
           (resource "aws_instance" "instance_chain_c" {"id" "instance-chain-c" "subnet_id" "subnet-chain-c" "vpc_security_group_ids" ["sg-chain-open"]})
           (resource "aws_instance" "instance_chain_d" {"id" "instance-chain-d" "subnet_id" "subnet-chain-d" "vpc_security_group_ids" ["sg-chain-open"]})]
    include-b-c-route?
    (conj (resource "aws_route" "rt_chain_b_pcx_bc" {"id" "route-chain-b-pcx-bc" "route_table_id" "rt-chain-b"
                                                       "destination_cidr_block" "10.22.0.0/16" "vpc_peering_connection_id" "pcx-chain-bc"}))))

(defn- chain-db
  []
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body (chain-network-resources true)))
    (d/db conn)))

(defn- broken-chain-db
  "The A-B-C-D chain fixture with the B-side route naming the B<->C
  peering connection omitted, breaking the chain partway through - a
  separate fixture-building call, not a mutation of `chain-db`."
  []
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body (chain-network-resources false)))
    (d/db conn)))

(deftest reachable-within-hops?-full-chain
  (let [db (chain-db)]
    (is (query/reachable-within-hops? db "instance-chain-a" "instance-chain-d" 3))))

(deftest reachable-within-hops?-broken-link
  (let [db (broken-chain-db)]
    (is (not (query/reachable-within-hops? db "instance-chain-a" "instance-chain-d" 5)))))

(deftest reachable-within-hops?-hop-limit-too-low
  (let [db (chain-db)]
    (is (not (query/reachable-within-hops? db "instance-chain-a" "instance-chain-d" 2)))))

(deftest reachable-within-hops?-self
  (let [db (chain-db)]
    (is (query/reachable-within-hops? db "instance-chain-a" "instance-chain-a" 0))))
