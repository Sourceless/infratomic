## 1. Schema

- [ ] 1.1 Add `:reconciliation/*` schema to `db.clj`: `:reconciliation/resource` (ref), `:reconciliation/rule` (keyword), `:reconciliation/action` (`:reconciliation.action/apply` / `:reconciliation.action/import-destroy` / `:reconciliation.action/none`), `:reconciliation/invocation` (optional ref), `:reconciliation/at` (instant)

## 2. Child-binding companion query

- [ ] 2.1 Add `offending-port-22-rules-for-sg` (or equivalent name) to `query.clj`: given a security group's `:resource/id`/entity, returns the specific `aws_security_group_rule` entities matching the same port-22/`0.0.0.0/0` predicate as `security-groups-with-port-22-open-rule`
- [ ] 2.2 Unit-test it directly against a db value with a security group that has both an offending and a non-offending ingress rule, confirming only the offending rule is returned

## 3. Config synthesis (`terraform.clj`)

- [ ] 3.1 Add per-type import-id derivation: direct passthrough of the stored `id` attribute for id-space-matched types (e.g. `aws_security_group`), and a composite-id builder for `db/id-space-mismatched-types` (`aws_security_group_rule`: `<security_group_id>_<type>_<protocol>_<from_port>_<to_port>_<source>`; `aws_route`)
- [ ] 3.2 Add scratch-working-directory creation: fresh temp directory per invocation, minimal provider/backend config written into it
- [ ] 3.3 Add `synthesize-import-and-destroy!` (or equivalent): writes the `import { to = <resource/id>, id = <derived-id> }` block, runs `terraform plan -generate-config-out`, `terraform apply -auto-approve`, `terraform destroy -target=<address> -auto-approve` in sequence within the scratch directory
- [ ] 3.4 Wrap the sequence with the existing `with-lock-and-invocation` so it locks/records an Invocation the same way `apply!`/`import!`/`destroy!` do
- [ ] 3.5 Discard the scratch directory unconditionally in a `finally`, regardless of success or failure at any step
- [ ] 3.6 Integration test (LocalStack): a hand-created (out-of-band) `aws_security_group` is imported, config-generated, applied, and destroyed via this path; assert it no longer exists in LocalStack afterward and the scratch directory is gone
- [ ] 3.7 Integration test: same sequence for an `aws_security_group_rule` (id-space-mismatched type), confirming the composite import id resolves correctly

## 4. Reconciliation decision logic (`reconcile.clj`, new namespace)

- [ ] 4.1 Create `infratomic.state-backend.reconcile` with a `reconcile!` entry point taking `conn`
- [ ] 4.2 Evaluate every registered Rule (via `policy.clj`'s `run-rule`, reused as-is) against `(d/db conn)` directly — live state, not a speculative plan-derived db
- [ ] 4.3 For each violating bound entity, resolve the concrete remediation target: if the Rule binds a parent whose actual violation is attributable to a child (e.g. the port-22 SG rule), resolve the specific child entity/entities via the companion query from task 2.1; otherwise the bound entity itself is the target
- [ ] 4.4 Dispatch per resolved target based on its own `:resource/managed?`: managed and drifted (per `drifted-resources`/the drift Rule) → `apply!`; not managed → `synthesize-import-and-destroy!`; managed and not drifted → no action
- [ ] 4.5 Write a `:reconciliation/*` record for every violating target on every pass, with the correct action and, when applicable, a reference to the Invocation entity the action produced
- [ ] 4.6 Confirm no action and no `:reconciliation/*` record is produced for a resource that violates no registered Rule

## 5. Wiring

- [ ] 5.1 Call `reconcile/reconcile!` as the final step of `sync.clj`'s `sync!`, after discovery/ingestion completes, for both on-demand and scheduled invocations
- [ ] 5.2 Confirm `wrap-failure-isolated` (or equivalent) still isolates a reconciliation failure the same way it isolates a sync failure, so one bad cycle doesn't kill the scheduler

## 6. End-to-end verification

- [ ] 6.1 Extend `sync_integration_test.clj`'s `create-out-of-band-security-group!` fixture into a full reconciliation test: hand-create a security group with 0.0.0.0/0:22 open via LocalStack (bypassing Terraform), trigger a sync+reconcile cycle, assert the resource no longer exists in LocalStack and a `:reconciliation/*` record exists for it with action `:import-destroy`
- [ ] 6.2 Add an integration test for the New-Child-Drift path: an already Terraform-managed security group gains a rogue out-of-band ingress rule; after sync+reconcile, assert only the rogue rule is destroyed (the managed security group itself still exists) and a `:reconciliation/*` record exists for the rule
- [ ] 6.3 Add an integration test for the managed-and-drifted path: a managed resource's config is policy-violating and its live value has drifted; after sync+reconcile, assert `terraform apply` ran (an Invocation with command `apply` exists) and a `:reconciliation/*` record with action `:apply` exists
- [ ] 6.4 Add an integration test for the managed-and-non-drifted path: register a Rule that a currently-deployed, non-drifted managed resource violates; after sync+reconcile, assert no Invocation was created for it and a `:reconciliation/*` record with action `:none` exists
- [ ] 6.5 Add a test confirming a fully compliant resource produces neither an Invocation nor a `:reconciliation/*` record across a sync+reconcile cycle

## 7. Documentation

- [ ] 7.1 Update CONTEXT.md: add/update glossary entries for Reconciliation, the `:reconciliation/*` entity, and config synthesis; update Drift's entry to reflect that Drift is no longer purely "detection only"
