(ns infratomic.state-backend.sync-integration-test
  "Integration test: applies the sample Terraform app (`terraform/`) against
  already-running LocalStack, directly creates an extra security group (and
  an ingress rule on it) via the EC2 API - bypassing Terraform entirely, the
  same way a real out-of-band/drifted resource would exist - then runs the
  real `sync!` pass (real `cognitect.aws/api` calls against LocalStack, real
  Datomic dev-local transactions) and asserts on its result and on the
  resulting db. Also covers issue #27's out-of-band drift on an *already*
  Terraform-managed resource: directly changing one of the sample app's
  instances' security groups (`ModifyInstanceAttribute`, same
  `InstanceId`) and asserting Sync updates it (tagging
  `:resource/last-write-source :sync`, reporting it in the `:drifted`
  summary bucket) and that `GET /drift` reflects it.

  NOT part of the hermetic `clojure -X:test` suite (see `test_runner.clj`)
  - it shells out to real `terraform`/`docker` and makes real LocalStack API
  calls, unlike every other test namespace besides `query_integration_test`.
  Run it separately via `clojure -X:integration-test`, after bringing up
  `docker compose up -d` from the repo root (LocalStack reachable at
  http://localhost:4566, with the `ec2` service enabled). Mirrors
  `query_integration_test.clj`'s fixture pattern (own State Backend
  connection, apply-then-destroy the sample app in a `finally`) - see that
  namespace's docstring for why (Datomic dev-local's exclusive connection
  lock)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [cognitect.aws.client.api :as aws]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.main :as main]
            [infratomic.state-backend.query :as query]
            [infratomic.state-backend.sync :as sync]
            [ring.adapter.jetty :as jetty]))

(def ^:private port 8080)

(def ^:private terraform-dir
  (str (io/file (System/getProperty "user.dir") ".." "terraform")))

(defn- terraform!
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh "terraform" (concat args [:dir terraform-dir]))]
    (when-not (zero? exit)
      (throw (ex-info (str "terraform " (first args) " failed (exit " exit ")")
                       {:args args :out out :err err})))
    out))

(defn- with-state-backend-server
  "Start a real State Backend HTTP server on `port`, bound to a connection
  this test also uses directly for `sync!` afterward, for the duration of
  `(f conn)` - `terraform init`/`apply`/`destroy` need a live `/state`
  endpoint to talk to, exactly as `query_integration_test.clj`'s fixture
  of the same name does. Stops the server in a `finally`."
  [f]
  (let [conn   (db/ensure-db! (db/client))
        server (jetty/run-jetty (handler/handler conn) {:port port :join? false})]
    (try
      (f conn)
      (finally
        (.stop server)))))

(defn- with-applied-sample-app
  [f]
  (terraform! "init" "-input=false")
  (terraform! "destroy" "-auto-approve")
  (terraform! "apply" "-auto-approve")
  (try
    (f)
    (finally
      (terraform! "destroy" "-auto-approve"))))

