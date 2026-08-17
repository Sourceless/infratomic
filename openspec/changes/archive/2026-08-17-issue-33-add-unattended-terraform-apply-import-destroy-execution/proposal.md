## Why

The reconciliation engine (#34) needs to run `terraform apply`/`import`/`destroy` against the State Backend's own `/state` endpoint without a human at a terminal. The only existing "invoke real `terraform`" code (`cli/src/infratomic/cli/main.clj`'s `terraform!`/`capture!`/`apply-gated!`) is human-driven (`ProcessBuilder` with `inheritIO`), lives in the CLI's own project, and isn't reachable from `state-backend/`. Nothing today records that a Terraform invocation happened, and concurrent unattended invocations against the same resource have no safety net. #33 builds the unattended execution primitive itself; deciding *when* to call it is out of scope (#34).

## What Changes

- Add a new `state-backend` namespace exposing `apply!`, `import!`, and `destroy!` — each shells out to real `terraform` (`apply`, `import <address> <id>`, `destroy -target=<address>`) against a caller-supplied working directory, non-interactively, and returns `{:success true/false :out ... :err ...}`.
- `import!` is a pure executor: it assumes a resource block for the target address already exists in the working directory's config. It does not synthesize config.
- Add a new Datomic schema for an Invocation entity (`:invocation/*`) recording command, resource address, and outcome — written unconditionally by `apply!`/`import!`/`destroy!` on every call, not dependent on the caller remembering to record it.
- Add a new Datomic-backed, per-resource-address lock: `apply!`/`import!`/`destroy!` acquire a lock on their target resource address before running (via an atomic Datomic transaction, not a check-then-write race) and release it when done; a stale lock (holder crashed) expires via TTL and can be reacquired without manual intervention.
- Wire the three functions into `app-handler` behind new HTTP routes, following the existing Sync/Policy Check pattern (new namespace, sharing the one dev-local `conn`).
- Bundle a pinned `terraform` CLI binary into `state-backend/Dockerfile`'s final image so the capability works from the deployed container, not just dev/test.

## Capabilities

### New Capabilities
- `terraform-execution`: unattended `apply!`/`import!`/`destroy!` execution against a caller-supplied Terraform working directory, with per-invocation outcome logging and per-resource-address locking to keep concurrent invocations on the same resource safe.

### Modified Capabilities
(none — this does not change `terraform-cli`'s or `state-backend`'s existing requirements; it adds a new, separate capability)

## Impact

- New source: a `state-backend` namespace (e.g. `infratomic.state-backend.terraform`) for `apply!`/`import!`/`destroy!`.
- `state-backend/src/infratomic/state_backend/db.clj`: new schema for `:invocation/*` and a resource-address lock entity.
- `state-backend/src/infratomic/state_backend/main.clj`: new routes wired into `app-handler`.
- `state-backend/Dockerfile`: bundle a pinned `terraform` binary into the final image stage.
- New tests (unit + integration) exercising `apply!`/`import!`/`destroy!` against the LocalStack sample-app Terraform config, verifying invocation logging and lock behavior.
- No change to the CLI (`cli/`) or to Terraform's own `backend "http"` LOCK/UNLOCK protocol (both explicitly out of scope, per the alignment decision).
