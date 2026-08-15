## Why

Sync's drift mechanism (#27/#29) only catches an out-of-band change to a managed resource's *own* attributes. A new out-of-band *child* resource appearing under a managed parent (e.g. hand-adding a `0.0.0.0/0:22` `aws_security_group_rule` directly to a managed `aws_security_group`) is currently indistinguishable from an entirely unrelated Discovered Resource - it is never joined back to the managed parent it actually constitutes drift on. The symmetric case - a previously-known child disappearing out-of-band - has no detection signal at all today. Issue #32's alignment closes both gaps, generalized across every FK-bearing child type this system models, not just security group rules.

## What Changes

- Add a query-time Datalog join, generalizing the existing `security-groups-with-port-22-open` join precedent, that finds "new children" of a managed parent across four FK-bearing child types: `aws_security_group_rule` -> `aws_security_group`, `aws_route` -> `aws_route_table`, `aws_route_table_association` -> `aws_route_table`/`aws_subnet`, and `aws_iam_role_policy_attachment` -> `aws_iam_role`. No changes to Sync's write path or `resource-tx`'s persisted outcome kinds - a new out-of-band child keeps persisting exactly as it does today (a plain Discovered Resource); the join is purely additive at query time.
- Add wholly new Sync fetch/matching infrastructure for `aws_iam_role_policy_attachment`: an IAM `ListAttachedRolePolicies` call per known `aws_iam_role`, a new `iam-role-policy-attachment->attrs` translation function, and composite `(role, policy_arn)` matching (this type has no modeled `"id"` key, unlike the other three FK-bearing types, which are id-based).
- Reuse the existing `explicit-route?`/`subnet-association?` filters when building `aws_route`/`aws_route_table_association` new-child detection, so AWS-implicit entries (the default local route, the main-table association) are never flagged as false-positive drift.
- Add removed-child detection: a previously-known managed child that's gone missing from a live Sync fetch is flagged via an additive, history-based Datomic signal - never via full entity retraction (`GET /state` reconstruction must never be affected). The exact mechanism is designed in this change's design.md and recorded in a new ADR, following ADR-0009's precedent (write-source tag + Datomic history) for the analogous attribute-drift problem.
- Extend `GET /drift`'s response shape: a parent entry in the existing flat `drifted` list gains two new, independently-optional keys, `new_children` and `removed_children`, each a list of `{type, id}` objects. Existing plain attribute-drift entries are unaffected.
- Update the CLI (`trigger-drift-check`/`drift-check!`/its resource-printing helper) to not break on, and to surface, the new keys.
- Add a regression test confirming `aws_instance.vpc_security_group_ids` membership changes remain covered by the existing (shipped) attribute-diff drift mechanism - explicitly no new detection logic for that attribute.

## Capabilities

### New Capabilities

(none - this change extends two existing capabilities, it introduces no new one)

### Modified Capabilities

- `drift-detection`: adds a new-child/removed-child detection Rule (a query-time join across the four FK-bearing child types), extends `GET /drift`'s response shape with per-parent `new_children`/`removed_children` keys, and states the "detection only, never a live-state mutation" constraint explicitly for the removed-child case.
- `resource-sync`: adds `aws_iam_role_policy_attachment` fetch/translation/matching to Sync (the one FK-bearing child type with no existing Sync coverage at all), matched by composite `(role, policy_arn)` rather than a single AWS-assigned id.

## Impact

- `state-backend/src/infratomic/state_backend/sync.clj`: new IAM fetch call, translation function, and composite-key matching for `aws_iam_role_policy_attachment`; `describe-all` extended to include it.
- `state-backend/src/infratomic/state_backend/query.clj`: new generalized new-child join Rule, new removed-child detection Rule (history-based), `drift-endpoint`'s response-shape extended.
- `state-backend/src/infratomic/state_backend/db.clj`: possible new schema/history-support additions for the removed-child mechanism (see design.md).
- `cli/src/infratomic/cli/main.clj`: `trigger-drift-check`/`drift-check!`/resource-printing updated for the new response keys.
- `docs/adr/00NN-<slug>.md`: new ADR documenting the removed-child Datomic mechanism, following ADR-0009's precedent.
- `openspec/specs/drift-detection/spec.md`, `openspec/specs/resource-sync/spec.md`: updated via this change's delta specs.
