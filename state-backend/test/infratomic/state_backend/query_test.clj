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
