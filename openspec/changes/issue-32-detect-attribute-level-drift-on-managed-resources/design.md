## Context

See proposal.md for motivation. Relevant existing mechanics this design builds on:

- `sync.clj`'s `resource-tx`/`existing-match`/`id-ident`: match a freshly observed AWS resource against an existing Resource entity by that type's single modeled `"id"` attribute; `nil? match` -> plain Discovered Resource, no join to any parent.
- `sync.clj`'s `explicit-route?`/`subnet-association?`: filter AWS-implicit entries (default local route, main-table association) out of `describe-all` before they ever become candidate resources.
- `query.clj`'s `security-groups-with-port-22-open`: the precedent for joining a child (`aws_security_group_rule`) back to its owning parent (`aws_security_group`) via typed value equality on a modeled FK attribute.
- `query.clj`'s `drifted-resources`/`drift-endpoint`, `db.clj`'s `:resource/last-write-source`, and ADR-0009: the precedent for signalling drift via a plain, orthogonal entity attribute (never part of `resource-pull-pattern`, so invisible to `GET /state` reconstruction) plus `d/history`/`d/as-of` to recover prior state.
- `db.clj`'s `resource-pull-pattern`/`reconstruct-attributes`: exactly the set of attributes `GET /state` reconstructs. Any attribute not in this pattern can be freely written without changing what Terraform is told.
- `db.clj`'s `resource-schema`: every FK-bearing child type already models its FK as a typed attribute (`:aws-security-group-rule/security-group-id`, `:aws-route/route-table-id`, `:aws-route-table-association/route-table-id` and `/subnet-id`, `:aws-iam-role-policy-attachment/role`) - the join target for each is that parent type's own id-shaped attribute (`:aws-security-group/id`, `:aws-route-table/id`, `:aws-subnet/id`), except `aws_iam_role_policy_attachment`, whose FK (`role`) joins against `:aws-iam-role/name`, not an id.

## Goals / Non-Goals

**Goals:**
- A single, data-driven join description covering all five child-FK relationships (`aws_route_table_association` has two), reused for both new-child and removed-child detection, so the four-type generalization is a data change, not four bespoke Rules.
- A removed-child signal that is provably inert with respect to `GET /state`: it must not appear anywhere in `resource-pull-pattern`/`reconstruct-attributes`, and it must never retract a resource entity or any of its modeled/generic attribute datoms.
- Minimal new Sync surface for `aws_iam_role_policy_attachment` - reuse the `ec2-client`/`http-client` JDK-transport pattern for a new IAM client, reuse `resource-attr-tx`/`resource-upsert-retractions` unchanged for the write side.

**Non-Goals:**
- Detecting disappearance of any resource type other than the four FK-bearing child types (e.g. a managed `aws_security_group` itself disappearing is not in scope - only its rules are).
- Any change to `resource-tx`'s outcome kinds, `sync!`'s public summary shape, or the CLI's `sync` subcommand output.
- Remediation of any kind.

## Decisions

### Decision: a single data-driven child/parent join table drives both new- and removed-child detection

