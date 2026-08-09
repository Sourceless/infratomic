(ns infratomic.state-backend.iam
  "IAM access reachability: given the deployed IAM policy graph (identity-
  based policies, resource-based policies, and role-assumption chains),
  answers whether a principal can perform an action on a resource by
  parsing the deployed policy JSON at query time into a scratch, never-
  transacted `d/with` db and traversing the resulting facts with a
  recursive Datalog rule set (`grants`) - not by walking parsed policy JSON
  as an application-level graph walk in Clojure. See
  docs/adr/0005-derive-iam-policy-facts-at-query-time-via-speculative-db.md.

  Policy JSON itself is never given a modeled schema
  (`db/resource-schema` gains no entries for it) - it stays exactly as
  stored today, an opaque generic-attribute string, read back here via the
  same reconstruction path `GET` already uses (`db/reconstruct-attributes`).
  Only `arn` attributes (already computed by Terraform) are modeled, so the
  `grants` rule can resolve a `:resource/id` to the ARN a policy statement
  actually names.

  Kept as its own namespace, separate from `query.clj` (pure Datalog
  querying over already-typed persisted data) and `policy.clj` (plan-time
  speculative evaluation of a different kind of input, `terraform show
  -json` plan documents) - this derives read-only fact datoms from
  already-real, already-persisted state for a read query, sharing no code
  with either."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.query :as query]))

;; ---------------------------------------------------------------------------
;; Glob matching (IAM's `*`/`?` wildcard semantics)
;; ---------------------------------------------------------------------------

(def ^:private glob-metachar-re
  "Every regex metacharacter that could appear in a compiled glob pattern
  and needs handling one character at a time (including `*`/`?`
  themselves, which get glob semantics instead of literal escaping -
  everything else is escaped literally)."
  #"[.*?+^$\[\]\\(){}|]")

(defn- glob-char->regex-fragment
  [c]
  (case c
    "*" ".*"
    "?" "."
    (str "\\" c)))

(defn- glob->regex
  "Compile an IAM glob `pattern` (`*` matches zero or more characters, `?`
  matches exactly one, everything else literal) into an anchored regex."
  [pattern]
  (re-pattern (str "^" (str/replace pattern glob-metachar-re glob-char->regex-fragment) "$")))

(defn glob-matches?
  "Whether `value` matches IAM glob `pattern` (`*`/`?` wildcards, everything
  else literal). Invoked from the `grants` rule as a Datomic predicate
  clause (`[(infratomic.state-backend.iam/glob-matches? ?pattern ?value)]`)
  - fully qualified so it resolves regardless of which namespace's `d/q`
  call evaluates the rule."
  [pattern value]
  (boolean (re-matches (glob->regex pattern) value)))

;; ---------------------------------------------------------------------------
;; Policy JSON parsing
;; ---------------------------------------------------------------------------

