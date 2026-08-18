## Why

Sync and drift detection can already see when a resource violates policy — via out-of-band drift or as a Discovered Resource — but nothing acts on that finding. A policy bypass (a hand-created security group, a rogue ingress rule added directly against LocalStack) stays exploitable indefinitely until a human notices and intervenes by hand. Closing this loop is the last sub-ticket of epic #30.

## What Changes

- Reconciliation runs automatically as the last step of every `sync!` invocation (on-demand and scheduled) — no separate `/reconcile` endpoint.
- For every managed resource, reconciliation evaluates the policy Rule registry directly against live state (independent of drift status) and dispatches per violating resource based on whether that specific resource is itself Terraform-managed:
  - Managed and drifted → `terraform apply` against the existing shared working directory (reuses the existing unattended `apply!` primitive as-is).
  - Unmanaged (a Discovered Resource, or the specific offending child resource of an otherwise-managed parent, e.g. new-child drift) → synthesized `terraform import` + `terraform destroy` in a dedicated scratch working directory.
  - Managed and non-drifted but still violating (e.g. a rule registered after deploy) → no remediation action is taken; the violation is recorded (action `:none`).
- New capability: synthesized import, using Terraform's native `import` block plus `terraform plan -generate-config-out` plus `terraform apply` — the AWS provider generates the resource body itself; no attribute-map-to-HCL serialization is written by this codebase. Runs in a scratch working directory created fresh per invocation and discarded unconditionally afterward, isolated from the shared `terraform/` directory.
- A companion query resolves the offending child entity (e.g. the specific `aws_security_group_rule`) for a policy rule that only binds a parent resource, without generalizing the existing Rule registry's shape.
- New persisted entity type, `:reconciliation/*`, records every remediation decision: resource, rule, action taken (`:apply`/`:import-destroy`/`:none`), timestamp, and an optional reference to the Invocation entity when one was created. Decoupled from `:invocation/*`, whose meaning stays "an execution attempt, not a finding."
- Resources with no policy violation are left completely untouched — no remediation action, no record.

## Capabilities

### New Capabilities
- `policy-reconciliation`: automatic, per-resource evaluation of live state against the policy Rule registry on every Sync, dispatching to the correct remediation path (apply / synthesized import+destroy / record-only) and persisting a `:reconciliation/*` record of every decision.
- `terraform-config-synthesis`: generates and applies a Terraform `import` block for a previously-unmanaged resource (or offending child resource) using `terraform plan -generate-config-out`, in an isolated, per-invocation scratch working directory, then destroys the now-imported resource.

### Modified Capabilities
(none — the existing `resource-sync`, `policy-check`, `drift-detection`, and `terraform-execution` capabilities keep their current requirements unchanged; reconciliation is a new consumer of `sync!`'s completion and of the existing `apply!` primitive, not a change to their contracts)

## Impact

- `state-backend/src/infratomic/state_backend/reconcile.clj` (new): decision logic — evaluates the Rule registry against live state per managed resource, resolves the managed/unmanaged dispatch (including the child-binding companion query), calls into `terraform.clj`'s `apply!` or the new synthesis capability, and writes `:reconciliation/*` records.
- `state-backend/src/infratomic/state_backend/terraform.clj`: adds the import-block/generate-config-out synthesis + scratch-working-directory capability alongside existing `apply!`/`import!`/`destroy!`; those existing primitives are reused unmodified.
- `state-backend/src/infratomic/state_backend/query.clj`: adds `offending-port-22-rules-for-sg` (or equivalent), the reconciliation-only companion query that resolves the specific violating child entity for the existing `security-groups-with-port-22-open-rule`.
- `state-backend/src/infratomic/state_backend/db.clj`: adds `:reconciliation/*` schema.
- `state-backend/src/infratomic/state_backend/sync.clj`: `sync!` calls reconciliation as its final step.
- `state-backend/test/infratomic/state_backend/sync_integration_test.clj`: extends the existing `create-out-of-band-security-group!` fixture into an end-to-end reconciliation verification (hand-created violating SG is destroyed, a reconciliation record exists).
