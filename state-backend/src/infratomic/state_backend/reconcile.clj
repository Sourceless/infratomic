(ns infratomic.state-backend.reconcile
  "Reconciliation (issue #34): the final step of every Sync pass
  (`sync.clj`'s `sync!`, both on-demand and scheduled) - evaluates every
  registered policy Rule (`policy.clj`'s `rule-registry`) directly against
  live state (`(d/db conn)`, not a speculative plan-derived db - unlike
  Policy Check's `evaluate`), and for every violating resource, dispatches
  remediation based on whether that specific resource is itself
  Terraform-managed:

  - Managed and drifted (per `query.clj`'s `drifted-resources`) ->
    `terraform.clj`'s `apply!`, against the shared working directory.
  - Not managed (a Discovered Resource, or the specific offending child
    resource of an otherwise-managed parent - e.g. New-Child Drift) ->
    `terraform.clj`'s `synthesize-import-and-destroy!`, in its own scratch
    working directory.
  - Managed and not drifted but still violating -> no remediation action;
    the finding is recorded only.

  A Rule that binds a parent resource whose actual violation is
  attributable to a specific child (the port-22-open security group rule)
  is resolved to that concrete child via `query.clj`'s
  `offending-port-22-rules-for-sg` before dispatch - the parent itself is
  never the remediation target for that Rule (`child-resolvers`).

  Every decision - one per violating (post-child-resolution) resource per
  reconciliation pass - is persisted unconditionally as a
  `:reconciliation/*` record (`db.clj`), whether or not it produced an
  Invocation. See proposal.md/design.md for the full rationale; this is
  the one namespace whose job is closing the loop between detection
  (read-only, elsewhere) and action (`terraform.clj`, elsewhere)."
  (:require [infratomic.state-backend.datomic :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.policy :as policy]
            [infratomic.state-backend.query :as query]
            [infratomic.state-backend.terraform :as terraform]))

(def ^:private resource-summary-pattern
  [:resource/id :resource/type])

(defn- rule-violations
  "Every `resource-summary-pattern`-shaped bound entity for one registered
  Rule, evaluated directly against live `db` - `policy.clj`'s `run-rule`,
  reused as-is (it's already db-value-agnostic; only `evaluate`, hardwired
  to a speculative plan-derived db, is unsuitable here). Returns the
  Rule's own `:rule/id` alongside each bound resource, so downstream
  dispatch/recording knows which Rule it violated."
  [db {:rule/keys [id] :as rule}]
  (map (fn [eid] (assoc (d/pull db resource-summary-pattern eid) :rule id))
       (policy/run-rule db rule)))

(def ^:private child-resolvers
  "Map of `:rule/id` -> `(fn [db parent-summary]) -> seq of resource-
  summary-pattern-shaped child resources` - for a Rule whose `:rule/find`
  binds a parent resource but whose actual violation is attributable to
  one of that parent's children (design.md's \"child-binding companion
  query\" decision). Reconciliation-only, keyed here rather than
  generalizing `:rule/find`/the Rule registry itself (alignment
  explicitly rejected that). A Rule with no entry here treats its own
  bound resource as the remediation target directly."
  {:security-groups-with-port-22-open
   (fn [db parent] (query/offending-port-22-rules-for-sg db (:resource/id parent)))})

(defn- remediation-targets
  "The concrete remediation target(s) for one violation: the resolved
  offending child(ren) for a Rule with a `child-resolvers` entry, or the
  violation's own bound resource otherwise (design.md's \"dispatch keys
  off... the specific violating entity, not the Rule's own bound
  entity\" decision)."
  [db {:keys [rule] :as violation}]
  (if-let [resolve-children (get child-resolvers rule)]
    (resolve-children db violation)
    [violation]))

(defn- managed?
  [db resource-id]
  (:resource/managed? (d/pull db [:resource/managed?] [:resource/id resource-id])))

(defn- drifted-resource-ids
  [db]
  (into #{} (map :resource/id) (query/drifted-resources db)))

(defn- latest-invocation-eid
  "The entity id of the most recently transacted Invocation entity for
  `address` - looked up against a fresh `(d/db conn)` read immediately
  after the terraform.clj call that (unconditionally) wrote it, rather
  than threaded through as a return value, so `apply!`/
  `synthesize-import-and-destroy!` need no return-shape change of their
  own (design.md's Impact: existing primitives reused unmodified)."
  [db address]
  (let [pairs (d/q '[:find ?e ?tx
                      :in $ ?a
                      :where [?e :invocation/resource-address ?a ?tx]]
                    db address)]
    (when (seq pairs)
      (first (last (sort-by second pairs))))))

(defn- record-tx
  [resource-id rule-id action invocation-eid]
  (cond-> {:reconciliation/resource [:resource/id resource-id]
           :reconciliation/rule     rule-id
           :reconciliation/action   action
           :reconciliation/at       (java.util.Date.)}
    invocation-eid (assoc :reconciliation/invocation invocation-eid)))

(defn- remediate-unmanaged!
  "Not managed (a Discovered Resource, including a New-Child-Drift child):
  synthesized import+destroy - never a bare `terraform apply` against a
  parent that never declared it (terraform-config-synthesis spec's
  \"unmanaged, policy-violating resource is imported and destroyed\"
  requirement)."
  [conn db resource-id rule-id]
  (let [type       (:resource/type (d/pull db [:resource/type] [:resource/id resource-id]))
        eid        (:db/id (d/pull db [:db/id] [:resource/id resource-id]))
        attributes (db/stored-attributes db eid type)]
    (terraform/synthesize-import-and-destroy! conn resource-id type attributes)
    (let [invocation-eid (latest-invocation-eid (d/db conn) resource-id)]
      (d/transact conn {:tx-data [(record-tx resource-id rule-id :reconciliation.action/import-destroy invocation-eid)]}))))

(defn- remediate-managed-drifted!
  "Managed and drifted: `terraform apply` against the shared working
  directory, reusing `apply!` as-is."
  [conn resource-id rule-id]
  (terraform/apply! conn (terraform/terraform-base-dir) resource-id)
  (let [invocation-eid (latest-invocation-eid (d/db conn) resource-id)]
    (d/transact conn {:tx-data [(record-tx resource-id rule-id :reconciliation.action/apply invocation-eid)]})))

(defn- record-no-action!
  "Managed and not drifted but still violating: no remediation action,
  finding recorded only."
  [conn resource-id rule-id]
  (d/transact conn {:tx-data [(record-tx resource-id rule-id :reconciliation.action/none nil)]}))

(defn- remediate-and-record!
  "Dispatches and records one resolved remediation target: `db` is the
  single db value snapshot the whole reconciliation pass evaluates Rules
  against (managed?/drifted? decisions read from it), while the
  terraform.clj calls themselves - and the Invocation lookup afterward -
  always go through `conn`/a fresh `(d/db conn)` read, since they mutate
  real state."
  [conn db resource-id rule-id]
  (cond
    (not (managed? db resource-id))
    (remediate-unmanaged! conn db resource-id rule-id)

    (contains? (drifted-resource-ids db) resource-id)
    (remediate-managed-drifted! conn resource-id rule-id)

    :else
    (record-no-action! conn resource-id rule-id)))

(defn reconcile!
  "Reconciliation's entry point (design.md's \"reconcile.clj as a new
  namespace\" decision): evaluates every registered policy Rule against
  live state, resolves each violation to its concrete remediation
  target(s), dispatches remediation per target's own managed/drifted
  status, and persists a `:reconciliation/*` record for every one -
  unconditionally, including a resource that violates no Rule getting no
  action and no record at all (the policy-reconciliation spec's
  \"non-violating resources are left untouched\" requirement, satisfied
  simply by never being a `violation` in the first place)."
  [conn]
  (let [db (d/db conn)]
    (doseq [rule (vals @policy/rule-registry)
            violation (rule-violations db rule)
            target (remediation-targets db violation)]
      (remediate-and-record! conn db (:resource/id target) (:rule violation)))))
