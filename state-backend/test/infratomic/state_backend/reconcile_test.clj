(ns infratomic.state-backend.reconcile-test
  "Hermetic unit tests for `reconcile.clj`'s decision/dispatch logic (issue
  #34), exercised against an in-memory Datomic dev-local database -
  mirroring `policy_test.clj`/`query_test.clj`'s fixture pattern.
  `terraform.clj`'s `apply!`/`synthesize-import-and-destroy!` are stubbed
  via `with-redefs` (each still writing a real Invocation entity, exactly
  like the real fn would - `with-lock-and-invocation`'s own contract) so
  these tests never shell out to a real `terraform` binary. Real
  end-to-end remediation against LocalStack is covered separately by
  `sync_integration_test.clj`."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.policy :as policy]
            [infratomic.state-backend.reconcile :as reconcile]
            [infratomic.state-backend.sync :as sync]
            [infratomic.state-backend.terraform :as terraform]))

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

(defn- with-cleanup
  "Run `f` (a 0-arg thunk registering a Rule under `rule-id`), then remove
  `rule-id` from `policy/rule-registry` afterward regardless of outcome -
  mirrors `policy_test.clj`'s own helper, redefined locally since that
  one is private."
  [rule-id f]
  (try
    (f)
    (finally
      (swap! policy/rule-registry dissoc rule-id))))

(defn- stub-apply!
  "Stands in for `terraform/apply!`: writes a real Invocation entity (so
  `reconcile.clj`'s Invocation-lookup-after-the-call logic is genuinely
  exercised), without shelling out to a real `terraform` binary."
  [conn _working-dir address]
  (d/transact conn {:tx-data [{:invocation/command          :apply
                                :invocation/resource-address address
                                :invocation/success?         true
                                :invocation/at               (java.util.Date.)}]})
  {:success true :out "" :err ""})

(defn- stub-synthesize!
  [conn address _type _attributes]
  (d/transact conn {:tx-data [{:invocation/command          :import-destroy
                                :invocation/resource-address address
                                :invocation/success?         true
                                :invocation/at               (java.util.Date.)}]})
  {:success true :out "" :err ""})

(defn- reconciliation-records
  [db]
  (map first
       (d/q '[:find (pull ?e [:reconciliation/rule :reconciliation/action
                               {:reconciliation/resource [:resource/id]}
                               {:reconciliation/invocation [:invocation/command]}])
              :where [?e :reconciliation/rule]]
            db)))