Add one new data structure (`query.clj`, alongside `resource-schema`'s spirit but scoped to this feature - it is a Rule concern, not a storage-schema concern, so it does not live in `db.clj`):

```clojure
(def child-parent-joins
  [{:child-type "aws_security_group_rule"        :fk-ident :aws-security-group-rule/security-group-id
    :parent-type "aws_security_group"             :parent-join-ident :aws-security-group/id}
   {:child-type "aws_route"                       :fk-ident :aws-route/route-table-id
    :parent-type "aws_route_table"                :parent-join-ident :aws-route-table/id}
   {:child-type "aws_route_table_association"     :fk-ident :aws-route-table-association/route-table-id
    :parent-type "aws_route_table"                :parent-join-ident :aws-route-table/id}
   {:child-type "aws_route_table_association"     :fk-ident :aws-route-table-association/subnet-id
    :parent-type "aws_subnet"                      :parent-join-ident :aws-subnet/id}
   {:child-type "aws_iam_role_policy_attachment"  :fk-ident :aws-iam-role-policy-attachment/role
    :parent-type "aws_iam_role"                    :parent-join-ident :aws-iam-role/name}])
```

`aws_route_table_association` appears twice (once per FK) - deliberately: an out-of-band association is drift on *both* the route table and the subnet it names, and both parent entries should carry it independently in `GET /drift`'s response. `aws_iam_role_policy_attachment` is the one entry whose `parent-join-ident` is not that parent type's `"id"` attribute (`:aws-iam-role/name`, not `:aws-iam-role/id`) - the join table makes this a data difference, not a code branch.

Both new-child and removed-child detection are one generic function each, parameterized over `child-parent-joins`, rather than five (or four) bespoke Rules - this is the direct generalization of `security-groups-with-port-22-open`'s single hard-coded join.

Alternative considered: keep four/five separate hand-written Rule functions (one per relationship), mirroring `security-groups-with-port-22-open`'s style exactly. Rejected - the four relationships are structurally identical (typed-value-equality join, differing only in which idents), and a fifth (the second `aws_route_table_association` FK) would otherwise be near-duplicate code; a data table plus one generic traversal is less code and the natural place to add a sixth relationship later (a data-only change).

### Decision: new-child detection is `parent + {child-type child managed?=false with FK -> parent}`, purely query-time

For each `child-parent-joins` entry, find every child entity of `:child-type` whose `:fk-ident` value equals a managed (`:resource/managed? true`) parent entity's `:parent-join-ident` value, where the child's own `:resource/managed?` is `false` (a Discovered Resource - i.e. Sync found it with no Terraform-managed match, per `resource-tx`'s unchanged `nil? match` branch). This condition alone is sufficient to mean "new, out-of-band child" - a *Terraform-managed* child with the same FK shape is not new-child drift, it is exactly what Terraform expects to exist.

No change anywhere in `sync.clj`'s write path: this is purely `resources-tx`'s already-shipped `nil? match` -> plain Discovered Resource outcome, read back at query time by the new Rule. Confirms the alignment's "no new persisted outcome kind" decision requires zero Sync changes for this half of the feature.

### Decision: removed-child detection via a new orthogonal `:resource/sync-present?` marker, tri-state through presence/absence + explicit `false`

New schema attribute:

```clojure
{:db/ident :resource/sync-present?
 :db/valueType :db.type/boolean
 :db/cardinality :db.cardinality/one
 :db/doc "Whether Sync's most recent full pass, for a Terraform-managed resource of one of the four FK-bearing child types this drift mechanism covers (aws_security_group_rule, aws_route, aws_route_table_association, aws_iam_role_policy_attachment), found this resource still present in AWS. true when found; false when Sync ran and did not find it (removed-child drift); absent when no Sync run covering this type has happened since the resource was created/last matched. Never read by GET /state reconstruction (absent from resource-pull-pattern) - purely a query-time signal for the removed-child Rule."}
```

`sync!` gains one additional step, run once per full pass (not per-resource, unlike `resource-tx`): for each of the four covered types, diff the set of AWS ids `describe-all` observed this run (already filtered by `explicit-route?`/`subnet-association?` where applicable, since that filtering already happens before `describe-all`'s output reaches this step) against the set of ids already stored on Terraform-managed entities of that type (`managed-resource-ids-by-type`, a new `db.clj` query mirroring `existing-match`'s shape but returning every managed match for a type, not one). Every id in the observed set gets `:resource/sync-present? true` asserted on its match (whether or not that id's attributes also changed - piggy-backs on the tx `resource-tx` already builds for that entity when present, or is added standalone when the entity's attributes didn't change and `resource-tx` would otherwise have produced no tx-data at all - see the reappearance fix below). Every id present in the stored set but absent from the observed set gets `:resource/sync-present? false` asserted (a genuine transition, so it is a real, `d/history`-visible event even though the mechanism's primary read path uses the current value directly - see below).

Removed-child Rule: for each `child-parent-joins` entry, find every child entity of `:child-type` with `:resource/managed? true` and current `:resource/sync-present? false`, joined to its parent via `:fk-ident`/`:parent-join-ident` exactly as the new-child Rule does. Because the child entity itself, and all of its modeled/generic attribute datoms, are never touched by this mechanism, `{type, id}` for the response is read directly off the (still fully intact) child entity - the same ordinary pull every other Rule in this codebase already uses (`resource-summary-pattern`). `d/history`/`d/as-of` remain available against `:resource/sync-present?` to recover *when* a child went missing (mirroring ADR-0009's `last-terraform-write-tx` idiom exactly, applied to this new attribute instead of `:resource/last-write-source`), satisfying the "additive, discoverable via Datomic history" constraint - the Rule's primary read just doesn't need that lookup for the minimal `{type, id}` shape the response requires today.

**Reappearance fix**: `resource-tx`'s existing-managed-match branch currently produces `{:tx-data [] :outcome :skipped-already-managed}` when observed attributes exactly equal stored attributes - no write at all. If a previously `:resource/sync-present? false` child reappears with unchanged attributes, this branch must still write (at minimum) `:resource/sync-present? true`, or the marker would never clear. `resource-tx` gains one extra condition on that branch: skip (no tx-data) only when attributes are equal *and* the match's current `:resource/sync-present?` is not `false`; otherwise, write is forced (even with attributes unchanged) to flip the marker back to `true`.

Considered and rejected: retracting the child's own FK attribute (e.g. `:aws-security-group-rule/security-group-id`) as the removed signal, discovered via history the same way ADR-0009 discovers Terraform's last-asserted attribute values. Rejected - the FK is a modeled attribute inside `resource-pull-pattern`; retracting it would change `reconstruct-attributes`' output for that resource, i.e. would change what `GET /state` reports (dropping the attribute Terraform itself asserted), a live-state mutation, directly violating the alignment's hard constraint.

Considered and rejected: a full `:db/retractEntity` on the child. Rejected explicitly by the alignment's hard constraint - `managed-resource-eids`/`GET /state` would silently stop reporting a resource Terraform still believes it owns, making the next `plan` think it needs recreating.

Considered and rejected: overloading `:resource/last-write-source` with a third enum value (e.g. `:sync-missing`) instead of a new attribute. Would technically work (existing consumers match the literal `:sync` value, so a `:sync-missing` value would not accidentally be picked up by `sync-sourced-managed-resources`) and would avoid one new schema attribute. Rejected for clarity: `:resource/last-write-source` answers "who wrote this attribute set last," a different question from "did Sync's most recent pass observe this resource at all" - conflating them into one attribute's value space makes both harder to reason about and to extend later (e.g. `:resource/sync-present?` needs a genuine tri-state - present/absent-so-far/confirmed-missing - that doesn't map cleanly onto "last writer").

Considered and rejected: an explicit boolean-flip-plus-timestamp pair, or a `:resource/removed-at-tx` ref-to-tx attribute, instead of a plain boolean. Rejected as unneeded extra structure - `d/history` already recovers the transaction of any datom change for free (this codebase's established idiom, per ADR-0009), so a dedicated timestamp/tx-ref attribute would only duplicate what history already answers, mirroring ADR-0009's own "considered and rejected: tx-metadata" reasoning.

### Decision: `resource-tx`/`existing-match` gain a composite-key matching path for `aws_iam_role_policy_attachment`

`id-ident` returns `nil` for `"aws_iam_role_policy_attachment"` (it has no modeled `"id"` key in `resource-schema` - only `"role"`/`"policy_arn"`), so `existing-match`'s `(when-let [ident (id-ident type)] ...)` guard already safely no-ops for it today (it is simply never matched, because Sync never fetches it today). Adding real Sync coverage for this type requires a second matching strategy: composite equality on *both* `:aws-iam-role-policy-attachment/role` and `:aws-iam-role-policy-attachment/policy-arn`, since neither alone uniquely identifies an attachment (a role can have many attachments; a policy can be attached to many roles).

`existing-match` is generalized to dispatch: types with an `id-ident` keep the existing single-attribute lookup unchanged; `"aws_iam_role_policy_attachment"` uses a new composite lookup (`[?e :aws-iam-role-policy-attachment/role ?role] [?e :aws-iam-role-policy-attachment/policy-arn ?arn]`, both bound). `discovered-resource-id` similarly gains a case for this type, synthesizing `"aws_iam_role_policy_attachment.discovered-<role>-<policy_arn>"` (both values are already valid ARN/name strings with no embedded ambiguity for this codebase's string-based `:resource/id`).

Alternative considered: model a synthetic `"id"` for `aws_iam_role_policy_attachment` in `resource-schema` (e.g. `role + "/" + policy_arn`), so it fits the existing single-ident matching path unchanged, no new matching branch needed. Rejected - Terraform's own `aws_iam_role_policy_attachment` resource has no `id` attribute in its schema either (confirmed against the provider), so inventing one that AWS/Terraform never asserts would be modeling a fact that doesn't exist, purely to avoid a second code path in `existing-match` - not a real simplification.

### Decision: new IAM Sync infrastructure - `iam-client`, `ListAttachedRolePolicies` per known role, `iam-role-policy-attachment->attrs`

`aws_iam_role`/`aws_iam_policy` are only ever created via `POST /state` today (`iam.clj`'s scope, never `sync.clj`'s) - there is no "discover every IAM role in LocalStack" call anywhere in this codebase, and adding one is out of scope (the alignment scopes this to attachments only, on already-known-managed roles). `describe-all` gains an IAM step: query the db (passed into `sync!` already) for every `aws_iam_role` entity's `:aws-iam-role/name`, call `ListAttachedRolePolicies` once per name (mirroring `security-group-rules`'s per-SG `DescribeSecurityGroupRules` pattern exactly, including the same "no blanket unfiltered call" shape), and translate each `AttachedPolicies[]` entry via a new `iam-role-policy-attachment->attrs` function (`{"role" role-name "policy_arn" (:PolicyArn entry)}`).

This makes `describe-all` no longer purely a function of `client` - it now also needs `db` (to enumerate known roles). Its signature changes to `(describe-all client db)`; `sync!` already holds both.

The IAM client reuses `http-client` (the JDK-native transport `ec2-client` already built to route around the Jetty version conflict - ADR-0008) via a new `iam-client` constructor, identical in shape to `ec2-client` except `:api :iam`.

## Risks / Trade-offs

- [Risk] `describe-all` gaining a `db` parameter is a breaking signature change to an existing function. -> Mitigation: `sync!` is `describe-all`'s only caller in this codebase; both call sites update together in this same change.
- [Risk] The reappearance fix touches `resource-tx`'s existing-managed-match branch, the same branch ADR-0009 already modified once. -> Mitigation: the added condition is purely additive (an extra reason to write, never a reason to skip a write the current logic already makes), so every existing `resource-tx`/`sync_integration_test.clj` scenario keeps its current outcome.
- [Risk] A route table association's two-parent join means a single out-of-band association could appear in *two* different parents' `new_children`/`removed_children` lists in one `GET /drift` response. -> Accepted, not mitigated: this is the correct semantics (it is genuinely new/removed data on both relationships), and the response shape (a list per parent entry) already supports a child id appearing under more than one parent without any structural change.
- [Risk] `ListAttachedRolePolicies` is called once per known `aws_iam_role` on every Sync pass, an extra AWS round-trip per role, every run. -> Accepted, matching `security-group-rules`'s already-accepted per-SG-call cost and Sync's existing "explicitly triggered, not automatic" cost profile (per `resource-sync` spec).

## Migration Plan

`:resource/sync-present?` is a brand-new attribute with no backfill requirement - unlike `:resource/managed?`/`:resource/last-write-source` (ADR-0005/0009), its absence is a valid, meaningful state ("no Sync run covering this type has happened yet"), not an ambiguity that needs resolving for pre-existing data. No migration step is added to `ensure-db!`.

An ADR (`docs/adr/0010-detect-removed-child-drift-via-sync-present-marker.md`) records the removed-child mechanism decision above, following ADR-0009's structure.
