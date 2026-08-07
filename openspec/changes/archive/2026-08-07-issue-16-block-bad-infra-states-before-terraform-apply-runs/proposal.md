## Why

Right now nothing stops `terraform apply` from creating a resource that violates a known-bad policy (e.g. a security group open to the world on port 22) — a bad resource only shows up *after* it's already live in AWS and posted to the State Backend. A thin `apply`-intercepting CLI that checks a plan against policy rules before it ever reaches `terraform apply` closes that gap, catching the violation while it's still just a plan.

## What Changes

- Add a new **Policy Check** HTTP endpoint to the State Backend service: given a Terraform plan's `planned_values.root_module.resources[]` (from `terraform show -json`), it decomposes those resources into the same tx-shape `db.clj`'s existing `decompose-attributes`/`resource-attr-tx` already produce for state, speculatively transacts them via `d/with` (never `d/transact`), evaluates a static vector of Rules against the resulting db, and returns structured Violations (which rule, which resource) as JSON. Runs inside the existing State Backend process so it reuses the one dev-local connection the process already holds.
- Add new plan-decomposition glue code (distinct from the state-shaped `handler.clj`) that adapts a plan resource's `type`/`name`/`values` into `db.clj`'s expected shape, and substitutes an **Address Stand-in** (the resource's own Terraform address, or a directly-referenced resource's address, per `configuration.root_module.resources[].expressions.*.references`) for any modeled identifying attribute that is still unknown (`null`) at plan time — so identity-based Rule joins still match a not-yet-applied resource. See `docs/adr/0004-resolve-plan-time-references-to-address-stand-ins.md`.
- Add a Rule contract (`(fn [db] -> seq-of-maps)`, matching `query.clj`'s existing function shape) and a static vector of Rules containing exactly one entry to start: the existing `security-groups-with-port-22-open`, reused completely unmodified.
- Add a new top-level `cli/` project (`infratomic.cli.main`, invoked via `clojure -M -m infratomic.cli.main -- <terraform args...>`) that shells out to the real `terraform` binary, passing every subcommand's args/flags/stdio/exit code straight through **except** `apply`, which it intercepts: run `terraform plan -out=tfplan`, `terraform show -json tfplan`, POST the plan JSON to the Policy Check endpoint, and either (no violations) run the real `terraform apply tfplan` and pass through its exit code/output, or (violations) print each violation's rule and resource and exit non-zero without ever calling real `apply`.

## Capabilities

### New Capabilities
- `policy-check`: the State Backend's new endpoint and supporting plan-decomposition/Address-Stand-in/Rule-evaluation logic that speculatively checks a Terraform plan against policy Rules and returns structured Violations.
- `terraform-cli`: the new top-level wrapper CLI that intercepts `terraform apply` to gate it on a Policy Check, and passes every other subcommand straight through unchanged.

### Modified Capabilities
(none — `security-groups-with-port-22-open` and `resource-query` are reused unmodified; no existing requirement's behavior changes)

## Impact

- New code: `cli/` (new `deps.edn` project, `infratomic.cli.main`), and new namespaces inside `state-backend/` for plan decomposition, Address Stand-in resolution, the Rule vector, and the Policy Check endpoint/handler wiring into `main.clj`.
- No changes to `state-backend/src/infratomic/state_backend/handler.clj`, `query.clj`'s rule logic, or the existing `/state` HTTP contract.
- New dependency: the CLI shells out to the real `terraform` binary as a subprocess.
- Sample app verification (`terraform/`) is exercised via the new CLI instead of the raw `terraform` binary for the acceptance scenario, but no `.tf` files change.
