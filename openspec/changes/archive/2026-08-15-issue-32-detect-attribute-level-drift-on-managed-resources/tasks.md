## 1. Schema

- [x] 1.1 Add `:resource/sync-present?` (`:db.type/boolean`, `:db.cardinality/one`) to `db.clj`'s schema, documented per design.md (never read by `resource-pull-pattern`/`GET /state`; tri-state absent/true/false).

## 2. Sync: IAM role policy attachment infrastructure

- [x] 2.1 Add `iam-client` to `sync.clj` (mirrors `ec2-client`, `:api :iam`, reuses `http-client`).
- [x] 2.2 Add `iam-role-policy-attachment->attrs` translation function (`{"role" role-name "policy_arn" (:PolicyArn entry)}`).
- [x] 2.3 Add a fetch step that, given `db`, finds every `aws_iam_role` entity's `:aws-iam-role/name`, calls `ListAttachedRolePolicies` per role, and yields `discovered` records for each attached policy - no blanket `ListRoles`/unfiltered call.
- [x] 2.4 Update `describe-all`'s signature to `(describe-all client db)`, threading the new IAM step in alongside the existing EC2 steps; update `sync!` (its only caller) accordingly.

## 3. Sync: composite-key matching for `aws_iam_role_policy_attachment`

- [x] 3.1 Add a composite-match lookup (`:aws-iam-role-policy-attachment/role` + `:aws-iam-role-policy-attachment/policy-arn`, both bound) used when `type` is `"aws_iam_role_policy_attachment"`, alongside `existing-match`'s existing `id-ident`-based path (which continues unchanged, and already no-ops for this type).
- [x] 3.2 Extend `discovered-resource-id` to synthesize `"aws_iam_role_policy_attachment.discovered-<role>-<policy_arn>"` for this type.
- [x] 3.3 Wire both into `resource-tx` so `aws_iam_role_policy_attachment` resources flow through the same discovered/updated/drifted/skipped-already-managed outcomes every other type already gets, with no new outcome kind.

## 4. Sync: removed-child presence marker

- [x] 4.1 Add `managed-resource-ids-by-type` (or similar) to `db.clj`: every Terraform-managed resource's AWS-identifying value (id, or role+policy_arn for the composite type) for a given type, with its entity id.
- [x] 4.2 In `sync!`, after computing this run's observed ids per covered child type (`aws_security_group_rule`, `aws_route`, `aws_route_table_association`, `aws_iam_role_policy_attachment`) from `describe-all`'s (already-filtered) output, diff against the stored managed set for that type: assert `:resource/sync-present? true` on every matched-and-observed entity, `:resource/sync-present? false` on every stored-but-unobserved entity.
- [x] 4.3 Update `resource-tx`'s existing-managed-match branch: force a write (at minimum reasserting `:resource/sync-present? true`) when attributes are unchanged but the match's current `:resource/sync-present?` is `false`, instead of skipping with no tx-data.
- [x] 4.4 Confirm `explicit-route?`/`subnet-association?`-filtered-out AWS-implicit entries never appear in the observed-id sets used by 4.2 (they already don't reach `describe-all`'s output - add a regression test, not new filtering code).

## 5. Query: generalized child/parent join table

- [x] 5.1 Add `child-parent-joins` to `query.clj` per design.md (five entries: SG rule, route, route-table-association x2, IAM attachment).
- [x] 5.2 Add a generic new-child Rule using `child-parent-joins`: managed parent + Discovered (`:resource/managed? false`) child whose FK matches the parent's join attribute.
- [x] 5.3 Add a generic removed-child Rule using `child-parent-joins`: managed parent + managed child with `:resource/sync-present? false` whose FK matches the parent's join attribute.
- [x] 5.4 Refactor `security-groups-with-port-22-open` only if needed to share underlying join helpers - do not change its behavior or the port-22 Policy Check Rule. (Not needed - left unchanged.)

## 6. `GET /drift` response shape

- [x] 6.1 Extend `drift-endpoint`'s response building: a parent entry gains `new_children`/`removed_children` keys (each a list of `{type, id}`) only when non-empty for that parent; existing plain attribute-drift entries keep their current flat shape.
- [x] 6.2 Merge attribute-drift, new-child, and removed-child results into `drifted-resources`' single flat list, keyed by resource so one entry never appears twice for the same resource.

## 7. CLI

- [x] 7.1 Update `trigger-drift-check`'s shape validation and `print-discovered-resource` (or a new printer) so `new_children`/`removed_children` keys are read and surfaced in `drift-check!`'s human-readable output, without breaking on entries that lack them.

## 8. Tests

- [x] 8.1 Sync integration tests: hand-added SG rule / route / route-table-association / IAM attachment on a managed parent -> discovered as usual, no write-path regression.
- [x] 8.2 Sync integration tests: hand-removed managed SG rule / route / route-table-association / IAM attachment -> `:resource/sync-present?` flips to `false`, entity and its attributes otherwise untouched, `GET /state` output for it unchanged.
- [x] 8.3 Sync integration test: removed child reappears in a later Sync run -> `:resource/sync-present?` flips back to `true`.
- [x] 8.4 Query tests: new-child Rule and removed-child Rule, one scenario per covered type per design.md/spec scenarios, including the route-table-association-under-two-parents case.
- [x] 8.5 Query/regression test: `aws_instance.vpc_security_group_ids` membership change is still caught by the existing attribute-diff drift Rule, with no new-child/removed-child mechanism involved.
- [x] 8.6 `GET /drift` endpoint test: response includes `new_children`/`removed_children` only when applicable; existing attribute-drift-only response shape test still passes unchanged.
- [x] 8.7 CLI test: `drift-check!`/`trigger-drift-check` handle a response with `new_children`/`removed_children` present.

## 9. Documentation

- [x] 9.1 Write `docs/adr/0010-detect-removed-child-drift-via-sync-present-marker.md`, following ADR-0009's structure (problem, decision, considered-and-rejected alternatives, trade-offs accepted), per design.md's Decisions section. (Already present from the propose stage; verified it matches the shipped implementation.)
- [x] 9.2 Update CONTEXT.md's "Drift" and/or "Discovered Resource" entries if the new child/parent vocabulary needs a definition (e.g. distinguishing new-child drift and removed-child drift from plain attribute drift).