(defn- records-for
  [db resource-id]
  (filter #(= resource-id (get-in % [:reconciliation/resource :resource/id])) (reconciliation-records db)))

;; ---------------------------------------------------------------------------
;; Dispatch (tasks.md 4.4/4.5): managed+drifted -> apply!, unmanaged ->
;; synthesize-import-and-destroy!, managed+non-drifted -> no action -
;; against a Rule with no child-resolvers entry (a plain aws_vpc Rule), so
;; each resolved target is exactly the Rule's own bound resource.
;; ---------------------------------------------------------------------------

(def ^:private any-vpc-rule
  {:rule/id    ::any-vpc
   :rule/find  '[?e]
   :rule/in    '[$]
   :rule/where '[[?e :aws-vpc/cidr-block _]]})

(deftest reconcile-remediates-an-unmanaged-violating-resource-via-synthesized-import-and-destroy
  (with-cleanup (:rule/id any-vpc-rule)
    (fn []
      (policy/register-rule! any-vpc-rule)
      (let [conn (fresh-conn)
            db0  (d/db conn)
            {:keys [tx-data]} (sync/resource-tx db0 "aws_vpc" "vpc-999" {"id" "vpc-999" "cidr_block" "10.0.0.0/16"})]
        (d/transact conn {:tx-data tx-data})
        (with-redefs [terraform/synthesize-import-and-destroy! stub-synthesize!]
          (reconcile/reconcile! conn))
        (let [records (records-for (d/db conn) "aws_vpc.discovered-vpc-999")]
          (testing "exactly one reconciliation record, action :import-destroy"
            (is (= 1 (count records)))
            (is (= :reconciliation.action/import-destroy (:reconciliation/action (first records)))))
          (testing "it references the Invocation the synthesized import+destroy produced"
            (is (= :import-destroy (get-in (first records) [:reconciliation/invocation :invocation/command])))))))))

(deftest reconcile-remediates-a-managed-drifted-violating-resource-via-apply
  (with-cleanup (:rule/id any-vpc-rule)
    (fn []
      (policy/register-rule! any-vpc-rule)
      (let [conn (fresh-conn)]
        (handler/post-state conn (state-body [(resource "aws_vpc" "main" {"id" "vpc-1" "cidr_block" "10.1.0.0/16"})]))
        (let [db0 (d/db conn)
              {:keys [tx-data outcome]} (sync/resource-tx db0 "aws_vpc" "vpc-1" {"id" "vpc-1" "cidr_block" "10.2.0.0/16"})]
          (is (= :drifted outcome))
          (d/transact conn {:tx-data tx-data}))
        (with-redefs [terraform/apply! stub-apply!]
          (reconcile/reconcile! conn))
        (let [records (records-for (d/db conn) "aws_vpc.main")]
          (testing "exactly one reconciliation record, action :apply"
            (is (= 1 (count records)))
            (is (= :reconciliation.action/apply (:reconciliation/action (first records)))))
          (testing "it references the Invocation the apply produced"
            (is (= :apply (get-in (first records) [:reconciliation/invocation :invocation/command])))))))))

(deftest reconcile-records-only-for-a-managed-non-drifted-violating-resource
  (with-cleanup (:rule/id any-vpc-rule)
    (fn []
      (policy/register-rule! any-vpc-rule)
      (let [conn (fresh-conn)]
        (handler/post-state conn (state-body [(resource "aws_vpc" "main" {"id" "vpc-2" "cidr_block" "10.3.0.0/16"})]))
        (with-redefs [terraform/apply!                        (fn [& _] (throw (ex-info "must not run apply!" {})))
                      terraform/synthesize-import-and-destroy! (fn [& _] (throw (ex-info "must not run synthesize" {})))]
          (reconcile/reconcile! conn))
        (let [records (records-for (d/db conn) "aws_vpc.main")]
          (testing "exactly one reconciliation record, action :none, no Invocation reference"
            (is (= 1 (count records)))
            (is (= :reconciliation.action/none (:reconciliation/action (first records))))
            (is (nil? (:reconciliation/invocation (first records))))))))))

(deftest reconcile-takes-no-action-and-no-record-for-a-non-violating-resource
  (with-cleanup (:rule/id any-vpc-rule)
    (fn []
      (policy/register-rule! any-vpc-rule)
      (let [conn (fresh-conn)]
        ;; An aws_s3_bucket never matches any-vpc-rule (no :aws-vpc/cidr-block).
        (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads" {"arn" "arn:aws:s3:::uploads"})]))
        (with-redefs [terraform/apply!                        (fn [& _] (throw (ex-info "must not run apply!" {})))
                      terraform/synthesize-import-and-destroy! (fn [& _] (throw (ex-info "must not run synthesize" {})))]
          (reconcile/reconcile! conn))
        (is (empty? (reconciliation-records (d/db conn))))))))

;; ---------------------------------------------------------------------------
;; Live-state evaluation (tasks.md 4.2): reconcile! flags a non-drifted
;; managed resource that violates a Rule registered after it was
;; deployed - never a plan-derived speculative db, unlike Policy Check's
;; evaluate, which has nothing to evaluate here at all (no plan is ever
;; submitted).
;; ---------------------------------------------------------------------------

(deftest reconcile-flags-a-non-drifted-managed-resource-violating-a-rule-registered-after-deploy
  (let [rule-id (keyword (str "test-rule-" (random-uuid)))]
    (with-cleanup rule-id
      (fn []
        (let [conn (fresh-conn)]
          (handler/post-state conn (state-body [(resource "aws_s3_bucket" "uploads" {"arn" "arn:aws:s3:::uploads"})]))
          ;; Registered only now, after the resource was already deployed
          ;; and never drifted.
          (policy/register-rule! {:rule/id    rule-id
                                   :rule/find  '[?e]
                                   :rule/in    '[$]
                                   :rule/where '[[?e :aws-s3-bucket/arn ?arn]]})
          (with-redefs [terraform/apply!                        (fn [& _] (throw (ex-info "must not run apply!" {})))
                        terraform/synthesize-import-and-destroy! (fn [& _] (throw (ex-info "must not run synthesize" {})))]
            (reconcile/reconcile! conn))
          (let [records (records-for (d/db conn) "aws_s3_bucket.uploads")]
            (is (= 1 (count records)))
            (is (= rule-id (:reconciliation/rule (first records))))
            (is (= :reconciliation.action/none (:reconciliation/action (first records))))))))))

;; ---------------------------------------------------------------------------
;; Child resolution (tasks.md 4.3): a parent-binding Rule (the built-in
;; security-groups-with-port-22-open Rule) has its violation resolved to
;; the specific offending child rule entity, never the parent security
;; group itself - New-Child Drift, an out-of-band ingress rule on an
;; already-managed security group.
;; ---------------------------------------------------------------------------

(deftest reconcile-resolves-a-parent-binding-rules-violation-to-its-offending-child
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body [(resource "aws_security_group" "ssh_open" {"id" "sg-1"})]))
    (let [db0 (d/db conn)
          {:keys [tx-data]} (sync/resource-tx db0 "aws_security_group_rule" "sgr-1"
                                               {"id" "sgr-1" "security_group_id" "sg-1"
                                                "type" "ingress" "protocol" "tcp"
                                                "from_port" 22 "to_port" 22
                                                "cidr_blocks" ["0.0.0.0/0"]})]
      (d/transact conn {:tx-data tx-data}))
    (with-redefs [terraform/synthesize-import-and-destroy! stub-synthesize!
                  terraform/apply!                         (fn [& _] (throw (ex-info "must not apply the parent SG" {})))]
      (reconcile/reconcile! conn))
    (let [db (d/db conn)]
      (testing "the offending child rule got a reconciliation record, not the parent security group"
        (let [child-records  (records-for db "aws_security_group_rule.discovered-sgr-1")
              parent-records (records-for db "aws_security_group.ssh_open")]
          (is (= 1 (count child-records)))
          (is (= :reconciliation.action/import-destroy (:reconciliation/action (first child-records))))
          (is (= :security-groups-with-port-22-open (:reconciliation/rule (first child-records))))
          (is (empty? parent-records)))))))
