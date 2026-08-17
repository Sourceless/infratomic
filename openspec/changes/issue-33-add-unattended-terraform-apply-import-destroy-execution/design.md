## Context

See proposal.md for motivation. Relevant existing state:

- `cli/src/infratomic/cli/main.clj:61-79` (`terraform!`/`capture!`) is the only prior art for shelling out to real `terraform` programmatically, but it's human-driven (`inheritIO`) and lives in the CLI's own `deps.edn` project, unreachable from `state-backend/` without duplication.
- `state-backend/test/infratomic/state_backend/sync_integration_test.clj:40-49` has a test-only `terraform!` using `clojure.java.shell/sh` with `:dir terraform-dir`, throwing `ex-info` with `{:args :out :err}` on non-zero exit — the closest existing pattern for non-interactive, output-capturing invocation.
- Sync and Policy Check both run inside the State Backend process itself, sharing the one dev-local `conn` (CONTEXT.md) — the established pattern any new State-Backend-side capability follows, rather than opening a second connection or process.
- No `:invocation/*` or lock schema exists in `state-backend/src/infratomic/state_backend/db.clj` yet.
- `terraform/provider.tf` uses `backend "http"` with `lock_address`/`unlock_address` deliberately omitted — Terraform's own HTTP-backend locking is not in play here.
- `state-backend/Dockerfile` bundles no `terraform` binary today.

## Goals / Non-Goals

**Goals:**
- A State-Backend-side `apply!`/`import!`/`destroy!` primitive, parameterized by working directory, usable unattended.
- Every invocation persisted as an auditable Datomic entity, unconditionally.
- Per-resource-address locking that survives process restarts and tolerates a crashed holder without manual recovery.
- The published container image can run these commands without extra operator setup.

**Non-Goals:**
- Deciding *when* to call `apply!`/`import!`/`destroy!` (the reconciliation engine, #34).
- Synthesizing Terraform configuration for a previously-unmanaged resource so `import!` has something to bind to (#34's "synthesized import" AC).
- Implementing Terraform's own HTTP-backend `LOCK`/`UNLOCK` protocol (a separate, already-known gap in `provider.tf`; protects against concurrent *non-Infratomic* Terraform runs, a different problem from this change's own concurrent-invocation safety).
- Multi-process/HA deployment of the State Backend itself — the lock design supports it (Datomic-backed, not an in-process mutex) but this change doesn't stand up a second process.

## Decisions

### Location: new `state-backend` namespace, wired into `app-handler`
Mirrors the Sync/Policy Check precedent exactly: a new namespace (e.g. `infratomic.state-backend.terraform`), new routes added to `main.clj`'s `app-handler` cond, closing over the one dev-local `conn` the process already holds. Alternative considered: extracting the CLI's `terraform!`/`capture!` into a shared module both `cli/` and `state-backend/` depend on — rejected because the CLI's version is interactive-shaped (`inheritIO`) and this needs to be unattended and Datomic-aware (writing the Invocation entity and taking the lock inline with the invocation), so sharing code would mean adapting it beyond recognition anyway; a fresh, purpose-built implementation is simpler than threading two very different usage modes through one shared function.

### Process invocation: `clojure.java.shell/sh` with `:dir`, non-interactive
Following the integration test's existing pattern rather than `ProcessBuilder`+`inheritIO` (the CLI's pattern, which requires a live terminal). `sh` returns `{:exit :out :err}` directly, which `apply!`/`import!`/`destroy!` normalize into `{:success (zero? exit) :out ... :err ...}`. Terraform is invoked with flags that make each command non-interactive: `apply -auto-approve`, `destroy -auto-approve -target=<address>`, `import` (already non-interactive once a target address/id pair is given, no plan approval step exists for it).