(defn- as-set
  "Normalize an IAM policy statement value that may be a bare string, an
  array, or absent into a set - `Action`/`Resource` allow both a bare
  string and an array for a single-element list."
  [v]
  (cond
    (nil? v) #{}
    (sequential? v) (set v)
    :else #{v}))

(defn- principal-set
  "Flatten a statement's `Principal` value (a bare string, or a map of
  principal-type -> string-or-array, e.g. `{\"AWS\": \"arn:...\"}`) into a
  set of principal identifiers. Non-ARN identifiers (e.g. a service
  principal like `\"lambda.amazonaws.com\"`) are kept too - they simply
  never glob-match a resource's ARN."
  [principal]
  (cond
    (nil? principal) #{}
    (string? principal) #{principal}
    (map? principal) (into #{} (mapcat (fn [[_ v]] (as-set v))) principal)
    :else #{}))

(defn- normalize-statement
  [stmt]
  {:effect    (get stmt "Effect")
   :action    (as-set (get stmt "Action"))
   :resource  (as-set (get stmt "Resource"))
   :principal (principal-set (get stmt "Principal"))})

(defn parse-policy-statements
  "Parse a raw IAM policy document JSON string into a seq of normalized
  statement maps (`:effect` a string, `:action`/`:resource`/`:principal`
  each a set of glob patterns/identifiers)."
  [policy-json]
  (let [document   (json/parse-string policy-json)
        statements (get document "Statement")
        statements (cond
                     (map? statements) [statements]
                     (sequential? statements) statements
                     :else [])]
    (mapv normalize-statement statements)))

;; ---------------------------------------------------------------------------
;; Query-time fact derivation
;; ---------------------------------------------------------------------------

(def ^:private policy-bearing-types
  "Every Terraform resource type `derive-tx-data` reads: the types whose
  modeled idents (`id`/`name`/`arn`/join attributes - never policy content
  itself, see this namespace's docstring) `raw-attribute-pull-pattern`
  needs to pull so `db/reconstruct-attributes` can recover them alongside
  the generic-attribute policy JSON."
  ["aws_iam_role" "aws_iam_policy" "aws_s3_bucket" "aws_iam_role_policy_attachment"])

(defn- modeled-idents-for
  [type]
  (map :ident (vals (get db/resource-schema type))))

(def ^:private raw-attribute-pull-pattern
  "Pulls enough of a resource entity to reconstruct its full attribute map
  via `db/reconstruct-attributes`: every modeled ident `policy-bearing-
  types` carries (`id`/`name`/`arn`/join attributes), plus the generic
  `:resource/attribute` sub-entities every policy-bearing attribute is
  always stored through, since no policy-content attribute is ever
  modeled (see this namespace's docstring)."
  (into [:resource/type
         {:resource/attribute [:resource.attribute/key :resource.attribute/value
                                {:resource.attribute/overflow-chunk [:overflow-chunk/index :overflow-chunk/value]}]}]
        (mapcat modeled-idents-for) policy-bearing-types))

(defn- resource-attributes
  [db eid]
  (let [pulled (d/pull db raw-attribute-pull-pattern eid)]
    (db/reconstruct-attributes (:resource/type pulled) pulled)))

(defn- entries-by-type
  "Every currently deployed resource of `type`, as `{:id <:resource/id>
  :attributes <full attribute map, incl. raw policy JSON strings>}`."
  [db id->eid type]
  (map (fn [{:resource/keys [id]}]
         {:id id :attributes (resource-attributes db (get id->eid id))})
       (query/resources-by-type db type)))

(defn- attr-index
  "Map of raw Terraform attribute value (for any of `attr-keys`, e.g.
  `[\"name\" \"id\"]` for a role referenced by either) -> owning resource's
  `:resource/id`, across `entries` (see `entries-by-type`). Used to resolve
  a policy's own string reference (e.g. `aws_iam_role_policy.role`,
  `aws_s3_bucket_policy.bucket`) back to the resource entity it names."
  [entries attr-keys]
  (into {}
        (mapcat (fn [{:keys [id attributes]}]
                  (keep (fn [k] (when-let [v (get attributes k)] [v id])) attr-keys)))
        entries))

(defn- statement-tx-map
  "One scratch `:iam-statement/*` entity tx-map for a normalized statement,
  sourced from `source-id` (an already-persisted resource's `:resource/id`,
  referenced via a lookup ref so no eid lookup is needed here)."
  [{:keys [effect action resource principal]} kind source-id]
  (cond-> {:db/id                (str (random-uuid))
           :iam-statement/effect effect
           :iam-statement/kind   kind
           :iam-statement/source [:resource/id source-id]}
    (seq action)    (assoc :iam-statement/action (vec action))
    (seq resource)  (assoc :iam-statement/resource (vec resource))
    (seq principal) (assoc :iam-statement/principal (vec principal))))

(defn- statements-tx-data
  "Every `statement-tx-map` derived from `policy-json` (may be `nil` - a
  resource with no policy-bearing attribute set contributes nothing),
  sourced from `source-id`, tagged `kind`."
  [policy-json kind source-id]
  (when policy-json
    (mapv #(statement-tx-map % kind source-id) (parse-policy-statements policy-json))))

(defn derive-tx-data
  "The full scratch tx-data for one `iam-reachable?` call: one
  `:iam-statement/*` entity per policy statement found across every
  policy-bearing resource currently in `db` - trust policies
  (`aws_iam_role.assume_role_policy`, kind `:trust`), inline identity
  policies (`aws_iam_role_policy.policy`, kind `:identity`), resource-based
  policies (`aws_s3_bucket_policy.policy`, kind `:resource`), and managed
  policies reached via `aws_iam_role_policy_attachment` (kind `:identity`,
  sourced from the attached role, not the policy entity itself). Never
  transacted - only ever passed to `(d/with db {:tx-data ...})`."
  [db]
  (let [id->eid            (db/resource-id->eid db)
        roles              (entries-by-type db id->eid "aws_iam_role")
        role-policies      (entries-by-type db id->eid "aws_iam_role_policy")
        buckets            (entries-by-type db id->eid "aws_s3_bucket")
        bucket-policies    (entries-by-type db id->eid "aws_s3_bucket_policy")
        managed-policies   (entries-by-type db id->eid "aws_iam_policy")
        attachments        (entries-by-type db id->eid "aws_iam_role_policy_attachment")

        role->id           (attr-index roles ["name" "id"])
        bucket->id         (attr-index buckets ["bucket" "id"])
        policy-json-by-arn (into {}
                                  (keep (fn [{:keys [attributes]}]
                                          (when-let [arn (get attributes "arn")]
                                            [arn (get attributes "policy")])))
                                  managed-policies)

        trust-tx    (mapcat (fn [{:keys [id attributes]}]
                               (statements-tx-data (get attributes "assume_role_policy") :trust id))
                             roles)
        identity-tx (mapcat (fn [{:keys [attributes]}]
                               (when-let [source-id (get role->id (get attributes "role"))]
                                 (statements-tx-data (get attributes "policy") :identity source-id)))
                             role-policies)
        resource-tx (mapcat (fn [{:keys [attributes]}]
                               (when-let [source-id (get bucket->id (get attributes "bucket"))]
                                 (statements-tx-data (get attributes "policy") :resource source-id)))
                             bucket-policies)
        managed-tx  (mapcat (fn [{:keys [attributes]}]
                               (when-let [source-id (get role->id (get attributes "role"))]
                                 (statements-tx-data (get policy-json-by-arn (get attributes "policy_arn"))
                                                      :identity source-id)))
                             attachments)]
    (into [] (concat trust-tx identity-tx resource-tx managed-tx))))

;; ---------------------------------------------------------------------------
;; The `grants` recursive rule
;; ---------------------------------------------------------------------------

(def ^:private grants-rules
  "The Datomic rules (`:in $ %`) backing `iam-reachable?`:

  - `resource-arn ?id ?arn`: resolves a `:resource/id` to whichever typed
    `arn` attribute its resource type carries (`aws_iam_role`,
    `aws_iam_policy`, or `aws_s3_bucket`) - the only modeled attribute
    policy content itself is ever joined against (see this namespace's
    docstring and design.md's ARN-resolution decision).

  - `identity-allows`/`resource-allows`/`trust-allows ?principal ?action
    ?resource`: an Allow statement of the matching kind, sourced from the
    relevant side's own resource entity, whose action/resource(/principal)
    glob-match. `identity-allows` needs no principal check (the statement's
    principal is implicit - it's attached to `?principal` itself);
    `resource-allows` requires both a resource and a principal match (a
    resource-based policy always names both); `trust-allows` requires only
    an action and principal match (a trust statement has no `Resource` key
    - its target is implicit, the role it's attached to).

  - `identity-denies`/`resource-denies ?principal ?action ?resource` and
    `denied? ?principal ?action ?resource`: the same shape, but for `Deny`
    statements (`resource-denies` covers both `:resource` and `:trust`
    kinds), scoped only to the action/resource in question - never a
    principal check, and never a blanket \"any deny exists\" check - per
    design.md's scoped-deny-override decision.

  - `grants ?principal ?action ?resource`: the public recursive rule.
    Direct access (identity-side OR resource-side Allow, not gated to
    `sts:AssumeRole`, with no matching deny) is one path; the
    role-assumption edge (`?action` = `\"sts:AssumeRole\"`, `?resource` an
    `aws_iam_role`) is a separate path requiring BOTH `trust-allows` and
    `identity-allows` (unlike direct access's OR), since AWS role
    assumption genuinely requires both sides; and the recursive
    resource-access-via-assumed-role clause chains `grants ?principal
    \"sts:AssumeRole\" ?mid` with `grants ?mid ?action ?resource`, letting a
    chain of more than one assumed role work without hand-unrolling hops."
  '[[(resource-arn ?id ?arn)
     [?e :resource/id ?id]
     [?e :aws-iam-role/arn ?arn]]
    [(resource-arn ?id ?arn)
     [?e :resource/id ?id]
     [?e :aws-iam-policy/arn ?arn]]
    [(resource-arn ?id ?arn)
     [?e :resource/id ?id]
     [?e :aws-s3-bucket/arn ?arn]]

    [(identity-allows ?principal ?action ?resource)
     [?principal-e :resource/id ?principal]
     [?stmt :iam-statement/source ?principal-e]
     [?stmt :iam-statement/kind :identity]
     [?stmt :iam-statement/effect "Allow"]
     [?stmt :iam-statement/action ?apattern]
     [(infratomic.state-backend.iam/glob-matches? ?apattern ?action)]
     (resource-arn ?resource ?resource-arn)
     [?stmt :iam-statement/resource ?rpattern]
     [(infratomic.state-backend.iam/glob-matches? ?rpattern ?resource-arn)]]

    [(resource-allows ?principal ?action ?resource)
     [?resource-e :resource/id ?resource]
     [?stmt :iam-statement/source ?resource-e]
     [?stmt :iam-statement/kind :resource]
     [?stmt :iam-statement/effect "Allow"]
     [?stmt :iam-statement/action ?apattern]
     [(infratomic.state-backend.iam/glob-matches? ?apattern ?action)]
     [?stmt :iam-statement/resource ?rpattern]
     (resource-arn ?resource ?resource-arn)
     [(infratomic.state-backend.iam/glob-matches? ?rpattern ?resource-arn)]
     [?stmt :iam-statement/principal ?ppattern]
     (resource-arn ?principal ?principal-arn)
     [(infratomic.state-backend.iam/glob-matches? ?ppattern ?principal-arn)]]

    [(trust-allows ?principal ?action ?resource)
     [?resource-e :resource/id ?resource]
     [?stmt :iam-statement/source ?resource-e]
     [?stmt :iam-statement/kind :trust]
     [?stmt :iam-statement/effect "Allow"]
     [?stmt :iam-statement/action ?apattern]
     [(infratomic.state-backend.iam/glob-matches? ?apattern ?action)]
     [?stmt :iam-statement/principal ?ppattern]
     (resource-arn ?principal ?principal-arn)
     [(infratomic.state-backend.iam/glob-matches? ?ppattern ?principal-arn)]]

    [(identity-denies ?principal ?action ?resource)
     [?principal-e :resource/id ?principal]
     [?stmt :iam-statement/source ?principal-e]
     [?stmt :iam-statement/kind :identity]
     [?stmt :iam-statement/effect "Deny"]
     [?stmt :iam-statement/action ?apattern]
     [(infratomic.state-backend.iam/glob-matches? ?apattern ?action)]
     (resource-arn ?resource ?resource-arn)
     [?stmt :iam-statement/resource ?rpattern]
     [(infratomic.state-backend.iam/glob-matches? ?rpattern ?resource-arn)]]

    [(resource-denies ?principal ?action ?resource)
     [?resource-e :resource/id ?resource]
     [?stmt :iam-statement/source ?resource-e]
     [?stmt :iam-statement/kind :resource]
     [?stmt :iam-statement/effect "Deny"]
     [?stmt :iam-statement/action ?apattern]
     [(infratomic.state-backend.iam/glob-matches? ?apattern ?action)]
     [?stmt :iam-statement/resource ?rpattern]
     (resource-arn ?resource ?resource-arn)
     [(infratomic.state-backend.iam/glob-matches? ?rpattern ?resource-arn)]]

    [(resource-denies ?principal ?action ?resource)
     [?resource-e :resource/id ?resource]
     [?stmt :iam-statement/source ?resource-e]
     [?stmt :iam-statement/kind :trust]
     [?stmt :iam-statement/effect "Deny"]
     [?stmt :iam-statement/action ?apattern]
     [(infratomic.state-backend.iam/glob-matches? ?apattern ?action)]]

    [(denied? ?principal ?action ?resource)
     (identity-denies ?principal ?action ?resource)]
    [(denied? ?principal ?action ?resource)
     (resource-denies ?principal ?action ?resource)]

    ;; Direct access - either side alone suffices - never applies to the
    ;; role-assumption action, which requires both sides (below).
    [(grants ?principal ?action ?resource)
     [(not= ?action "sts:AssumeRole")]
     (identity-allows ?principal ?action ?resource)
     (not-join [?principal ?action ?resource] (denied? ?principal ?action ?resource))]
    [(grants ?principal ?action ?resource)
     [(not= ?action "sts:AssumeRole")]
     (resource-allows ?principal ?action ?resource)
     (not-join [?principal ?action ?resource] (denied? ?principal ?action ?resource))]

    ;; Role-assumption edge: both the target role's trust policy and the
    ;; source's own identity-based sts:AssumeRole grant are required.
    [(grants ?principal ?action ?target-role)
     [(= ?action "sts:AssumeRole")]
     [?target-role-e :resource/id ?target-role]
     [?target-role-e :resource/type "aws_iam_role"]
     (trust-allows ?principal ?action ?target-role)
     (identity-allows ?principal ?action ?target-role)
     (not-join [?principal ?action ?target-role] (denied? ?principal ?action ?target-role))]

    ;; Recursive resource-access-via-assumed-role: genuinely self-
    ;; referential, so a chain of more than one assumed role is followed
    ;; without hand-unrolling hops.
    [(grants ?principal ?action ?resource)
     (grants ?principal "sts:AssumeRole" ?mid)
     (grants ?mid ?action ?resource)]])

(defn iam-reachable?
  "Whether IAM `principal` (an `aws_iam_role` `:resource/id`, e.g.
  \"aws_iam_role.source\") can perform `action` (an IAM action string, e.g.
  \"s3:GetObject\") on `resource` (a `:resource/id`, e.g.
  \"aws_s3_bucket.data\") - derived by parsing the deployed IAM policy
  documents in `db` at query time into a scratch, never-transacted db
  (`derive-tx-data`) and traversing the resulting statement facts as the
  recursive `grants` Datalog rule set (see `grants-rules`), including
  role-assumption chains of arbitrary length and scoped deny-override.
  `db` is a live, already-persisted db value - no plan involved, unlike
  `policy.clj`'s speculative evaluation."
  [db principal resource action]
  (let [db-after (:db-after (d/with db {:tx-data (derive-tx-data db)}))]
    (boolean
     (seq (d/q '[:find ?resource
                 :in $ % ?principal ?resource ?action
                 :where (grants ?principal ?action ?resource)]
               db-after grants-rules principal resource action)))))
