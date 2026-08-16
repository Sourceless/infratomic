## Why

Infratomic today only runs against an in-process, embedded dev-local Datomic and has no packaged, runnable artifact — there's no Dockerfile, no published image, and no way for anyone outside a dev checkout to try it. Issue #35 asks for a real, network-separated deployment path (state backend talking to Datomic over the network, not in-process) plus the ad-hoc query/rule surface an operator needs to actually poke at a running instance, so the system can be tried out end-to-end instead of only exercised via the test suite.

## What Changes

- Add a **Dev-Local Gateway**: a new, separately-run process that wraps the existing dev-local Datomic behind a wire protocol mirroring `datomic.client.api`'s shape (opaque db/connection handles, generic proxy over client-api-style calls) — genuine network separation without a `com.datomic/client-pro` dependency or my.datomic.com credentials anywhere.
- Add an `INFRATOMIC_DATOMIC_MODE` env var to the State Backend: `embedded` (today's in-process dev-local, default for local dev/test, existing hermetic test suite unchanged) or `gateway` (network call to the Dev-Local Gateway, the container's default).
- Add a thin explicit `bootstrap` command/entrypoint arg that invokes the existing idempotent `ensure-db!` and exits, additive to (not replacing) the implicit bootstrap `-main` already performs on every start.
- **BREAKING** (internal, not user-facing behavior change): unify Rule representation. Rewrite the existing code-defined Rules (e.g. `security-groups-with-port-22-open`) into one stored-Datalog-query data shape — supporting recursive rule sets via `:in $ %` — held in a single atom-backed registry, replacing today's compile-time vector of `(fn [db] -> seq)` values.
- Add an HTTP endpoint that registers new Rules at runtime into that same atom-backed registry, validated through a shared query validator.
- Add an HTTP endpoint that runs ad-hoc Datalog queries against the live db, validated through the same shared validator.
- Add the shared query/rule validator itself: rejects any `:where` clause containing a function-invocation clause, via an explicit allowlist of safe built-in predicates (`< > <= >= =`, etc.) — the one enforcement point for both untrusted-input surfaces (ad-hoc queries and runtime-registered Rules).
- Add a Dockerfile that builds an image running the State Backend service, and CI wiring to publish it to GHCR.
- Add a user guide covering: State Backend, Policy Check, CLI, Sync, Drift detection, network reachability, IAM reachability.

## Capabilities

### New Capabilities
- `dev-local-gateway`: a new, separately-run process wrapping dev-local Datomic behind a client-api-shaped wire protocol, plus the State Backend's `gateway`-mode client that talks to it over the network.
- `ad-hoc-query`: an HTTP endpoint that runs a caller-supplied Datalog query against the live db, rejecting any query containing a function-invocation clause outside an explicit allowlist of safe built-in predicates.
- `container-image`: a Dockerfile building the State Backend into a runnable image, published to GHCR.
- `user-guide`: operator-facing documentation walking through every existing user-facing capability (State Backend, Policy Check, CLI, Sync, Drift detection, network reachability, IAM reachability).

### Modified Capabilities
- `policy-check`: Rules move from a compile-time vector of `(fn [db] -> seq)` values to a single atom-backed registry of stored Datalog-query data (supporting `:in $ %` recursive rule sets, no arbitrary-function escape hatch), validated by the shared query validator; a new HTTP endpoint registers Rules into that registry at runtime, while Rules also stay definable in code (as data, seeded into the same registry at startup).
- `state-backend`: startup gains an `INFRATOMIC_DATOMIC_MODE` switch (`embedded` default for dev/test, `gateway` for the container) governing which Datomic connection path `-main` uses, and a new explicit `bootstrap` entrypoint arg alongside the unchanged implicit bootstrap.

## Impact

- **Affected code**: `state-backend/src/infratomic/state_backend/db.clj` (connection setup, mode switch), `main.clj` (env reading, `bootstrap` arg, new routes), `policy.clj` (Rule registry, runtime registration endpoint), a new query/rule validator namespace, `query.clj` (existing rule-shaped queries migrated to stored-Datalog-query data where they become registry Rules).
- **New code**: a new Dev-Local Gateway process/namespace (likely a new deploy unit under `state-backend/` or a sibling directory), a new ad-hoc query HTTP handler.
- **New files**: root or `state-backend/`-scoped `Dockerfile`, a GHCR-publishing CI workflow (first CI of any kind in this repo), a user guide document.
- **Dependencies**: no new licensed/gated dependencies (`com.datomic/client-pro` explicitly excluded); the Dev-Local Gateway still depends only on `com.datomic/local`.
- **Test suite**: existing hermetic tests (`db/client :mem`, dev-local in-process) continue unchanged under `embedded` mode; new tests needed for `gateway` mode, the validator, runtime Rule registration, and the ad-hoc query endpoint.
- **Out of scope**: real AWS credentials, HTTP API auth/multi-tenancy, Kubernetes/prod deploy manifests, and any real Datomic Pro/Cloud implementation.
