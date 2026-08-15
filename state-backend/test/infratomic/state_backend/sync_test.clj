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
             :ReferencedGroupInfo {:GroupId "sg-ad032061586482f30" :UserId "000000000000"}}))))

  ;; Issue #32 PR #36 round-2 review follow-up: confirmed empirically
  ;; against a running LocalStack instance, AWS reports FromPort/ToPort as
  ;; `-1` (a sentinel - ports are meaningless for an all-protocols rule),
  ;; not `0`, for an `IpProtocol` `"-1"` rule - but Terraform's own
  ;; `aws_security_group_rule` convention (and this codebase's sample app)
  ;; asserts `0`. Left untranslated, `db/resource-composite-key` (which
  ;; includes from_port/to_port) could never match such a rule back to its
  ;; Terraform-managed instance.
  (testing "an all-protocols rule normalizes AWS's -1 FromPort/ToPort sentinel to Terraform's 0 convention"
    (is (= {"id"                "sgr-all-protocols"
            "from_port"         0
            "to_port"           0
            "protocol"          "-1"
            "security_group_id" "sg-b450dcc8007bd5693"
            "type"              "egress"
            "cidr_blocks"       ["0.0.0.0/0"]}
           (sync/security-group-rule->attrs
            {:SecurityGroupRuleId "sgr-all-protocols"
             :GroupId "sg-b450dcc8007bd5693"
             :GroupOwnerId "000000000000"
             :IsEgress true
             :IpProtocol "-1"
             :FromPort -1
             :ToPort -1
             :CidrIpv4 "0.0.0.0/0"
             :Tags []})))))

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

(deftest iam-role-policy-attachment-translation
  (is (= {"role" "infratomic-test-app-lambda-exec"
          "policy_arn" "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess"}
         (sync/iam-role-policy-attachment->attrs
          "infratomic-test-app-lambda-exec"
          {:PolicyName "AmazonS3ReadOnlyAccess"
           :PolicyArn "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess"}))))

;; ---------------------------------------------------------------------------
;; explicit-route?/subnet-association? - confirms AWS-implicit entries stay
;; filtered out of describe-all's output (issue #32 task 4.4: the
;; removed-child presence-marker step's observed-id sets are built directly
;; from describe-all's output, so this already-shipped filtering continuing
;; to hold is what keeps an implicit local route/main-table association from
;; ever being treated as a candidate resource, managed or otherwise, in the
;; first place - a regression test, not new filtering code).
;; ---------------------------------------------------------------------------