(defn- eid-by-aws-id
  "A modeled id attribute (e.g. `:aws-security-group/id`) isn't
  `:db.unique/*`, so it can't be looked up via a `[:ident value]` lookup
  ref - find its entity id via a direct query instead."
  [db ident aws-id]
  (ffirst (d/q '[:find ?e :in $ ?ident ?v :where [?e ?ident ?v]] db ident aws-id)))

(defn- create-out-of-band-security-group!
  "Directly creates a security group (with an ingress rule opening port 22
  to `0.0.0.0/0`, matching the `security-groups-with-port-22-open` Rule's
  shape) against LocalStack via the EC2 API - bypassing Terraform entirely,
  standing in for a real out-of-band/drifted resource. Returns the new
  security group's id."
  [client]
  (let [vpc-id (-> (aws/invoke client {:op :DescribeVpcs}) :Vpcs first :VpcId)
        sg-id  (-> (aws/invoke client {:op :CreateSecurityGroup
                                        :request {:GroupName (str "sync-test-" (random-uuid))
                                                   :Description "Sync integration test fixture"
                                                   :VpcId vpc-id}})
                    :GroupId)]
    (aws/invoke client {:op :AuthorizeSecurityGroupIngress
                         :request {:GroupId sg-id
                                   :IpPermissions [{:IpProtocol "tcp" :FromPort 22 :ToPort 22
                                                     :IpRanges [{:CidrIp "0.0.0.0/0"}]}]}})
    sg-id))

(deftest sync-discovers-out-of-band-resources-without-duplicating-or-touching-managed-ones
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [client (sync/ec2-client)
                sg-id  (create-out-of-band-security-group! client)]
            (try
              (let [summary1 (sync/sync! conn client)]

                (testing "the out-of-band security group is discovered"
                  (is (contains? (into #{} (map :id) (:discovered summary1)) sg-id)))

                (testing "its ingress rule is discovered too"
                  (is (some #(and (= "aws_security_group_rule" (:type %))
                                   (let [db  (d/db conn)
                                         eid (eid-by-aws-id db :aws-security-group-rule/id (:id %))]
                                     (= sg-id (:aws-security-group-rule/security-group-id
                                               (d/pull db [:aws-security-group-rule/security-group-id] eid)))))
                            (:discovered summary1))))

                (testing "the discovered security group is tagged unmanaged"
                  (let [db  (d/db conn)
                        eid (eid-by-aws-id db :aws-security-group/id sg-id)]
                    (is (= false (:resource/managed? (d/pull db [:resource/managed?] eid))))))

                (testing "the Terraform-managed sample-app security groups are skipped, not re-discovered"
                  (is (not (contains? (into #{} (map :id) (:discovered summary1)) "aws_security_group.ssh_open")))
                  (is (pos? (:skipped-already-managed summary1))))

                (let [summary2 (sync/sync! conn client)]
                  (testing "running sync again with no LocalStack changes creates no duplicates"
                    (is (empty? (:discovered summary2)))
                    (let [db (d/db conn)]
                      (is (= 1 (count (d/q '[:find ?e :in $ ?id :where [?e :aws-security-group/id ?id]] db sg-id))))))

                  (testing "the previously-discovered security group is reported as updated, not re-discovered"
                    (is (contains? (into #{} (map :id) (:updated summary2)) sg-id)))))
              (finally
                ;; Clean up the out-of-band security group so repeated
                ;; test runs don't accumulate orphan LocalStack resources
                ;; (mirrors query_integration_test.clj's "safe to run
                ;; repeatedly" property).
                (aws/invoke client {:op :DeleteSecurityGroup :request {:GroupId sg-id}})
                (aws/stop client)))))))))

;; ---------------------------------------------------------------------------
;; New-child drift: an out-of-band route / route-table-association / IAM
;; role policy attachment on a managed parent (issue #32). The security-
;; group-rule case is already covered above
;; (`sync-discovers-out-of-band-resources-without-duplicating-or-touching-
;; managed-ones`); this covers the remaining three FK-bearing child types.
;;
;; `aws_route` is still hand-added here (to exercise Sync's route
;; translation end-to-end against a real managed route table), but is
;; deliberately NOT asserted as new-child drift below - it's one of the
;; two types `query.clj`'s `new-child-detection-gap-types` excludes
;; entirely (see that var's docstring and
;; `sync-does-not-flag-terraform-managed-sg-rules-or-routes-as-new-child-
;; drift` further down for why).
;; ---------------------------------------------------------------------------

(defn- route-table-id-by-name
  [client name]
  (-> (aws/invoke client {:op :DescribeRouteTables :request {:Filters [{:Name "tag:Name" :Values [name]}]}})
      :RouteTables first :RouteTableId))

(defn- role-name-by-tf-name
  "The sample app's `aws_iam_role.lambda_exec`'s `name` attribute (a
  literal, not tag-based - IAM roles have no tags in this fixture) -
  hardcoded, matching `iam.tf`'s literal `name = \"infratomic-test-app-
  lambda-exec\"`."
  []
  "infratomic-test-app-lambda-exec")

(deftest sync-discovers-new-out-of-band-children-of-managed-parents
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [ec2-client (sync/ec2-client)
                iam-client  (sync/iam-client)
                rt-a-id     (route-table-id-by-name ec2-client "infratomic-test-app-rt-a")
                rt-c-id     (route-table-id-by-name ec2-client "infratomic-test-app-rt-c")
                igw-a-id    (-> (aws/invoke ec2-client {:op :DescribeRouteTables :request {:RouteTableIds [rt-a-id]}})
                                :RouteTables first :Routes
                                (->> (some #(when (= "0.0.0.0/0" (:DestinationCidrBlock %)) (:GatewayId %)))))
                vpc-c-id    (-> (aws/invoke ec2-client {:op :DescribeRouteTables :request {:RouteTableIds [rt-c-id]}})
                                :RouteTables first :VpcId)
                subnet-id   (-> (aws/invoke ec2-client {:op :CreateSubnet
                                                          :request {:VpcId vpc-c-id :CidrBlock "10.2.9.0/24"}})
                                :Subnet :SubnetId)
                assoc-id    (-> (aws/invoke ec2-client {:op :AssociateRouteTable
                                                          :request {:RouteTableId rt-c-id :SubnetId subnet-id}})
                                :AssociationId)
                role-name   (role-name-by-tf-name)]
            (aws/invoke ec2-client {:op :CreateRoute
                                      :request {:RouteTableId rt-a-id
                                                :DestinationCidrBlock "198.51.100.0/24"
                                                :GatewayId igw-a-id}})
            (aws/invoke iam-client {:op :AttachRolePolicy
                                      :request {:RoleName role-name
                                                :PolicyArn "arn:aws:iam::aws:policy/ReadOnlyAccess"}})
            (try
              (let [_        (sync/sync! conn ec2-client)
                    db       (d/db conn)
                    rt-a-eid (eid-by-aws-id db :aws-route-table/id rt-a-id)
                    rt-c-eid (eid-by-aws-id db :aws-route-table/id rt-c-id)
                    role-eid (ffirst (d/q '[:find ?e :in $ ?name :where [?e :aws-iam-role/name ?name]] db role-name))
                    new-children (query/new-children-by-parent db)]
                (testing "the hand-added route is NOT flagged as new-child drift (known Sync id-matching gap for aws_route)"
                  (is (not (some #(= "aws_route" (:resource/type %)) (get new-children rt-a-eid)))))
                (testing "the hand-added association is a new child of the managed route table (unmanaged subnet side)"
                  (is (some #(= "aws_route_table_association" (:resource/type %)) (get new-children rt-c-eid))))
                (testing "the hand-attached policy is a new child of the managed IAM role"
                  (is (some #(= "aws_iam_role_policy_attachment" (:resource/type %)) (get new-children role-eid)))))
              (finally
                (aws/invoke iam-client {:op :DetachRolePolicy
                                          :request {:RoleName role-name
                                                    :PolicyArn "arn:aws:iam::aws:policy/ReadOnlyAccess"}})
                (aws/invoke ec2-client {:op :DeleteRoute
                                          :request {:RouteTableId rt-a-id :DestinationCidrBlock "198.51.100.0/24"}})
                (aws/invoke ec2-client {:op :DisassociateRouteTable :request {:AssociationId assoc-id}})
                (aws/invoke ec2-client {:op :DeleteSubnet :request {:SubnetId subnet-id}})
                (aws/stop ec2-client)
                (aws/stop iam-client)))))))))

;; ---------------------------------------------------------------------------
;; Code-review regression (issue #32 PR #36, Finding 1): a freshly-applied,
;; never-touched sample app - real `aws_security_group_rule`/`aws_route`
;; instances included - must report zero new-child drift after a single
;; `sync!` pass. Before the fix, the `aws_security_group_rule`/`aws_route`
;; id-matching gap (`sync.clj`'s `sync-present-types` docstring) made
;; `resource-tx` treat every one of the sample app's own already-managed
;; rules/routes as unmatched, producing a permanent Discovered-Resource
;; duplicate of each that `new-children-by-parent` then misreported as
;; new-child drift on its own real, unmodified managed parent - exactly
;; what the drift-detection spec's "A Terraform-managed child is not
;; flagged as new-child drift" scenario forbids.
;; ---------------------------------------------------------------------------

(deftest sync-does-not-flag-terraform-managed-sg-rules-or-routes-as-new-child-drift
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [ec2-client (sync/ec2-client)]
            (try
              (sync/sync! conn ec2-client)
              (let [db           (d/db conn)
                    new-children (query/new-children-by-parent db)
                    flagged-ids  (into #{}
                                        (mapcat (fn [children] (map :resource/id children)))
                                        (vals new-children))]
                (testing "no Terraform-managed security group rule is flagged as new-child drift on its security group"
                  (doseq [id ["aws_security_group_rule.ssh_open_ingress"
                              "aws_security_group_rule.https_only_ingress"
                              "aws_security_group_rule.reachability_open_egress"
                              "aws_security_group_rule.reachability_open_ingress"
                              "aws_security_group_rule.reachability_restricted_egress"]]
                    (is (not (contains? flagged-ids id)) (str id " should not be flagged as new-child drift"))))
                (testing "no Terraform-managed route is flagged as new-child drift on its route table"
                  (doseq [id ["aws_route.rt_a_igw" "aws_route.rt_a_pcx" "aws_route.rt_b_pcx"]]
                    (is (not (contains? flagged-ids id)) (str id " should not be flagged as new-child drift"))))
                (testing "GET /drift reports no new_children for any managed security group"
                  (let [drifted (get (json/parse-string (:body (query/drift-endpoint conn))) "drifted")]
                    (doseq [entry drifted]
                      (when (= "aws_security_group" (get entry "type"))
                        (is (not (contains? entry "new_children"))
                            (str (get entry "id") " should have no new_children")))))))
              (finally
                (aws/stop ec2-client)))))))))

;; ---------------------------------------------------------------------------
;; Drift: a Terraform-managed resource changed out-of-band (issue #27)
;; ---------------------------------------------------------------------------

(defn- instance-id-by-name
  "The `InstanceId` of the *running* instance tagged `Name` `name`. Filters
  on `instance-state-name=running` (not just `tag:Name`) because
  LocalStack, like real AWS, keeps terminated instances visible in
  `DescribeInstances` indefinitely (see `sync.clj`'s `live-instance?`) - a
  prior test run's now-terminated instance can share the same `Name` tag
  (the sample app's tags are fixed, not randomized), so an unfiltered
  lookup can resolve to a stale, no-longer-live instance instead of the
  one this test just applied."
  [client name]
  (-> (aws/invoke client {:op :DescribeInstances
                           :request {:Filters [{:Name "tag:Name" :Values [name]}
                                                {:Name "instance-state-name" :Values ["running"]}]}})
      :Reservations first :Instances first :InstanceId))

(defn- security-group-id-by-name
  "The `GroupId` of the security group named (its AWS `GroupName`, i.e.
  Terraform's `name` attribute - `aws_security_group.https_only` has no
  `Name` *tag* in the sample app config, unlike the instances, so this
  filters on `group-name` rather than `tag:Name`) `name`."
  [client name]
  (-> (aws/invoke client {:op :DescribeSecurityGroups :request {:Filters [{:Name "group-name" :Values [name]}]}})
      :SecurityGroups first :GroupId))

(defn- drift-sample-app-instance!
  "Directly changes the sample app's `aws_instance.workload_1`'s security
  groups (`ModifyInstanceAttribute`, same `InstanceId` - a genuine
  AWS-assigned id, so the same `:resource/id` per `sync/instance->attrs`)
  to just `aws_security_group.https_only` - standing in for a real
  out-of-band/drifted instance. (Deliberately not `aws_route`: a route has
  no AWS-assigned id of its own - `sync/route->attrs` synthesizes one from
  its route table + destination CIDR, a scheme independent of, and never
  matching, the Terraform AWS provider's own internal route id, so a
  Terraform-managed route can never be matched by Sync's id-based lookup
  in the first place, real drift-or-not - confirmed against a real
  LocalStack instance while developing this test. `aws_instance`'s `id` is
  a real AWS-assigned id on both sides, so it doesn't have this problem.)
  Returns `{:instance-id ... :new-security-group-id ...}` for assertions."
  [client]
  (let [instance-id (instance-id-by-name client "infratomic-test-app-workload-1")
        new-sg-id   (security-group-id-by-name client "infratomic-test-app-https-only")]
    (aws/invoke client {:op :ModifyInstanceAttribute
                         :request {:InstanceId instance-id :Groups [new-sg-id]}})
    {:instance-id instance-id :new-security-group-id new-sg-id}))

(deftest sync-updates-and-get-drift-flags-a-terraform-managed-resource-changed-out-of-band
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [client      (sync/ec2-client)
                app-handler (main/app-handler conn client)
                get-drift   (fn [] (json/parse-string
                                     (:body (app-handler {:request-method :get :uri "/drift"}))))]
            (testing "no drift before Sync has run"
              (is (= [] (get (get-drift) "drifted"))))

            (let [{:keys [instance-id new-security-group-id]} (drift-sample-app-instance! client)
                  summary1 (sync/sync! conn client)]

              (testing "the drifted instance is reported in the sync summary's :drifted bucket"
                (is (contains? (into #{} (map :id) (:drifted summary1)) instance-id)))

              (testing "the drifted instance's stored attributes and write source reflect the observed live value"
                (let [db     (d/db conn)
                      eid    (ffirst (d/q '[:find ?e :in $ ?id :where [?e :aws-instance/id ?id]] db instance-id))
                      pulled (d/pull db [:resource/last-write-source
                                          :aws-instance/vpc-security-group-id] eid)]
                  (is (= :sync (:resource/last-write-source pulled)))
                  (is (= [new-security-group-id] (:aws-instance/vpc-security-group-id pulled)))))

              (testing "GET /drift now includes the drifted instance"
                ;; GET /drift identifies resources by :resource/id (the
                ;; Terraform address, "aws_instance.workload_1"), matching
                ;; query.clj's existing Rule convention (resource-summary-
                ;; pattern) - unlike sync!'s summary, which uses the raw
                ;; AWS-assigned id since Discovered Resources have no
                ;; Terraform address at all.
                (is (contains? (into #{} (map #(get % "id")) (get (get-drift) "drifted")) "aws_instance.workload_1")))

              (let [summary2 (sync/sync! conn client)]
                (testing "re-running sync with no further out-of-band changes makes no additional writes"
                  (is (empty? (:drifted summary2)))
                  (let [db (d/db conn)]
                    (is (= 1 (count (d/q '[:find ?e :in $ ?id :where [?e :aws-instance/id ?id]] db instance-id))))))))

            (aws/stop client)))))))

;; ---------------------------------------------------------------------------
;; Removed-child drift: a previously-known managed child of each of the
;; four FK-bearing child types deleted out-of-band (issue #32) - the
;; :resource/sync-present? marker flips to false, the child's own entity
;; and attributes are otherwise untouched, and GET /state still reports it
;; unchanged (the mechanism's hard "never a live-state mutation"
;; constraint).
;;
;; Note (discovered developing this test, against a real LocalStack
;; instance): for `aws_security_group_rule` and `aws_route` specifically,
;; a Terraform-managed instance's *own* stored id never matches what Sync
;; observes (see the reappearance test below's comment for the full
;; explanation) - a pre-existing Sync-matching gap (#26/#27), not
;; introduced here. In practice this means `:resource/sync-present?`
;; ends up `false` for those two types' Terraform-managed children
;; regardless of whether they were actually deleted out-of-band - this
;; test's SG-rule/route assertions below hold either way (the marker
;; really is `false` after the revoke/delete), but aren't proof that the
;; mechanism is telling those two types' rules/routes apart from
;; "unmatched by Sync at all"; `aws_route_table_association` and
;; `aws_iam_role_policy_attachment` (both reliably id/composite-matched)
;; are the trustworthy cases here.
;;
;; The same unmatched-by-Sync root cause also means every Terraform-
;; managed SG rule/route gets (once, then perpetually re-matched)
;; misread as a *new* out-of-band child too, not just a removed one -
;; `query.clj`'s `new-children-by-parent` excludes both types entirely
;; for exactly this reason (`new-child-detection-gap-types`); see
;; `sync-does-not-flag-terraform-managed-sg-rules-or-routes-as-new-child-
;; drift` below for the regression test covering that half.
;; ---------------------------------------------------------------------------

(defn- subnet-id-by-name
  [client name]
  (-> (aws/invoke client {:op :DescribeSubnets :request {:Filters [{:Name "tag:Name" :Values [name]}]}})
      :Subnets first :SubnetId))

(defn- association-id-for-subnet
  [client rt-id subnet-id]
  (->> (-> (aws/invoke client {:op :DescribeRouteTables :request {:RouteTableIds [rt-id]}})
           :RouteTables first :Associations)
       (some #(when (= subnet-id (:SubnetId %)) (:RouteTableAssociationId %)))))

(defn- attached-policy-arn
  [client role-name]
  (-> (aws/invoke client {:op :ListAttachedRolePolicies :request {:RoleName role-name}})
      :AttachedPolicies first :PolicyArn))

(def ^:private removed-child-resource-ids
  ["aws_security_group_rule.https_only_ingress"
   "aws_route.rt_a_pcx"
   "aws_route_table_association.assoc_a2"
   "aws_iam_role_policy_attachment.iam_managed_policy"])

(deftest sync-flags-removed-child-drift-and-preserves-get-state
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [ec2-client   (sync/ec2-client)
                iam-client   (sync/iam-client)
                app-handler  (main/app-handler conn ec2-client)
                sg-id        (security-group-id-by-name ec2-client "infratomic-test-app-https-only")
                rt-a-id      (route-table-id-by-name ec2-client "infratomic-test-app-rt-a")
                subnet-a2-id (subnet-id-by-name ec2-client "infratomic-test-app-subnet-a2")
                assoc-id     (association-id-for-subnet ec2-client rt-a-id subnet-a2-id)
                role-name    "infratomic-test-app-iam-managed-policy-principal"
                policy-arn   (attached-policy-arn iam-client role-name)]
            (try
              ;; Remove one managed child of each of the four FK-bearing
              ;; child types directly against LocalStack, bypassing
              ;; Terraform entirely.
              (aws/invoke ec2-client {:op :RevokeSecurityGroupIngress
                                       :request {:GroupId sg-id
                                                 :IpPermissions [{:IpProtocol "tcp" :FromPort 443 :ToPort 443
                                                                   :IpRanges [{:CidrIp "0.0.0.0/0"}]}]}})
              (aws/invoke ec2-client {:op :DeleteRoute
                                       :request {:RouteTableId rt-a-id :DestinationCidrBlock "10.1.0.0/16"}})
              (aws/invoke ec2-client {:op :DisassociateRouteTable :request {:AssociationId assoc-id}})
              (aws/invoke iam-client {:op :DetachRolePolicy :request {:RoleName role-name :PolicyArn policy-arn}})

              (sync/sync! conn ec2-client)

              (let [db (d/db conn)]
                (testing "each removed child's :resource/sync-present? is now false"
                  (doseq [id removed-child-resource-ids]
                    (let [eid (ffirst (d/q '[:find ?e :in $ ?id :where [?e :resource/id ?id]] db id))]
                      (is (= false (:resource/sync-present? (d/pull db [:resource/sync-present?] eid)))
                          (str id " should be flagged sync-present? false")))))

                (testing "the removed SG rule's own entity/attributes are otherwise untouched"
                  (let [eid    (ffirst (d/q '[:find ?e :in $ ?id :where [?e :resource/id ?id]]
                                             db "aws_security_group_rule.https_only_ingress"))
                        pulled (d/pull db [:resource/managed?
                                            :aws-security-group-rule/from-port
                                            :aws-security-group-rule/to-port] eid)]
                    (is (= true (:resource/managed? pulled)))
                    (is (= 443 (:aws-security-group-rule/from-port pulled)))
                    (is (= 443 (:aws-security-group-rule/to-port pulled)))))

                (testing "GET /state still reports every removed child unchanged"
                  (let [resources (get (json/parse-string (:body (app-handler {:request-method :get :uri "/state"})))
                                        "resources")
                        addrs     (into #{} (map #(str (get % "type") "." (get % "name"))) resources)]
                    (doseq [id removed-child-resource-ids]
                      (is (contains? addrs id)))))

                (testing "GET /drift surfaces the removed children under their respective managed parents"
                  (let [drifted (get (json/parse-string (:body (query/drift-endpoint conn))) "drifted")
                        by-id   (into {} (map (fn [entry] [(get entry "id") entry])) drifted)]
                    (is (some #(= "aws_security_group_rule.https_only_ingress" (get % "id"))
                              (get (get by-id "aws_security_group.https_only") "removed_children")))
                    (is (some #(= "aws_route.rt_a_pcx" (get % "id"))
                              (get (get by-id "aws_route_table.rt_a") "removed_children")))
                    (is (some #(= "aws_route_table_association.assoc_a2" (get % "id"))
                              (get (get by-id "aws_route_table.rt_a") "removed_children")))
                    (is (some #(= "aws_iam_role_policy_attachment.iam_managed_policy" (get % "id"))
                              (get (get by-id "aws_iam_role.iam_managed_policy_principal") "removed_children"))))))
              (finally
                (aws/stop ec2-client)
                (aws/stop iam-client)))))))))

;; ---------------------------------------------------------------------------
;; Removed-child reappearance (issue #32): once a stale
;; :resource/sync-present? false marker exists (however it got there), the
;; next real Sync pass that still finds the resource present flips it back
;; to true - resource-tx's reappearance fix, exercised end-to-end against a
;; genuinely still-present LocalStack resource.
;;
;; Uses `aws_route_table_association.assoc_a2`, not a security group rule
;; or a route: confirmed against a real LocalStack instance while
;; developing this test, Terraform's own `id` for `aws_security_group_rule`
;; is its own synthetic hash (e.g. `\"sgrule-<n>\"`), distinct from the AWS
;; `SecurityGroupRuleId` Sync matches on (ADR-0006 names the latter, but
;; `resource-schema`'s `\"aws_security_group_rule\"` `\"id\"` entry is
;; written by *both* `POST /state` and Sync, so a Terraform-managed rule's
;; stored id is never the one Sync's own observations carry) - the same
;; already-documented gap `drift-sample-app-instance!` above notes for
;; `aws_route` (whose Sync-synthesized id never matches Terraform's own
;; route id either). Neither type's Terraform-managed instances can
;; actually be re-matched by Sync at all, so `:resource/sync-present?`
;; for them isn't a reliable reappearance signal today - a pre-existing
;; Sync-matching gap (#26/#27), out of this change's scope to fix.
;; `aws_route_table_association`'s id is a genuine, stable AWS-assigned
;; one that matches on both sides (confirmed against LocalStack), so it's
;; the reliable case to exercise this fix end-to-end.
;; ---------------------------------------------------------------------------

(deftest sync-clears-a-stale-removed-child-marker-once-the-child-still-present-is-resynced
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [ec2-client (sync/ec2-client)]
            (try
              (d/transact conn {:tx-data [[:db/add [:resource/id "aws_route_table_association.assoc_a2"]
                                            :resource/sync-present? false]]})
              (sync/sync! conn ec2-client)
              (let [db     (d/db conn)
                    eid    (ffirst (d/q '[:find ?e :in $ ?id :where [?e :resource/id ?id]]
                                         db "aws_route_table_association.assoc_a2"))
                    pulled (d/pull db [:resource/sync-present?] eid)]
                (is (= true (:resource/sync-present? pulled))))
              (finally
                (aws/stop ec2-client)))))))))

(deftest post-sync-endpoint-returns-a-summary-reflecting-real-localstack-resources
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [client       (sync/ec2-client)
                sync-handler (main/app-handler conn client)]
            (try
              (let [response (sync-handler {:request-method :post :uri "/sync"})
                    body     (json/parse-string (:body response))]
                (is (= 200 (:status response)))
                (testing "the response summary reflects real LocalStack resources"
                  (is (seq (get body "discovered")))
                  (is (every? #(and (contains? % "type") (contains? % "id")) (get body "discovered"))))
                (testing "the sample app's Terraform-managed security groups are reflected as skipped, not re-discovered"
                  (is (not (contains? (into #{} (map #(get % "id")) (get body "discovered"))
                                       "aws_security_group.ssh_open")))
                  (is (pos? (get body "skipped_already_managed")))))
              (finally
                (aws/stop client)))))))))