### Return shape
`{:success true/false :out <string> :err <string>}` — a boolean rather than the raw exit code, so callers (#34) branch on outcome without needing to know Terraform's exit-code conventions. No invocation-entity id is embedded in the return value (see below).

### Invocation entity schema
New Datomic schema, transacted unconditionally after each `apply!`/`import!`/`destroy!` call completes (success or failure) — not conditional on the caller doing anything with the result:
- `:invocation/command` — keyword or string identifying which operation ran (`:apply`, `:import`, `:destroy`)
- `:invocation/resource-address` — string, the target resource address
- `:invocation/success?` — boolean outcome
- `:invocation/at` — instant, when the invocation ran (needed to be useful as an audit trail at all, and is the natural hook #34's "timestamp" AC extends)

Deliberately excludes captured stdout/stderr from the persisted entity: AC #4 only asks for command/resource/outcome, and Datomic dev-local's 4096-byte-per-string limit (the same constraint that already forced "Reconstructed State" instead of raw state storage, per ADR-0002) makes storing large captured Terraform output risky. Captured output is still returned to the immediate caller (`:out`/`:err` in the return map) — just not persisted.

No id is returned to the caller from `apply!`/`import!`/`destroy!`; a caller wanting the persisted record queries for it independently (e.g. by resource address and recency).

### Locking: Datomic-backed, per-resource-address, CAS-safe acquire, TTL-based staleness
A lock entity keyed by resource address, acquired via a single atomic Datomic transaction that both checks no live (non-expired) lock exists for that address and creates the lock entity — not a separate check-then-write pair, which would race under real concurrency. Two schema fields suffice:
- `:lock/resource-address` — unique identity attribute (the lock's key)
- `:lock/acquired-at` — instant, used to compute staleness

Acquisition logic (implemented as a `d/transact` whose transaction function or precondition fails the whole transact if a non-stale lock for the address already exists — Datomic's `:db/cas` or an explicit function-based assertion, decided at implementation time) either succeeds (caller proceeds) or fails because a live lock exists (caller does not proceed with the invocation). Release is a retraction of the lock entity once the invocation completes, in a `finally`-equivalent so a lock is released even if the underlying `terraform` invocation throws.

Staleness threshold: a fixed constant (e.g. 10 minutes) checked at acquisition time — if the existing lock's `:lock/acquired-at` is older than the threshold, treat it as abandoned (its holder presumably crashed) and allow acquisition to proceed as though no lock existed, rather than requiring heartbeat renewal or a human to clear it. Chosen over heartbeat renewal because a single `apply!`/`import!`/`destroy!` invocation targeting one resource address should always complete well within 10 minutes, so a fixed generous threshold needs no extra "renew the lease" machinery and still recovers automatically from a crash.

Alternative considered: an in-process mutex (e.g. a Clojure `ref`/lock map keyed by address) — rejected because it doesn't survive a process restart and doesn't extend to the State Backend eventually running as more than one process, both of which the alignment decision calls out explicitly.

Whole-state locking (one lock for all resources) was considered and rejected in favor of per-address locking specifically so concurrent unattended operations on disjoint resources aren't serialized against each other — only two invocations targeting the *same* address contend.

### Container: bundle a pinned terraform binary
`state-backend/Dockerfile`'s build stage downloads a specific, pinned Terraform CLI release (matching or exceeding `provider.tf`'s `required_version >= 1.5`) from `releases.hashicorp.com` and copies the binary into the final `eclipse-temurin:17-jre-jammy` stage, alongside the existing uberjar. The AWS provider plugin is not baked in — it downloads at `terraform init` time, same as today, since the container already needs network reachability to LocalStack/AWS for any of this to be useful (no new constraint introduced).

### Recording an ADR
This change introduces a new architectural pattern (Datomic-backed distributed locking) not covered by any existing ADR. An ADR should be added during implementation (next available number after `0010`) documenting the CAS-transaction + TTL-staleness approach and the per-resource-address (not whole-state) scope, following the existing ADR style in `docs/adr/`.

## Risks / Trade-offs

- **[Risk]** A 10-minute staleness threshold means a genuinely still-running (not crashed) invocation that legitimately takes longer than 10 minutes could have its lock stolen out from under it, causing two concurrent `terraform` runs against the same resource address. → **Mitigation**: the threshold is chosen to be comfortably longer than any single-resource-targeted `apply`/`import`/`destroy` should take (as opposed to a whole-state apply, which isn't this primitive's shape); if this proves too tight in practice it can be tuned without any spec or schema change.
- **[Risk]** Persisting Invocation entities unconditionally on every call, forever, grows the database without bound. → **Mitigation**: out of scope for this change (no retention/pruning requirement in the issue's ACs); noted here so it isn't silently assumed solved.
- **[Trade-off]** Excluding stdout/stderr from the persisted Invocation entity means the audit trail alone can't explain *why* an invocation failed — only that it did. → Accepted: AC #4 only asks for command/resource/outcome, and the 4096-byte limit makes persisting arbitrary-length output risky; the immediate caller still receives full `:out`/`:err` in the return value for its own use (e.g. surfacing in a UI or its own logs) even though it isn't durably stored.
- **[Risk]** No pinned Terraform binary version is specified by the issue or alignment. → **Mitigation**: pin to `>= 1.5` (matching `provider.tf`'s `required_version`) at implementation time; not a decision this design needs to hold up progress on.
