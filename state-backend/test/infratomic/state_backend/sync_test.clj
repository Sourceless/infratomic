(ns infratomic.state-backend.sync-test
  "Hermetic unit tests for Sync's pure pieces: the AWS-response -> Terraform-
  attribute-map translation functions (against representative sample EC2 API
  response shapes captured from a real LocalStack instance - see each
  fixture's comment), and the matching/ingestion decision (`resource-tx`)
  against an in-memory Datomic dev-local database, mirroring
  `handler_test.clj`'s fixture pattern. No network/LocalStack access - the
  full `sync!` pass (real EC2 calls) is covered by `sync_integration_test.clj`
  instead."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
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

;; ---------------------------------------------------------------------------
;; Translation functions, against sample real EC2 API response shapes
;; (captured from `DescribeSecurityGroups`/`DescribeSecurityGroupRules`/etc.
;; invoked directly against a running LocalStack instance)
;; ---------------------------------------------------------------------------

(deftest security-group-translation
  (is (= {"id" "sg-b450dcc8007bd5693" "vpc_id" "vpc-d34b8e95"}
         (sync/security-group->attrs
          {:GroupId "sg-b450dcc8007bd5693"
           :VpcId "vpc-d34b8e95"
           :GroupName "infratomic-test-app-ssh-open"
           :Description "Insecure example: allows SSH from the whole internet"
           :IpPermissions []
           :IpPermissionsEgress []
           :Tags []
           :OwnerId "000000000000"}))))

(deftest security-group-rule-translation
  (testing "a CIDR-based ingress rule"
    (is (= {"id"                "sgr-b43bf3335765b7878"
            "from_port"         22
            "to_port"           22
            "protocol"          "tcp"
            "security_group_id" "sg-b450dcc8007bd5693"
            "type"              "ingress"
            "cidr_blocks"       ["0.0.0.0/0"]}
           (sync/security-group-rule->attrs
            {:SecurityGroupRuleId "sgr-b43bf3335765b7878"
             :GroupId "sg-b450dcc8007bd5693"
             :GroupOwnerId "000000000000"
             :IsEgress false
             :IpProtocol "tcp"
             :FromPort 22
             :ToPort 22
             :CidrIpv4 "0.0.0.0/0"
             :Tags []}))))

  (testing "a security-group-referencing egress rule"
    (is (= {"id"                        "sgr-8aa6d31e26f62099f"
            "from_port"                 9999
            "to_port"                   9999
            "protocol"                  "tcp"
            "security_group_id"         "sg-b450dcc8007bd5693"
            "type"                      "egress"
            "source_security_group_id"  "sg-ad032061586482f30"}
           (sync/security-group-rule->attrs
            {:SecurityGroupRuleId "sgr-8aa6d31e26f62099f"
             :GroupId "sg-b450dcc8007bd5693"
             :GroupOwnerId "000000000000"
             :IsEgress true
             :IpProtocol "tcp"
             :FromPort 9999
             :ToPort 9999
             :ReferencedGroupInfo {:GroupId "sg-ad032061586482f30" :UserId "000000000000"}})))))

(deftest vpc-translation
  (is (= {"id" "vpc-d34b8e95" "cidr_block" "10.0.0.0/16"}
         (sync/vpc->attrs
          {:VpcId "vpc-d34b8e95"
           :CidrBlock "10.0.0.0/16"
           :IsDefault false
           :State "available"
           :Tags [{:Key "Name" :Value "infratomic-test-app-vpc-a"}]}))))

(deftest subnet-translation
  (is (= {"id" "subnet-7c968f9d" "vpc_id" "vpc-f6913a3d" "cidr_block" "172.31.0.0/20"}
         (sync/subnet->attrs
          {:SubnetId "subnet-7c968f9d"
           :VpcId "vpc-f6913a3d"
           :CidrBlock "172.31.0.0/20"
           :AvailabilityZone "us-east-1a"
           :State "available"}))))

(deftest route-table-translation
  (is (= {"id" "rtb-b16c189a" "vpc_id" "vpc-d34b8e95"}
         (sync/route-table->attrs
          {:RouteTableId "rtb-b16c189a"
           :VpcId "vpc-d34b8e95"
           :Tags [{:Key "Name" :Value "infratomic-test-app-rt-a"}]}))))

(deftest route-translation
  (testing "a route to an internet gateway"
    (is (= {"id"                        "rtb-b16c189a-0.0.0.0/0"
            "route_table_id"            "rtb-b16c189a"
            "destination_cidr_block"    "0.0.0.0/0"
            "gateway_id"                "igw-142a066d"
            "vpc_peering_connection_id" nil}
           (sync/route->attrs "rtb-b16c189a"
                               {:DestinationCidrBlock "0.0.0.0/0"
                                :Origin "CreateRoute"
                                :State "active"
                                :GatewayId "igw-142a066d"}))))

  (testing "a route to a peering connection"
    (is (= {"id"                        "rtb-b16c189a-10.1.0.0/16"
            "route_table_id"            "rtb-b16c189a"
            "destination_cidr_block"    "10.1.0.0/16"
            "gateway_id"                nil
            "vpc_peering_connection_id" "pcx-efed2f55"}
           (sync/route->attrs "rtb-b16c189a"
                               {:DestinationCidrBlock "10.1.0.0/16"
                                :Origin "CreateRoute"
                                :State "active"
                                :VpcPeeringConnectionId "pcx-efed2f55"})))))

(deftest route-table-association-translation
  (is (= {"id" "rtbassoc-95faf747" "subnet_id" "subnet-7183d1b1" "route_table_id" "rtb-b16c189a"}
         (sync/route-table-association->attrs
          {:Main false
           :RouteTableAssociationId "rtbassoc-95faf747"
           :RouteTableId "rtb-b16c189a"
           :SubnetId "subnet-7183d1b1"
           :AssociationState {:State "associated"}}))))

(deftest internet-gateway-translation
  (is (= {"id" "igw-142a066d" "vpc_id" "vpc-d34b8e95"}
         (sync/internet-gateway->attrs
          {:InternetGatewayId "igw-142a066d"
           :Attachments [{:State "available" :VpcId "vpc-d34b8e95"}]
           :Tags [{:Key "Name" :Value "infratomic-test-app-igw-a"}]}))))

(deftest vpc-peering-connection-translation
  (is (= {"id" "pcx-efed2f55" "vpc_id" "vpc-d34b8e95" "peer_vpc_id" "vpc-e28a315b"}
         (sync/vpc-peering-connection->attrs
          {:VpcPeeringConnectionId "pcx-efed2f55"
           :RequesterVpcInfo {:VpcId "vpc-d34b8e95" :CidrBlock "10.0.0.0/16"}
           :AccepterVpcInfo {:VpcId "vpc-e28a315b" :CidrBlock "10.1.0.0/16"}
           :Status {:Code "active" :Message "Active"}
           :Tags [{:Key "Name" :Value "infratomic-test-app-pcx-ab"}]}))))

(deftest instance-translation
  (is (= {"id" "i-6073d2ce840e7afa4" "subnet_id" "subnet-b9c6499e"
          "vpc_security_group_ids" ["sg-6bd43627d40050a11" "sg-b450dcc8007bd5693"]}
         (sync/instance->attrs
          {:InstanceId "i-6073d2ce840e7afa4"
           :SubnetId "subnet-b9c6499e"
           :InstanceType "t3.micro"
           :State {:Code 16 :Name "running"}
           :SecurityGroups [{:GroupName "infratomic-test-app-reachability-open" :GroupId "sg-6bd43627d40050a11"}
                             {:GroupName "infratomic-test-app-ssh-open" :GroupId "sg-b450dcc8007bd5693"}]}))))

;; ---------------------------------------------------------------------------
;; Matching/ingestion decision (resource-tx) - issue #26's core "no
;; duplicates" behavior, plus issue #27's diff-gated update-on-drift for a
;; Terraform-managed match (superseding #26's "left untouched" behavior)
;; ---------------------------------------------------------------------------

(defn- eid-by-aws-id
  "`:aws-security-group/id` (like every modeled id attribute) isn't
  `:db.unique/*`, so it can't be looked up via a `[:ident value]` lookup
  ref - find its entity id via a direct query instead."
  [db ident aws-id]
  (ffirst (d/q '[:find ?e :in $ ?ident ?v :where [?e ?ident ?v]] db ident aws-id)))

(deftest resource-tx-with-no-existing-match-discovers-a-new-resource
  (let [conn (fresh-conn)
        db   (d/db conn)
        {:keys [tx-data outcome]} (sync/resource-tx db "aws_security_group" "sg-999" {"id" "sg-999" "vpc_id" "vpc-1"})]
    (is (= :discovered outcome))
    (d/transact conn {:tx-data tx-data})
    (let [db'    (d/db conn)
          eid    (eid-by-aws-id db' :aws-security-group/id "sg-999")
          pulled (d/pull db' [:resource/id :resource/managed? :resource/last-write-source] eid)]
      (is (= "aws_security_group.discovered-sg-999" (:resource/id pulled)))
      (is (= false (:resource/managed? pulled)))
      (is (= :sync (:resource/last-write-source pulled))))))

;; ---------------------------------------------------------------------------
;; resource-tx matching a Terraform-managed resource - diff-gated
;; update-on-drift (issue #27, superseding issue #26's "left untouched"
;; behavior)
;; ---------------------------------------------------------------------------

(defn- managed-conn-with
  "A fresh conn with one Terraform-managed `aws_security_group` entity
  already transacted, `:aws-security-group/id \"sg-managed\"`, tagged
  `:resource/last-write-source :terraform` as `resource->tx`/`POST /state`
  would."
  []
  (let [conn (fresh-conn)]
    (d/transact conn {:tx-data [{:resource/id                 "aws_security_group.ssh_open"
                                  :resource/type               "aws_security_group"
                                  :resource/managed?           true
                                  :resource/last-write-source  :terraform
                                  :aws-security-group/id       "sg-managed"}]})
    conn))

(deftest resource-tx-matching-a-terraform-managed-resource-with-no-drift-makes-no-write
  (testing "observed live value identical to what's stored: no tx-data, outcome :skipped-already-managed"
    (let [conn (managed-conn-with)
          db   (d/db conn)
          {:keys [tx-data outcome]} (sync/resource-tx db "aws_security_group" "sg-managed" {"id" "sg-managed"})]
      (is (= :skipped-already-managed outcome))
      (is (empty? tx-data)))))

(deftest resource-tx-matching-a-terraform-managed-resource-with-drift-updates-and-retags
  (testing "observed live value differs from what's stored: updates the resource, tags :sync, outcome :drifted"
    (let [conn (managed-conn-with)
          db   (d/db conn)
          {:keys [tx-data outcome]} (sync/resource-tx db "aws_security_group" "sg-managed" {"id" "sg-managed" "vpc_id" "vpc-changed"})]
      (is (= :drifted outcome))
      (is (seq tx-data))
      (d/transact conn {:tx-data tx-data})
      (let [db'    (d/db conn)
            eid    (eid-by-aws-id db' :aws-security-group/id "sg-managed")
            pulled (d/pull db' [:resource/id :resource/managed? :resource/last-write-source
                                 :aws-security-group/vpc-id] eid)]
        (is (= "aws_security_group.ssh_open" (:resource/id pulled)))
        (is (= true (:resource/managed? pulled)))
        (is (= :sync (:resource/last-write-source pulled)))
        (is (= "vpc-changed" (:aws-security-group/vpc-id pulled)))))))

(deftest resource-tx-matching-a-previously-discovered-resource-updates-in-place
  (let [conn (fresh-conn)
        db0  (d/db conn)
        {:keys [tx-data]} (sync/resource-tx db0 "aws_security_group" "sg-999" {"id" "sg-999" "vpc_id" "vpc-1"})]
    (d/transact conn {:tx-data tx-data})
    (let [db1 (d/db conn)
          {:keys [tx-data outcome]} (sync/resource-tx db1 "aws_security_group" "sg-999" {"id" "sg-999" "vpc_id" "vpc-2"})]
      (is (= :updated outcome))
      (d/transact conn {:tx-data tx-data})
      (let [db'  (d/db conn)
            eids (map first (d/q '[:find ?e :where [?e :aws-security-group/id "sg-999"]] db'))]
        (is (= 1 (count eids)))
        (let [pulled (d/pull db' [:aws-security-group/vpc-id :resource/last-write-source] (first eids))]
          (is (= "vpc-2" (:aws-security-group/vpc-id pulled)))
          (is (= :sync (:resource/last-write-source pulled))))))))
