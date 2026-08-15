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
;; New-child drift: an out-of-band security group rule / route /
;; route-table-association / IAM role policy attachment on a managed parent
;; (issue #32), all four FK-bearing child types. The whole-new-security-group
;; case (an entirely unmanaged parent) is already covered above
;; (`sync-discovers-out-of-band-resources-without-duplicating-or-touching-
;; managed-ones`); this exercises a hand-added child of an *already
;; Terraform-managed* parent for each type - `aws_security_group_rule`'s
;; case here is issue #32's own literal "How to verify" scenario (a
;; 0.0.0.0/0:22 rule hand-added to a security group that doesn't already
;; have port 22 open).
;;
;; `aws_security_group_rule`/`aws_route` were, until PR #36's round-2 fix, a
;; known Sync id-matching gap (`db/resource-composite-key`'s docstring,
;; ADR-0006) that made this undetectable for either type; both are now
;; composite-matched, so both are asserted as flagged below like every
;; other covered type.
;; ---------------------------------------------------------------------------

(defn- security-group-id-by-name
  "The `GroupId` of the security group named (its AWS `GroupName`, i.e.
  Terraform's `name` attribute - `aws_security_group.https_only` has no
  `Name` *tag* in the sample app config, unlike the instances, so this
  filters on `group-name` rather than `tag:Name`) `name`."
  [client name]
  (-> (aws/invoke client {:op :DescribeSecurityGroups :request {:Filters [{:Name "group-name" :Values [name]}]}})
      :SecurityGroups first :GroupId))

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
                sg-id       (security-group-id-by-name ec2-client "infratomic-test-app-https-only")
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
            (aws/invoke ec2-client {:op :AuthorizeSecurityGroupIngress
                                      :request {:GroupId sg-id
                                                :IpPermissions [{:IpProtocol "tcp" :FromPort 22 :ToPort 22
                                                                  :IpRanges [{:CidrIp "0.0.0.0/0"}]}]}})
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
                    sg-eid   (eid-by-aws-id db :aws-security-group/id sg-id)
                    rt-a-eid (eid-by-aws-id db :aws-route-table/id rt-a-id)
                    rt-c-eid (eid-by-aws-id db :aws-route-table/id rt-c-id)
                    role-eid (ffirst (d/q '[:find ?e :in $ ?name :where [?e :aws-iam-role/name ?name]] db role-name))
                    new-children (query/new-children-by-parent db)]
                (testing "the hand-added ingress rule (issue #32's own verification scenario) is a new child of the managed security group"
                  (is (some #(= "aws_security_group_rule" (:resource/type %)) (get new-children sg-eid))))
                (testing "the hand-added route is a new child of the managed route table"
                  (is (some #(= "aws_route" (:resource/type %)) (get new-children rt-a-eid))))
                (testing "the hand-added association is a new child of the managed route table (unmanaged subnet side)"
                  (is (some #(= "aws_route_table_association" (:resource/type %)) (get new-children rt-c-eid))))
                (testing "the hand-attached policy is a new child of the managed IAM role"
                  (is (some #(= "aws_iam_role_policy_attachment" (:resource/type %)) (get new-children role-eid)))))
              ;; Round-4 review Finding 1 regression: revert every out-of-band
              ;; change and confirm a further Sync pass clears the new-child
              ;; drift signal instead of reporting it forever -
              ;; `new-children-by-parent`'s own `:resource/sync-present?`
              ;; filtering, backed by `missing-child-tx` now also covering
              ;; Discovered resources of a covered type
              ;; (`db/resource-ids-by-type`, no longer managed-only).
              (aws/invoke ec2-client {:op :RevokeSecurityGroupIngress
                                        :request {:GroupId sg-id
                                                  :IpPermissions [{:IpProtocol "tcp" :FromPort 22 :ToPort 22
                                                                    :IpRanges [{:CidrIp "0.0.0.0/0"}]}]}})
              (aws/invoke iam-client {:op :DetachRolePolicy
                                        :request {:RoleName role-name
                                                  :PolicyArn "arn:aws:iam::aws:policy/ReadOnlyAccess"}})
              (aws/invoke ec2-client {:op :DeleteRoute
                                        :request {:RouteTableId rt-a-id :DestinationCidrBlock "198.51.100.0/24"}})
              (aws/invoke ec2-client {:op :DisassociateRouteTable :request {:AssociationId assoc-id}})
              (let [_        (sync/sync! conn ec2-client)
                    db       (d/db conn)
                    sg-eid   (eid-by-aws-id db :aws-security-group/id sg-id)
                    rt-a-eid (eid-by-aws-id db :aws-route-table/id rt-a-id)
                    rt-c-eid (eid-by-aws-id db :aws-route-table/id rt-c-id)
                    role-eid (ffirst (d/q '[:find ?e :in $ ?name :where [?e :aws-iam-role/name ?name]] db role-name))
                    new-children (query/new-children-by-parent db)]
                (testing "the reverted ingress rule no longer flags the security group as having a new child"
                  (is (not (some #(= "aws_security_group_rule" (:resource/type %)) (get new-children sg-eid)))))
                (testing "the reverted route no longer flags the route table as having a new child"
                  (is (not (some #(= "aws_route" (:resource/type %)) (get new-children rt-a-eid)))))
                (testing "the reverted association no longer flags the route table as having a new child"
                  (is (not (some #(= "aws_route_table_association" (:resource/type %)) (get new-children rt-c-eid)))))
                (testing "the reverted policy attachment no longer flags the role as having a new child"
                  (is (not (some #(= "aws_iam_role_policy_attachment" (:resource/type %)) (get new-children role-eid))))))
              (finally
                (aws/invoke ec2-client {:op :DeleteSubnet :request {:SubnetId subnet-id}})
                (aws/stop ec2-client)
                (aws/stop iam-client)))))))))

;; ---------------------------------------------------------------------------
;; Code-review regression (issue #32 PR #36, Finding 1 and its round-2 twin):
;; a freshly-applied, never-touched sample app - real
;; `aws_security_group_rule`/`aws_route` instances included - must report
;; zero new-child *and* zero removed-child drift after a single `sync!`
;; pass, with no out-of-band change having happened at all. Before the
;; round-2 fix, the `aws_security_group_rule`/`aws_route` id-matching gap
;; (`db/resource-composite-key`'s docstring) made `resource-tx` treat every
;; one of the sample app's own already-managed rules/routes as unmatched on
;; the new-child side (a permanent Discovered-Resource duplicate
;; `new-children-by-parent` misread as new-child drift on the real,
;; unmodified managed parent), and made `missing-child-tx` mark every one
;; `:resource/sync-present? false` on the removed-child side regardless of
;; whether it was actually removed - both violating the drift-detection
;; spec's "A Terraform-managed child is not flagged as new-child drift"
;; scenario (and its removed-child analogue).
;; ---------------------------------------------------------------------------

(deftest sync-does-not-flag-terraform-managed-sg-rules-or-routes-as-new-or-removed-child-drift
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [ec2-client (sync/ec2-client)
                covered-ids ["aws_security_group_rule.ssh_open_ingress"
                             "aws_security_group_rule.https_only_ingress"
                             "aws_security_group_rule.reachability_open_egress"
                             "aws_security_group_rule.reachability_open_ingress"
                             "aws_security_group_rule.reachability_restricted_egress"
                             "aws_route.rt_a_igw" "aws_route.rt_a_pcx" "aws_route.rt_b_pcx"]]
            (try
              (sync/sync! conn ec2-client)
              (let [db               (d/db conn)
                    new-children     (query/new-children-by-parent db)
                    removed-children (query/removed-children-by-parent db)
                    flagged-new      (into #{}
                                            (mapcat (fn [children] (map :resource/id children)))
                                            (vals new-children))
                    flagged-removed  (into #{}
                                            (mapcat (fn [children] (map :resource/id children)))
                                            (vals removed-children))]
                (testing "no Terraform-managed security group rule/route is flagged as new-child drift on its parent"
                  (doseq [id covered-ids]
                    (is (not (contains? flagged-new id)) (str id " should not be flagged as new-child drift"))))
                (testing "no Terraform-managed security group rule/route is flagged as removed-child drift on its parent"
                  (doseq [id covered-ids]
                    (is (not (contains? flagged-removed id)) (str id " should not be flagged as removed-child drift"))))
                (testing "each covered resource's :resource/sync-present? is never false"
                  ;; Not asserting `true` here: a genuinely unmatched
                  ;; *change* on this first-ever sync pass would force a
                  ;; write (`resource-tx`'s reappearance-or-drift paths),
                  ;; but an unchanged, already-matched resource whose
                  ;; marker was never previously `false` takes
                  ;; `resource-tx`'s no-write `:skipped-already-managed`
                  ;; path (see that fn's docstring) - so absent (not yet
                  ;; explicitly asserted `true`) is an equally valid
                  ;; "not removed" outcome here, matching
                  ;; `removed-children-by-parent-does-not-flag-a-present-
                  ;; or-not-yet-checked-child` (query_test.clj)'s
                  ;; established absent-is-fine semantics. `false` is the
                  ;; only value that would mean this fix didn't work.
                  (doseq [id covered-ids]
                    (let [eid (ffirst (d/q '[:find ?e :in $ ?id :where [?e :resource/id ?id]] db id))]
                      (is (not (false? (:resource/sync-present? (d/pull db [:resource/sync-present?] eid))))
                          (str id " should not be sync-present? false")))))
                (testing "GET /drift reports no new_children/removed_children for any managed security group/route table"
                  (let [drifted (get (json/parse-string (:body (query/drift-endpoint conn))) "drifted")]
                    (doseq [entry drifted]
                      (when (contains? #{"aws_security_group" "aws_route_table"} (get entry "type"))
                        (is (not (contains? entry "new_children"))
                            (str (get entry "id") " should have no new_children"))
                        (is (not (contains? entry "removed_children"))
                            (str (get entry "id") " should have no removed_children")))))))
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

(defn- drift-sample-app-instance!
  "Directly changes the sample app's `aws_instance.workload_1`'s security
  groups (`ModifyInstanceAttribute`, same `InstanceId` - a genuine
  AWS-assigned id, so the same `:resource/id` per `sync/instance->attrs`)
  to just `aws_security_group.https_only` - standing in for a real
  out-of-band/drifted instance, exercising the plain attribute-level drift
  path (issue #27). Returns `{:instance-id ... :new-security-group-id
  ...}` for assertions."
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
;; Composite-matched attribute drift for aws_route without touching
;; Terraform's own id (issue #32 PR #36 round-2 fix): `ReplaceRoute` changes
;; the sample app's `aws_route.rt_a_igw` route's target (gateway_id) while
;; keeping its `(route_table_id, destination_cidr_block)` match key
;; unchanged - a real out-of-band drift Sync can now actually match
;; (composite key, `db/resource-composite-key`) and report, without ever
;; overwriting the `"id"` Terraform itself last asserted for it
;; (`db/id-space-mismatched-types`, `sync.clj`'s `writable-attributes`) -
;; `GET /state` must keep reporting exactly that id, unchanged, to
;; Terraform even after the drifted write.
;; ---------------------------------------------------------------------------

(deftest sync-flags-a-drifted-route-without-overwriting-terraforms-own-id
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [ec2-client  (sync/ec2-client)
                app-handler (main/app-handler conn ec2-client)
                state       (fn [] (json/parse-string (:body (app-handler {:request-method :get :uri "/state"}))))
                route-attrs (fn []
                              (let [resource (some #(when (and (= "aws_route" (get % "type"))
                                                                (= "rt_a_igw" (get % "name")))
                                                       %)
                                                    (get (state) "resources"))]
                                (get (first (get resource "instances")) "attributes")))
                original-attrs (route-attrs)
                original-id    (get original-attrs "id")
                rt-a-id        (route-table-id-by-name ec2-client "infratomic-test-app-rt-a")
                vpc-a-id       (-> (aws/invoke ec2-client {:op :DescribeRouteTables :request {:RouteTableIds [rt-a-id]}})
                                    :RouteTables first :VpcId)
                new-igw-id     (-> (aws/invoke ec2-client {:op :CreateInternetGateway}) :InternetGateway :InternetGatewayId)]
            (aws/invoke ec2-client {:op :AttachInternetGateway :request {:InternetGatewayId new-igw-id :VpcId vpc-a-id}})
            (aws/invoke ec2-client {:op :ReplaceRoute
                                      :request {:RouteTableId rt-a-id :DestinationCidrBlock "0.0.0.0/0"
                                                :GatewayId new-igw-id}})
            (try
              (is (some? original-id) "the route must already have a Terraform-asserted id before the drift")
              (let [summary (sync/sync! conn ec2-client)]
                (testing "the drifted route is reported in the sync summary's :drifted bucket"
                  (is (contains? (into #{} (map :id) (:drifted summary)) (str rt-a-id "-0.0.0.0/0"))))
                (testing "the drifted route's stored gateway_id reflects the observed live value"
                  (let [db     (d/db conn)
                        eid    (ffirst (d/q '[:find ?e :in $ ?id :where [?e :resource/id ?id]] db "aws_route.rt_a_igw"))
                        pulled (d/pull db [:aws-route/gateway-id :resource/last-write-source] eid)]
                    (is (= new-igw-id (:aws-route/gateway-id pulled)))
                    (is (= :sync (:resource/last-write-source pulled)))))
                (testing "GET /state still reports exactly the id Terraform itself last asserted, unchanged"
                  (is (= original-id (get (route-attrs) "id")))))
              (finally
                (aws/invoke ec2-client {:op :ReplaceRoute
                                          :request {:RouteTableId rt-a-id :DestinationCidrBlock "0.0.0.0/0"
                                                    :GatewayId (get original-attrs "gateway_id")}})
                (aws/invoke ec2-client {:op :DetachInternetGateway :request {:InternetGatewayId new-igw-id :VpcId vpc-a-id}})
                (aws/invoke ec2-client {:op :DeleteInternetGateway :request {:InternetGatewayId new-igw-id}})
                (aws/stop ec2-client)))))))))

;; ---------------------------------------------------------------------------
;; Removed-child drift: a previously-known managed child of each of the
;; four FK-bearing child types deleted out-of-band (issue #32) - the
;; :resource/sync-present? marker flips to false, the child's own entity
;; and attributes are otherwise untouched, and GET /state still reports it
;; unchanged (the mechanism's hard "never a live-state mutation"
;; constraint).
;;
;; All four covered types, `aws_security_group_rule`/`aws_route` included,
;; are now reliably matched by Sync (composite key -
;; `db/resource-composite-key`, ADR-0006/issue #32 PR #36's round-2 fix), so
;; `:resource/sync-present? false` genuinely means "this pass didn't
;; observe it" for every type here, not an artifact of an id-matching gap -
;; see `sync-does-not-flag-terraform-managed-sg-rules-or-routes-as-new-or-
;; removed-child-drift` above for the complementary "nothing removed"
;; regression test.
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
;; genuinely still-present LocalStack resource. `aws_route_table_association`
;; exercises the id-based match path; `aws_security_group_rule` (below)
;; exercises the composite-match path (issue #32 PR #36's round-2 fix) -
;; before that fix, a Terraform-managed rule's own stored id never matched
;; what Sync observes, so this reappearance signal wasn't reliable for it.
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

(deftest sync-clears-a-stale-removed-child-marker-for-a-composite-matched-sg-rule
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [ec2-client (sync/ec2-client)]
            (try
              (d/transact conn {:tx-data [[:db/add [:resource/id "aws_security_group_rule.ssh_open_ingress"]
                                            :resource/sync-present? false]]})
              (sync/sync! conn ec2-client)
              (let [db     (d/db conn)
                    eid    (ffirst (d/q '[:find ?e :in $ ?id :where [?e :resource/id ?id]]
                                         db "aws_security_group_rule.ssh_open_ingress"))
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
