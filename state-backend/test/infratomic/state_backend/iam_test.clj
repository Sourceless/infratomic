(ns infratomic.state-backend.iam-test
  "Unit tests for `infratomic.state-backend.iam`'s `iam-reachable?`,
  exercised against an in-memory (non-persistent) Datomic dev-local
  database seeded via the handler's `POST` path - mirroring
  `query_test.clj`'s own fixture pattern - plus direct unit tests of
  `glob-matches?` (per tasks.md 4.5, not just exercised indirectly via the
  `grants` rule)."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.iam :as iam]))

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

(defn- db-with-resources
  [resources]
  (let [conn (fresh-conn)]
    (handler/post-state conn (state-body resources))
    (d/db conn)))

;; ---------------------------------------------------------------------------
;; Policy JSON document builders
;; ---------------------------------------------------------------------------

(defn- stmt
  "Build one policy statement map (Terraform/AWS's raw JSON shape).
  `resources`/`principal` are omitted when absent, matching a real trust
  policy statement (no `Resource` key) or an identity-based policy
  statement (no `Principal` key)."
  [{:keys [effect actions resources principal]}]
  (cond-> {"Effect" effect "Action" actions}
    resources (assoc "Resource" resources)
    principal (assoc "Principal" principal)))

(defn- policy-doc
  [& statements]
  (json/generate-string {"Version" "2012-10-17" "Statement" (vec statements)}))

(defn- aws-principal
  [arn]
  {"AWS" arn})

;; ---------------------------------------------------------------------------
;; glob-matches? - direct unit tests (tasks.md 4.5)
;; ---------------------------------------------------------------------------

(deftest glob-matches?-wildcard-and-literal-cases
  (testing "trailing wildcard"
    (is (iam/glob-matches? "s3:Get*" "s3:GetObject"))
    (is (not (iam/glob-matches? "s3:Get*" "s3:PutObject"))))
  (testing "wildcard within an ARN path"
    (is (iam/glob-matches? "arn:aws:s3:::my-bucket/*" "arn:aws:s3:::my-bucket/key.txt"))
    (is (not (iam/glob-matches? "arn:aws:s3:::my-bucket/*" "arn:aws:s3:::other-bucket/key.txt"))))
  (testing "single-character wildcard"
    (is (iam/glob-matches? "s3:GetObjec?" "s3:GetObject"))
    (is (not (iam/glob-matches? "s3:GetObjec?" "s3:GetObjectt"))))
  (testing "literal equality, no metacharacters"
    (is (iam/glob-matches? "sts:AssumeRole" "sts:AssumeRole"))
    (is (not (iam/glob-matches? "sts:AssumeRole" "sts:AssumeRoleWithSAML"))))
  (testing "regex metacharacters in the literal portion are escaped, not interpreted"
    (is (iam/glob-matches? "a.b" "a.b"))
    (is (not (iam/glob-matches? "a.b" "aXb")))))

;; ---------------------------------------------------------------------------
;; Direct identity-based policy (tasks.md 6.1)
;; ---------------------------------------------------------------------------

(def ^:private direct-access-resources
  [(resource "aws_iam_role" "role_direct"
             {"id" "role-direct" "name" "role-direct" "arn" "arn:aws:iam::000000000000:role/role-direct"})
   (resource "aws_s3_bucket" "bucket_direct"
             {"bucket" "bucket-direct" "arn" "arn:aws:s3:::bucket-direct"})
   (resource "aws_iam_role_policy" "role_direct_policy"
             {"role"   "role-direct"
              ;; Resource pattern matches the bucket's own ARN exactly -
              ;; `iam-reachable?` resolves `?resource` to the bucket
              ;; resource's own ARN, not a per-object ARN (there's no
              ;; per-object entity in this system's model).
              "policy" (policy-doc (stmt {:effect "Allow" :actions ["s3:GetObject"]
                                           :resources ["arn:aws:s3:::bucket-direct"]}))})])

(deftest iam-reachable?-direct-identity-based-policy
  (let [db (db-with-resources direct-access-resources)]
    (is (iam/iam-reachable? db "aws_iam_role.role_direct" "aws_s3_bucket.bucket_direct" "s3:GetObject"))))

;; ---------------------------------------------------------------------------
;; Resource-based policy (tasks.md 6.2)
;; ---------------------------------------------------------------------------

(def ^:private resource-based-access-resources
  [(resource "aws_iam_role" "role_resource"
             {"id" "role-resource" "name" "role-resource" "arn" "arn:aws:iam::000000000000:role/role-resource"})
   (resource "aws_s3_bucket" "bucket_resource"
             {"bucket" "bucket-resource" "arn" "arn:aws:s3:::bucket-resource"})
   (resource "aws_s3_bucket_policy" "bucket_resource_policy"
             {"bucket" "bucket-resource"
              "policy" (policy-doc (stmt {:effect "Allow" :actions ["s3:GetObject"]
                                           :resources ["arn:aws:s3:::bucket-resource"]
                                           :principal (aws-principal "arn:aws:iam::000000000000:role/role-resource")}))})])

(deftest iam-reachable?-resource-based-policy
  (let [db (db-with-resources resource-based-access-resources)]
    (is (iam/iam-reachable? db "aws_iam_role.role_resource" "aws_s3_bucket.bucket_resource" "s3:GetObject"))))

;; ---------------------------------------------------------------------------
;; No granting policy anywhere (tasks.md 6.5)
;; ---------------------------------------------------------------------------

(def ^:private isolated-resources
  [(resource "aws_iam_role" "role_isolated"
             {"id" "role-isolated" "name" "role-isolated" "arn" "arn:aws:iam::000000000000:role/role-isolated"})
   (resource "aws_s3_bucket" "bucket_isolated"
             {"bucket" "bucket-isolated" "arn" "arn:aws:s3:::bucket-isolated"})])

(deftest iam-reachable?-no-granting-policy-anywhere
  (let [db (db-with-resources isolated-resources)]
    (is (not (iam/iam-reachable? db "aws_iam_role.role_isolated" "aws_s3_bucket.bucket_isolated" "s3:GetObject")))))

;; ---------------------------------------------------------------------------
;; Role-assumption chain (tasks.md 6.3, 6.4) - source -> mid -> target,
;; target has its own grant on a bucket. Two assumption hops
;; (source->mid, mid->target) so the positive case exercises genuine
;; multi-hop recursion, not a single hard-coded hop.
;; ---------------------------------------------------------------------------

(defn- chain-resources
  "The source->mid->target role-assumption chain fixture. When
  `include-mid-trust?` is false, `role_chain_mid`'s trust policy omits the
  statement granting `role_chain_source` `sts:AssumeRole`, breaking the
  chain at that edge - a separate fixture-building call, not a mutation."
  [include-mid-trust?]
  (let [source-arn "arn:aws:iam::000000000000:role/role-chain-source"
        mid-arn    "arn:aws:iam::000000000000:role/role-chain-mid"
        target-arn "arn:aws:iam::000000000000:role/role-chain-target"
        bucket-arn "arn:aws:s3:::bucket-chain-target"
        mid-trust-statements (cond-> [(stmt {:effect "Allow" :actions ["sts:AssumeRole"] :principal (aws-principal "arn:aws:iam::000000000000:role/lambda-placeholder")})]
                               include-mid-trust? (conj (stmt {:effect "Allow" :actions ["sts:AssumeRole"] :principal (aws-principal source-arn)})))]
    [(resource "aws_iam_role" "role_chain_source" {"id" "role-chain-source" "name" "role-chain-source" "arn" source-arn})
     (resource "aws_iam_role" "role_chain_mid"
               {"id" "role-chain-mid" "name" "role-chain-mid" "arn" mid-arn
                "assume_role_policy" (apply policy-doc mid-trust-statements)})
     (resource "aws_iam_role" "role_chain_target"
               {"id" "role-chain-target" "name" "role-chain-target" "arn" target-arn
                "assume_role_policy" (policy-doc (stmt {:effect "Allow" :actions ["sts:AssumeRole"]
                                                         :principal (aws-principal mid-arn)}))})
     (resource "aws_s3_bucket" "bucket_chain_target" {"bucket" "bucket-chain-target" "arn" bucket-arn})

     (resource "aws_iam_role_policy" "role_chain_source_policy"
               {"role"   "role-chain-source"
                "policy" (policy-doc (stmt {:effect "Allow" :actions ["sts:AssumeRole"] :resources [mid-arn]}))})
     (resource "aws_iam_role_policy" "role_chain_mid_policy"
               {"role"   "role-chain-mid"
                "policy" (policy-doc (stmt {:effect "Allow" :actions ["sts:AssumeRole"] :resources [target-arn]}))})
     (resource "aws_iam_role_policy" "role_chain_target_policy"
               {"role"   "role-chain-target"
                "policy" (policy-doc (stmt {:effect "Allow" :actions ["s3:GetObject"] :resources [bucket-arn]}))})]))

(deftest iam-reachable?-multi-hop-role-assumption-chain
  (let [db (db-with-resources (chain-resources true))]
    (is (iam/iam-reachable? db "aws_iam_role.role_chain_source" "aws_s3_bucket.bucket_chain_target" "s3:GetObject"))))

(deftest iam-reachable?-broken-role-assumption-chain
  (let [db (db-with-resources (chain-resources false))]
    (is (not (iam/iam-reachable? db "aws_iam_role.role_chain_source" "aws_s3_bucket.bucket_chain_target" "s3:GetObject")))))

;; ---------------------------------------------------------------------------
;; Deny overrides allow (tasks.md 6.6)
;; ---------------------------------------------------------------------------

(def ^:private deny-resources
  [(resource "aws_iam_role" "role_deny"
             {"id" "role-deny" "name" "role-deny" "arn" "arn:aws:iam::000000000000:role/role-deny"})
   (resource "aws_s3_bucket" "bucket_deny"
             {"bucket" "bucket-deny" "arn" "arn:aws:s3:::bucket-deny"})
   (resource "aws_iam_role_policy" "role_deny_policy"
             {"role" "role-deny"
              "policy" (policy-doc
                        ;; Matching deny: same action/resource as this
                        ;; Allow - blocks.
                        (stmt {:effect "Allow" :actions ["s3:GetObject"] :resources ["arn:aws:s3:::bucket-deny"]})
                        (stmt {:effect "Deny" :actions ["s3:GetObject"] :resources ["arn:aws:s3:::bucket-deny"]})
                        ;; Non-matching deny: different action - doesn't
                        ;; block this unrelated Allow.
                        (stmt {:effect "Allow" :actions ["s3:PutObject"] :resources ["arn:aws:s3:::bucket-deny"]})
                        (stmt {:effect "Deny" :actions ["s3:DeleteObject"] :resources ["arn:aws:s3:::bucket-deny"]}))})])

(deftest iam-reachable?-matching-deny-blocks-otherwise-granted-access
  (let [db (db-with-resources deny-resources)]
    (is (not (iam/iam-reachable? db "aws_iam_role.role_deny" "aws_s3_bucket.bucket_deny" "s3:GetObject")))))

(deftest iam-reachable?-non-matching-deny-does-not-block-unrelated-access
  (let [db (db-with-resources deny-resources)]
    (is (iam/iam-reachable? db "aws_iam_role.role_deny" "aws_s3_bucket.bucket_deny" "s3:PutObject"))))

;; ---------------------------------------------------------------------------
;; Glob matching via iam-reachable? (tasks.md 6.7) - distinct from the
;; direct glob-matches? unit tests above and from every fixture-level test,
;; whose statements above use literal (non-wildcard) actions/resources.
;; ---------------------------------------------------------------------------

(def ^:private glob-resources
  [(resource "aws_iam_role" "role_glob"
             {"id" "role-glob" "name" "role-glob" "arn" "arn:aws:iam::000000000000:role/role-glob"})
   (resource "aws_s3_bucket" "bucket_glob"
             {"bucket" "bucket-glob" "arn" "arn:aws:s3:::bucket-glob"})
   (resource "aws_iam_role_policy" "role_glob_policy"
             {"role"   "role-glob"
              ;; Both a wildcard Action (`s3:Get*`) and a wildcard Resource
              ;; (`bucket-glo*`, matching the bucket's own ARN exactly via
              ;; the trailing wildcard) - distinct from every other
              ;; fixture's literal-only statements.
              "policy" (policy-doc (stmt {:effect "Allow" :actions ["s3:Get*"]
                                           :resources ["arn:aws:s3:::bucket-glo*"]}))})])

(deftest iam-reachable?-wildcard-action-and-resource-pattern-matches
  (let [db (db-with-resources glob-resources)]
    (is (iam/iam-reachable? db "aws_iam_role.role_glob" "aws_s3_bucket.bucket_glob" "s3:GetObject"))))