(deftest explicit-route?-excludes-the-implicit-local-route
  (is (not (#'sync/explicit-route? {:Origin "CreateRouteTable" :GatewayId "local"})))
  (is (#'sync/explicit-route? {:Origin "CreateRoute" :GatewayId "igw-1"})))

(deftest subnet-association?-excludes-the-implicit-main-table-association
  (is (not (#'sync/subnet-association? {:Main true})))
  (is (#'sync/subnet-association? {:Main false :SubnetId "subnet-1"})))

;; ---------------------------------------------------------------------------
;; db/comparable-attributes - the diff-gate/drift-Rule comparison primitive
;; (issue #29 review findings: cardinality-many order-sensitivity and
;; empty-collection asymmetry, both false-drift sources)
;; ---------------------------------------------------------------------------

(deftest comparable-attributes-normalizes-cardinality-many-order
  (testing "same set of values in a different order compares equal"
    (is (= (db/comparable-attributes "aws_instance"
                                      {"id" "i-1" "vpc_security_group_ids" ["sg-aaa" "sg-bbb"]})
           (db/comparable-attributes "aws_instance"
                                      {"id" "i-1" "vpc_security_group_ids" ["sg-bbb" "sg-aaa"]}))))
  (testing "a genuinely different set does not compare equal"
    (is (not= (db/comparable-attributes "aws_instance"
                                         {"id" "i-1" "vpc_security_group_ids" ["sg-aaa" "sg-bbb"]})
              (db/comparable-attributes "aws_instance"
                                         {"id" "i-1" "vpc_security_group_ids" ["sg-aaa" "sg-ccc"]})))))

(deftest comparable-attributes-drops-empty-collection-same-as-absent-key
  (is (= (db/comparable-attributes "aws_instance" {"id" "i-1" "subnet_id" "subnet-1"})
         (db/comparable-attributes "aws_instance"
                                    {"id" "i-1" "subnet_id" "subnet-1" "vpc_security_group_ids" []}))))

;; Issue #32 PR #36 round-2 review follow-up: confirmed against a real
;; `terraform apply` + `GET /state`, Terraform's classic `aws_route`
;; resource posts `""` (empty string), not `null`, for every unused
;; target attribute (e.g. `vpc_peering_connection_id` on a gateway-only
;; route) - an asymmetry against Sync's own `nil`-for-unused-target
;; translation (`route->attrs`) that, left uncorrected, made every
;; composite-matched `aws_route` compare unequal (perpetual false drift)
;; on every single Sync pass.
(deftest comparable-attributes-drops-empty-string-same-as-absent-key
  (is (= (db/comparable-attributes "aws_route"
                                    {"id" "route-1" "route_table_id" "rt-1"
                                     "destination_cidr_block" "0.0.0.0/0" "gateway_id" "igw-1"})
         (db/comparable-attributes "aws_route"
                                    {"id" "route-1" "route_table_id" "rt-1"
                                     "destination_cidr_block" "0.0.0.0/0" "gateway_id" "igw-1"
                                     "vpc_peering_connection_id" ""}))))

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

;; ---------------------------------------------------------------------------
;; resource-tx matching a Terraform-managed resource with a cardinality-many
;; modeled attribute (`vpc_security_group_ids`) - issue #29 review finding:
;; Datomic's pull order for such an attribute is independent of the order
;; the freshly-observed value is built in, so the diff-gate must not treat a
;; same-set-different-order value as drift.
;; ---------------------------------------------------------------------------

(defn- managed-conn-with-instance
  "A fresh conn with one Terraform-managed `aws_instance` entity already
  transacted (as `resource->tx`/`POST /state` would), with two
  `vpc_security_group_ids` values."
  []
  (let [conn (fresh-conn)
        tx   (merge {:resource/id                 "aws_instance.web"
                      :resource/type               "aws_instance"
                      :resource/managed?           true
                      :resource/last-write-source  :terraform}
                     (db/resource-attr-tx "aws_instance"
                                          {"id"                     "i-managed"
                                           "subnet_id"              "subnet-1"
                                           "vpc_security_group_ids" ["sg-aaa" "sg-bbb"]}))]
    (d/transact conn {:tx-data [tx]})
    conn))

(deftest resource-tx-cardinality-many-attribute-same-set-different-order-is-not-drift
  (testing "observed value is the same set of security group ids, just pulled/observed in a different order: no drift"
    (let [conn (managed-conn-with-instance)
          db   (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_instance" "i-managed"
                             {"id" "i-managed" "subnet_id" "subnet-1"
                              "vpc_security_group_ids" ["sg-bbb" "sg-aaa"]})]
      (is (= :skipped-already-managed outcome))
      (is (empty? tx-data)))))

(deftest resource-tx-cardinality-many-attribute-actual-change-is-drift
  (testing "observed value is a genuinely different set of security group ids: real drift"
    (let [conn (managed-conn-with-instance)
          db   (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_instance" "i-managed"
                             {"id" "i-managed" "subnet_id" "subnet-1"
                              "vpc_security_group_ids" ["sg-aaa" "sg-ccc"]})]
      (is (= :drifted outcome))
      (is (seq tx-data)))))

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

;; ---------------------------------------------------------------------------
;; resource-tx composite-key matching for aws_iam_role_policy_attachment
;; (issue #32) - this type has no modeled "id" of its own, so matching is by
;; the (role, policy_arn) pair together, not a single AWS-assigned id.
;; ---------------------------------------------------------------------------

(def ^:private lambda-attachment-attrs
  {"role" "lambda-exec" "policy_arn" "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess"})

(defn- managed-conn-with-attachment
  "A fresh conn with one Terraform-managed `aws_iam_role_policy_attachment`
  entity already transacted, matching `lambda-attachment-attrs`, tagged
  `:resource/last-write-source :terraform` as `resource->tx`/`POST /state`
  would."
  []
  (let [conn (fresh-conn)]
    (d/transact conn {:tx-data [(merge {:resource/id                 "aws_iam_role_policy_attachment.lambda_s3"
                                          :resource/type               "aws_iam_role_policy_attachment"
                                          :resource/managed?           true
                                          :resource/last-write-source  :terraform}
                                         (db/resource-attr-tx "aws_iam_role_policy_attachment" lambda-attachment-attrs))]})
    conn))

(deftest resource-tx-with-no-existing-match-discovers-an-iam-role-policy-attachment
  (let [conn (fresh-conn)
        db   (d/db conn)
        {:keys [tx-data outcome]} (sync/resource-tx db "aws_iam_role_policy_attachment" nil lambda-attachment-attrs)]
    (is (= :discovered outcome))
    (d/transact conn {:tx-data tx-data})
    (let [db'    (d/db conn)
          eid    (ffirst (d/q '[:find ?e :where [?e :aws-iam-role-policy-attachment/role "lambda-exec"]] db'))
          pulled (d/pull db' [:resource/id :resource/managed?] eid)]
      (is (= (str "aws_iam_role_policy_attachment.discovered-lambda-exec-"
                  "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess")
             (:resource/id pulled)))
      (is (= false (:resource/managed? pulled))))))

(deftest resource-tx-matches-a-managed-attachment-by-role-and-policy-arn-together
  (let [conn (managed-conn-with-attachment)
        db   (d/db conn)
        {:keys [tx-data outcome]} (sync/resource-tx db "aws_iam_role_policy_attachment" nil lambda-attachment-attrs)]
    (is (= :skipped-already-managed outcome))
    (is (empty? tx-data))))

(deftest resource-tx-a-different-policy-on-the-same-role-is-discovered-not-matched
  (let [conn (managed-conn-with-attachment)
        db   (d/db conn)
        {:keys [outcome]}
        (sync/resource-tx db "aws_iam_role_policy_attachment" nil
                           {"role" "lambda-exec" "policy_arn" "arn:aws:iam::aws:policy/AWSLambdaBasicExecutionRole"})]
    (is (= :discovered outcome))))

;; ---------------------------------------------------------------------------
;; resource-tx reappearance fix (issue #32): a managed match whose
;; :resource/sync-present? is currently false must still write (at minimum
;; reasserting :resource/sync-present? true) even when attributes are
;; unchanged, or the marker would never clear.
;; ---------------------------------------------------------------------------

(deftest resource-tx-reappearance-fix-forces-a-write-to-clear-a-false-sync-present-marker
  (let [conn (managed-conn-with-attachment)]
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_iam_role_policy_attachment.lambda_s3"]
                                  :resource/sync-present? false]]})
    (let [db (d/db conn)
          {:keys [tx-data outcome]} (sync/resource-tx db "aws_iam_role_policy_attachment" nil lambda-attachment-attrs)]
      (is (= :skipped-already-managed outcome))
      (is (seq tx-data))
      (d/transact conn {:tx-data tx-data})
      (let [db'    (d/db conn)
            eid    (ffirst (d/q '[:find ?e :where [?e :aws-iam-role-policy-attachment/role "lambda-exec"]] db'))
            pulled (d/pull db' [:resource/sync-present?] eid)]
        (is (= true (:resource/sync-present? pulled)))))))

(deftest resource-tx-a-genuinely-unchanged-covered-resource-with-no-prior-false-marker-makes-no-write
  (testing "no reappearance to fix: the ordinary no-write skip is unaffected"
    (let [conn (managed-conn-with-attachment)
          db   (d/db conn)
          {:keys [tx-data outcome]} (sync/resource-tx db "aws_iam_role_policy_attachment" nil lambda-attachment-attrs)]
      (is (= :skipped-already-managed outcome))
      (is (empty? tx-data)))))

(defn- managed-conn-with-route
  "A fresh conn with one Terraform-managed `aws_route` entity already
  transacted, `:aws-route/id \"route-1\"` - Terraform's own internal route
  id, deliberately never the shape `sync/route->attrs` synthesizes
  (`\"<route_table_id>-<destination_cidr_block>\"`), matching what a real
  `POST /state` write posts (ADR-0006/`db/id-space-mismatched-types`) -
  tagged `:resource/last-write-source :terraform`."
  []
  (let [conn (fresh-conn)
        tx   (merge {:resource/id                 "aws_route.rt_a_igw"
                      :resource/type               "aws_route"
                      :resource/managed?           true
                      :resource/last-write-source  :terraform}
                     (db/resource-attr-tx "aws_route"
                                          {"id" "route-1" "route_table_id" "rt-1"
                                           "destination_cidr_block" "0.0.0.0/0"
                                           "gateway_id" "igw-1"}))]
    (d/transact conn {:tx-data [tx]})
    conn))

;; ---------------------------------------------------------------------------
;; resource-tx composite-key matching for aws_route (issue #32 PR #36
;; round-2 fix): Terraform's own `"id"` for this type is a different id
;; space than the AWS id Sync observes (ADR-0006), so matching is by
;; `(route_table_id, destination_cidr_block)` together, not `"id"` - and
;; `"id"` itself must never be treated as drift or overwritten by a Sync
;; write to the match (`db/id-space-mismatched-types`).
;; ---------------------------------------------------------------------------

(deftest resource-tx-matches-a-managed-route-by-route-table-and-cidr-together
  (testing "same route_table_id/destination_cidr_block/gateway_id, observed under AWS's own (different) id: matched, no drift"
    (let [conn (managed-conn-with-route)
          db   (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_route" "rt-1-0.0.0.0/0"
                             {"id" "rt-1-0.0.0.0/0" "route_table_id" "rt-1"
                              "destination_cidr_block" "0.0.0.0/0" "gateway_id" "igw-1"})]
      (is (= :skipped-already-managed outcome))
      (is (empty? tx-data)))))

(deftest resource-tx-a-route-attribute-change-drifts-without-overwriting-terraforms-id
  (testing "same route_table_id/destination_cidr_block, different gateway_id: matched, real drift, Terraform's own id untouched"
    (let [conn (managed-conn-with-route)
          db   (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_route" "rt-1-0.0.0.0/0"
                             {"id" "rt-1-0.0.0.0/0" "route_table_id" "rt-1"
                              "destination_cidr_block" "0.0.0.0/0" "gateway_id" "igw-2"})]
      (is (= :drifted outcome))
      (d/transact conn {:tx-data tx-data})
      (let [db'    (d/db conn)
            eid    (ffirst (d/q '[:find ?e :where [?e :resource/id "aws_route.rt_a_igw"]] db'))
            pulled (d/pull db' [:aws-route/id :aws-route/gateway-id :resource/last-write-source] eid)]
        (is (= "route-1" (:aws-route/id pulled))
            "Terraform's own asserted id must never be overwritten by AWS's observed one")
        (is (= "igw-2" (:aws-route/gateway-id pulled)))
        (is (= :sync (:resource/last-write-source pulled)))))))

(deftest resource-tx-a-route-with-a-different-cidr-is-discovered-not-matched
  (testing "a genuinely different route (different destination_cidr_block on the same table): not matched"
    (let [conn (managed-conn-with-route)
          db   (d/db conn)
          {:keys [outcome]}
          (sync/resource-tx db "aws_route" "rt-1-10.0.0.0/16"
                             {"id" "rt-1-10.0.0.0/16" "route_table_id" "rt-1"
                              "destination_cidr_block" "10.0.0.0/16" "gateway_id" "igw-1"})]
      (is (= :discovered outcome)))))

(deftest resource-tx-route-reappearance-fix-forces-a-write-without-touching-terraforms-id
  (let [conn (managed-conn-with-route)]
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_route.rt_a_igw"] :resource/sync-present? false]]})
    (let [db (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_route" "rt-1-0.0.0.0/0"
                             {"id" "rt-1-0.0.0.0/0" "route_table_id" "rt-1"
                              "destination_cidr_block" "0.0.0.0/0" "gateway_id" "igw-1"})]
      (is (= :skipped-already-managed outcome))
      (is (seq tx-data))
      (d/transact conn {:tx-data tx-data})
      (let [db'    (d/db conn)
            pulled (d/pull db' [:resource/sync-present? :aws-route/id]
                            [:resource/id "aws_route.rt_a_igw"])]
        (is (= true (:resource/sync-present? pulled)))
        (is (= "route-1" (:aws-route/id pulled)))))))

(defn- managed-conn-with-sg-rule
  "A fresh conn with one Terraform-managed `aws_security_group_rule`
  entity already transacted, `:aws-security-group-rule/id \"sgrule-1\"` -
  Terraform's own client-side rule id (a provider hash, e.g.
  `\"sgrule-<n>\"`), deliberately never AWS's own `SecurityGroupRuleId`
  (ADR-0006/`db/id-space-mismatched-types`) - tagged
  `:resource/last-write-source :terraform`."
  []
  (let [conn (fresh-conn)
        tx   (merge {:resource/id                 "aws_security_group_rule.ssh_open_ingress"
                      :resource/type               "aws_security_group_rule"
                      :resource/managed?           true
                      :resource/last-write-source  :terraform}
                     (db/resource-attr-tx "aws_security_group_rule"
                                          {"id" "sgrule-1" "security_group_id" "sg-1"
                                           "type" "ingress" "protocol" "tcp"
                                           "from_port" 22 "to_port" 22
                                           "cidr_blocks" ["0.0.0.0/0"]}))]
    (d/transact conn {:tx-data [tx]})
    conn))

;; ---------------------------------------------------------------------------
;; resource-tx composite-key matching for aws_security_group_rule (issue #32
;; PR #36 round-2 fix): same id-space-mismatch story as aws_route above, but
;; matched on `(security_group_id, type, protocol, from_port, to_port,
;; cidr_blocks/source_security_group_id)` together - which happens to be
;; every one of this type's other modeled attributes, so once matched, a
;; rule can never itself show plain attribute-level drift: any change to one
;; of those fields is, by AWS's own semantics (a rule's shape is otherwise
;; immutable via the classic `aws_security_group_rule` API surface this
;; system targets), a *different* rule, surfacing as a new-child/removed-
;; child pair instead (see query_test.clj) - not tested again here.
;; ---------------------------------------------------------------------------

(deftest resource-tx-matches-a-managed-sg-rule-by-shape-together
  (testing "same shape, observed under AWS's own (different) SecurityGroupRuleId: matched, no drift"
    (let [conn (managed-conn-with-sg-rule)
          db   (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_security_group_rule" "sgr-aws-1"
                             {"id" "sgr-aws-1" "security_group_id" "sg-1"
                              "type" "ingress" "protocol" "tcp"
                              "from_port" 22 "to_port" 22
                              "cidr_blocks" ["0.0.0.0/0"]})]
      (is (= :skipped-already-managed outcome))
      (is (empty? tx-data)))))

(deftest resource-tx-a-differently-shaped-sg-rule-on-the-same-group-is-discovered-not-matched
  (testing "same security group, a genuinely different rule shape (different from_port): not matched"
    (let [conn (managed-conn-with-sg-rule)
          db   (d/db conn)
          {:keys [outcome]}
          (sync/resource-tx db "aws_security_group_rule" "sgr-aws-2"
                             {"id" "sgr-aws-2" "security_group_id" "sg-1"
                              "type" "ingress" "protocol" "tcp"
                              "from_port" 2222 "to_port" 2222
                              "cidr_blocks" ["0.0.0.0/0"]})]
      (is (= :discovered outcome)))))

;; ADR-0006 preservation (issue #32 PR #36 round-2 review): composite-key
;; matching is only a *fallback* for `aws_security_group_rule`/`aws_route`,
;; tried after `id-based-match` - re-observing an already-*Discovered* rule
;; under the SAME AWS `SecurityGroupRuleId` must keep updating that exact
;; entity in place (ADR-0006's decision), not fall through to a
;; shape-based composite match, which could in principle re-identify a
;; *different* rule with the same shape (the collision risk ADR-0006
;; rejected the tuple over in the first place, for this exact case - unlike
;; matching an already Terraform-declared rule, where that risk doesn't
;; apply the same way).
(deftest resource-tx-a-rediscovered-sg-rule-matches-by-its-own-aws-id-not-composite-shape
  (let [conn (fresh-conn)
        db0  (d/db conn)
        {:keys [tx-data]} (sync/resource-tx db0 "aws_security_group_rule" "sgr-aws-1"
                                             {"id" "sgr-aws-1" "security_group_id" "sg-1"
                                              "type" "ingress" "protocol" "tcp"
                                              "from_port" 22 "to_port" 22
                                              "cidr_blocks" ["0.0.0.0/0"]})]
    (d/transact conn {:tx-data tx-data})
    (let [db1 (d/db conn)
          ;; Re-observed again, same AWS id, same shape - must update the
          ;; same Discovered entity in place (:updated), not discover a
          ;; second one.
          {:keys [tx-data outcome]}
          (sync/resource-tx db1 "aws_security_group_rule" "sgr-aws-1"
                             {"id" "sgr-aws-1" "security_group_id" "sg-1"
                              "type" "ingress" "protocol" "tcp"
                              "from_port" 22 "to_port" 22
                              "cidr_blocks" ["0.0.0.0/0"]})]
      (is (= :updated outcome))
      (d/transact conn {:tx-data tx-data})
      (let [db'  (d/db conn)
            eids (map first (d/q '[:find ?e :where [?e :aws-security-group-rule/id "sgr-aws-1"]] db'))]
        (is (= 1 (count eids)))))))

(deftest resource-tx-sg-rule-reappearance-fix-forces-a-write-without-touching-terraforms-id
  (let [conn (managed-conn-with-sg-rule)]
    (d/transact conn {:tx-data [[:db/add [:resource/id "aws_security_group_rule.ssh_open_ingress"]
                                  :resource/sync-present? false]]})
    (let [db (d/db conn)
          {:keys [tx-data outcome]}
          (sync/resource-tx db "aws_security_group_rule" "sgr-aws-1"
                             {"id" "sgr-aws-1" "security_group_id" "sg-1"
                              "type" "ingress" "protocol" "tcp"
                              "from_port" 22 "to_port" 22
                              "cidr_blocks" ["0.0.0.0/0"]})]
      (is (= :skipped-already-managed outcome))
      (is (seq tx-data))
      (d/transact conn {:tx-data tx-data})
      (let [db'    (d/db conn)
            pulled (d/pull db' [:resource/sync-present? :aws-security-group-rule/id]
                            [:resource/id "aws_security_group_rule.ssh_open_ingress"])]
        (is (= true (:resource/sync-present? pulled)))
        (is (= "sgrule-1" (:aws-security-group-rule/id pulled)))))))

;; ---------------------------------------------------------------------------
;; missing-child-tx composite-key matching for aws_route/aws_security_group_rule
;; (issue #32 PR #36 round-2 fix, the removed-child half of Finding 1's twin):
;; before this fix, `db/managed-resource-ids-by-type` keyed these two types by
;; their stored (Terraform-space) `"id"`, which `resource-key`'s observed
;; (AWS-space) key could never equal - so `missing-child-tx` marked every
;; Terraform-managed instance `:resource/sync-present? false` on every pass,
;; whether or not it was actually removed. Both sides now build the same
;; `db/resource-composite-key`, so an unmodified, still-present resource's
;; key is found in `observed-keys` and no longer flagged.
;; ---------------------------------------------------------------------------

(deftest missing-child-tx-does-not-flag-an-unmodified-managed-route-despite-the-id-mismatch
  (let [conn (managed-conn-with-route)
        db   (d/db conn)
        ;; The observed-keys set `sync!` would build this pass, via
        ;; `resource-key`, from Sync's own translation of the still-present
        ;; route - same route_table_id/cidr, Sync's own (different) "id".
        observed-keys #{(#'sync/resource-key "aws_route"
                          {"id" "rt-1-0.0.0.0/0" "route_table_id" "rt-1"
                           "destination_cidr_block" "0.0.0.0/0"})}]
    (is (empty? (#'sync/missing-child-tx db "aws_route" observed-keys)))))

(deftest missing-child-tx-flags-a-genuinely-removed-managed-route
  (let [conn (managed-conn-with-route)
        db   (d/db conn)]
    (is (seq (#'sync/missing-child-tx db "aws_route" #{})))))

(deftest missing-child-tx-does-not-flag-an-unmodified-managed-sg-rule-despite-the-id-mismatch
  (let [conn (managed-conn-with-sg-rule)
        db   (d/db conn)
        observed-keys #{(#'sync/resource-key "aws_security_group_rule"
                          {"id" "sgr-aws-1" "security_group_id" "sg-1"
                           "type" "ingress" "protocol" "tcp"
                           "from_port" 22 "to_port" 22
                           "cidr_blocks" ["0.0.0.0/0"]})}]
    (is (empty? (#'sync/missing-child-tx db "aws_security_group_rule" observed-keys)))))

(deftest missing-child-tx-flags-a-genuinely-removed-managed-sg-rule
  (let [conn (managed-conn-with-sg-rule)
        db   (d/db conn)]
    (is (seq (#'sync/missing-child-tx db "aws_security_group_rule" #{})))))

;; ---------------------------------------------------------------------------
;; Presence tracking for Discovered (not just Terraform-managed) resources
;; of a covered type (issue #32 PR #36 round-4 review fix): without this, a
;; Discovered child the new-child detection mechanism itself creates never
;; had its own presence tracked, so query.clj's `new-children-by-parent`
;; could never learn it had genuinely disappeared and kept reporting it
;; forever. `resource-tx`'s `nil? match` and `false? managed?` branches now
;; also assert `:resource/sync-present? true` for a `sync-present-types`
;; type, and `missing-child-tx` (via `db/resource-ids-by-type`, no longer
;; managed-only) now marks a stored-but-unobserved Discovered entity of a
;; covered type `false` too, exactly as it already did for a
;; Terraform-managed one.
;; ---------------------------------------------------------------------------

(deftest resource-tx-a-freshly-discovered-covered-child-is-marked-sync-present
  (let [conn (fresh-conn)
        db   (d/db conn)
        {:keys [tx-data outcome]}
        (sync/resource-tx db "aws_security_group_rule" "sgr-1"
                           {"id" "sgr-1" "security_group_id" "sg-1"
                            "type" "ingress" "protocol" "tcp"
                            "from_port" 22 "to_port" 22
                            "cidr_blocks" ["0.0.0.0/0"]})]
    (is (= :discovered outcome))
    (d/transact conn {:tx-data tx-data})
    (let [db'    (d/db conn)
          eid    (eid-by-aws-id db' :aws-security-group-rule/id "sgr-1")
          pulled (d/pull db' [:resource/sync-present?] eid)]
      (is (= true (:resource/sync-present? pulled))))))

(deftest resource-tx-a-freshly-discovered-uncovered-type-is-not-marked-sync-present
  (let [conn (fresh-conn)
        db   (d/db conn)
        {:keys [tx-data outcome]} (sync/resource-tx db "aws_security_group" "sg-999" {"id" "sg-999" "vpc_id" "vpc-1"})]
    (is (= :discovered outcome))
    (d/transact conn {:tx-data tx-data})
    (let [db'    (d/db conn)
          eid    (eid-by-aws-id db' :aws-security-group/id "sg-999")
          pulled (d/pull db' [:resource/sync-present?] eid)]
      (is (nil? (:resource/sync-present? pulled))))))

(deftest missing-child-tx-flags-a-discovered-covered-child-that-later-disappears
  (let [conn (fresh-conn)
        db0  (d/db conn)
        {:keys [tx-data]}
        (sync/resource-tx db0 "aws_security_group_rule" "sgr-1"
                           {"id" "sgr-1" "security_group_id" "sg-1"
                            "type" "ingress" "protocol" "tcp"
                            "from_port" 22 "to_port" 22
                            "cidr_blocks" ["0.0.0.0/0"]})]
    (d/transact conn {:tx-data tx-data})
    (is (seq (#'sync/missing-child-tx (d/db conn) "aws_security_group_rule" #{})))))

(deftest missing-child-tx-does-not-flag-a-still-observed-discovered-covered-child
  (let [conn (fresh-conn)
        db0  (d/db conn)
        {:keys [tx-data]}
        (sync/resource-tx db0 "aws_security_group_rule" "sgr-1"
                           {"id" "sgr-1" "security_group_id" "sg-1"
                            "type" "ingress" "protocol" "tcp"
                            "from_port" 22 "to_port" 22
                            "cidr_blocks" ["0.0.0.0/0"]})]
    (d/transact conn {:tx-data tx-data})
    (let [db            (d/db conn)
          observed-keys #{(#'sync/resource-key "aws_security_group_rule"
                            {"id" "sgr-1" "security_group_id" "sg-1"
                             "type" "ingress" "protocol" "tcp"
                             "from_port" 22 "to_port" 22
                             "cidr_blocks" ["0.0.0.0/0"]})}]
      (is (empty? (#'sync/missing-child-tx db "aws_security_group_rule" observed-keys))))))

(deftest resource-tx-re-observing-a-disappeared-then-reappeared-discovered-child-reasserts-sync-present-true
  (let [conn (fresh-conn)
        db0  (d/db conn)
        {:keys [tx-data]}
        (sync/resource-tx db0 "aws_security_group_rule" "sgr-1"
                           {"id" "sgr-1" "security_group_id" "sg-1"
                            "type" "ingress" "protocol" "tcp"
                            "from_port" 22 "to_port" 22
                            "cidr_blocks" ["0.0.0.0/0"]})]
    (d/transact conn {:tx-data tx-data})
    (d/transact conn {:tx-data (#'sync/missing-child-tx (d/db conn) "aws_security_group_rule" #{})})
    (let [db1                    (d/db conn)
          eid                    (eid-by-aws-id db1 :aws-security-group-rule/id "sgr-1")
          pulled-before-reappear (d/pull db1 [:resource/sync-present?] eid)]
      (is (= false (:resource/sync-present? pulled-before-reappear)))
      (let [{:keys [tx-data outcome]}
            (sync/resource-tx db1 "aws_security_group_rule" "sgr-1"
                               {"id" "sgr-1" "security_group_id" "sg-1"
                                "type" "ingress" "protocol" "tcp"
                                "from_port" 22 "to_port" 22
                                "cidr_blocks" ["0.0.0.0/0"]})]
        (is (= :updated outcome))
        (d/transact conn {:tx-data tx-data})
        (let [db2    (d/db conn)
              pulled (d/pull db2 [:resource/sync-present?] eid)]
          (is (= true (:resource/sync-present? pulled))))))))

;; ---------------------------------------------------------------------------
;; The sync-present? marker is never touched for a type outside
;; sync-present-types (e.g. aws_security_group is not one of the four
;; FK-bearing child types this mechanism covers).
;; ---------------------------------------------------------------------------

(deftest resource-tx-does-not-touch-sync-present-for-an-uncovered-type
  (let [conn (managed-conn-with)
        db   (d/db conn)
        {:keys [tx-data outcome]} (sync/resource-tx db "aws_security_group" "sg-managed" {"id" "sg-managed"})]
    (is (= :skipped-already-managed outcome))
    (is (empty? tx-data))))
