## 1. Schema

- [ ] 1.1 Add `:invocation/*` schema to `state-backend/src/infratomic/state_backend/db.clj`: `:invocation/command`, `:invocation/resource-address`, `:invocation/success?`, `:invocation/at`
- [ ] 1.2 Add lock schema to `db.clj`: `:lock/resource-address` (unique identity), `:lock/acquired-at`
- [ ] 1.3 Wire new schema into `db/ensure-db!`'s bootstrap transaction alongside the existing schema

## 2. Locking

- [ ] 2.1 Implement lock acquisition as a single atomic Datomic transaction (CAS-safe: fails if a non-stale lock for the address already exists, succeeds and creates the lock otherwise)
- [ ] 2.2 Implement staleness check (fixed TTL constant, e.g. 10 minutes) so an existing lock older than the threshold is treated as absent for acquisition purposes
- [ ] 2.3 Implement lock release (retraction of the lock entity), guaranteed to run even if the wrapped `terraform` invocation throws
- [ ] 2.4 Unit tests: acquiring a free lock succeeds; acquiring a held (non-stale) lock fails; acquiring a stale lock succeeds; release makes the address acquirable again

## 3. Terraform execution primitive

- [ ] 3.1 Create new namespace (e.g. `infratomic.state-backend.terraform`) with `apply!`, `import!`, `destroy!`, each taking a working-directory argument (and resource-address/AWS-id args as applicable)
- [ ] 3.2 Implement process invocation via `clojure.java.shell/sh` with `:dir <working-directory>`, non-interactive flags (`apply -auto-approve`, `destroy -auto-approve -target=<address>`, `import <address> <id>`)
- [ ] 3.3 Normalize `sh`'s `{:exit :out :err}` into `{:success (zero? exit) :out ... :err ...}`
- [ ] 3.4 Wrap each of `apply!`/`import!`/`destroy!` with: acquire lock on target resource address -> run terraform -> persist Invocation entity (unconditionally, success or failure) -> release lock -> return result
- [ ] 3.5 `import!` does not write or modify any Terraform configuration — it only shells `terraform import <address> <id>` against the caller-supplied directory as-is

## 4. HTTP wiring

- [ ] 4.1 Add routes to `state-backend/src/infratomic/state_backend/main.clj`'s `app-handler` for apply/import/destroy, following the existing Sync/Policy Check pattern (closing over the shared `conn`)
- [ ] 4.2 Return appropriate HTTP status/body reflecting `{:success ...}` for each route; non-matching methods get an explicit `405` per existing convention

## 5. Container image

- [ ] 5.1 Update `state-backend/Dockerfile`'s build stage to download a pinned Terraform CLI release (>= 1.5, matching `terraform/provider.tf`'s `required_version`) from releases.hashicorp.com
- [ ] 5.2 Copy the terraform binary into the final `eclipse-temurin:17-jre-jammy` image stage alongside the existing uberjar
- [ ] 5.3 Verify the built image can invoke `terraform version` (or equivalent smoke check)

## 6. Tests

- [ ] 6.1 Integration test: `apply!` against the LocalStack sample-app Terraform config succeeds, and `terraform show` on the resulting state matches what was applied
- [ ] 6.2 Integration test: `import!` against a resource address with a pre-existing config block succeeds
- [ ] 6.3 Integration test: `destroy!` with a target address succeeds and only removes the targeted resource
- [ ] 6.4 Test: a failing `terraform` invocation (e.g. invalid config or unreachable provider) is reported as `{:success false ...}`, not an unhandled exception
- [ ] 6.5 Test: every invocation (success and failure) creates an `:invocation/*` entity with the right command, resource address, and outcome
- [ ] 6.6 Test: two concurrent invocations targeting the same resource address serialize (the second does not start `terraform` until the first releases its lock); invocations on different addresses proceed concurrently
- [ ] 6.7 Test: a stale (past-TTL) lock can be reacquired by a new invocation

## 7. Documentation

- [ ] 7.1 Add an ADR under `docs/adr/` (next available number after `0010`) documenting the Datomic-backed per-resource-address locking approach (CAS-safe acquisition, TTL-based staleness) and why whole-state/in-process-mutex alternatives were rejected
- [ ] 7.2 Update `CONTEXT.md` with any new terminology this change introduces (e.g. Invocation, if it becomes a load-bearing term other capabilities reference)
