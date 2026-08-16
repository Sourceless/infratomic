## Context

See `proposal.md` for motivation. Binding constraints from the issue's alignment decisions (this design implements them, not re-derives them):

- No `com.datomic/client-pro` dependency and no my.datomic.com credentials anywhere (build, CI, runtime) — the Dev-Local Gateway is built entirely on `com.datomic/local`, the same dependency the State Backend already has.
- The Dev-Local Gateway's wire protocol only needs to *mirror the shape* of `datomic.client.api` (opaque db/connection handles, same conceptual request/response cycle) — it is not required to be wire-compatible with real Datomic Pro/Cloud, and does not need to implement the full client-api surface, only the operations this codebase actually calls.
- `INFRATOMIC_DATOMIC_MODE` must default to `embedded` behavior identical to today's, so the existing hermetic test suite (`db/client :mem`, in-process dev-local) keeps working unchanged.
- Rules unify onto one stored-Datalog-query data representation; only `policy.clj`'s Policy Check Rule registry is being unified (today one entry: `security-groups-with-port-22-open`). `query.clj`'s `reaches-rules`/`chain-rules` (network/IAM reachability) are a different mechanism — bespoke recursive rule sets backing their own dedicated query functions/endpoints (`/state`... `reachable?`, `iam-reachable?`), not Policy Check Rules — and are **not** being moved into the registry. They matter here only as the reason the stored Rule *format* must be able to express `:in $ %` recursive rule sets, even though today's one registered Rule doesn't need recursion — a Rule registered at runtime via the new HTTP endpoint might.
- Every prior State Backend feature (Sync, Drift, Policy Check) is a same-process HTTP route sharing the one long-lived `conn` built once at `-main` startup. The new ad-hoc query and rule-registration routes follow the same shape.

## Goals / Non-Goals

**Goals:**
- Prove the State Backend is not hard-wired to an in-process embedded db, via genuine network separation to a Dev-Local Gateway process, without requiring the literal Datomic Pro product.
- Keep `db.clj`/`query.clj`/`policy.clj`/`sync.clj` — the code that actually calls `d/q`/`d/pull`/`d/transact`/`d/with`/`d/history`/`d/as-of` — unchanged in *how* they call these operations, regardless of `INFRATOMIC_DATOMIC_MODE`.
- Give registered Rules and ad-hoc queries one shared representation and one shared safety boundary (the validator), so there is exactly one place that decides what Datalog is safe to run on untrusted input.
- Ship a runnable, GHCR-published container plus enough operator documentation to actually try the whole system end to end.

**Non-Goals:**
- Wire-compatibility with real Datomic Pro/Cloud's actual transit/msgpack protocol — only the *conceptual shape* (opaque handles, client-api-style operations) is mirrored, per the alignment decision.
- Implementing the full `datomic.client.api` surface in the Dev-Local Gateway — only the operations this codebase's four namespaces actually call.
- Moving `reaches-rules`/`chain-rules`/`grants` into the Rule registry, or exposing network/IAM reachability through the ad-hoc query endpoint's Rule mechanism — out of scope per the alignment decision.
- Auth/multi-tenancy on any HTTP endpoint, Kubernetes manifests, or a real Datomic Pro/Cloud backend — explicitly out of scope per the issue.

## Decisions

### A `datomic` facade namespace, not a runtime-polymorphic `datomic.client.api`

Today every namespace that touches the db does `(:require [datomic.client.api :as d])` and calls `d/q`, `d/pull`, `d/transact`, `d/with`, `d/with-db`, `d/db`, `d/history`, `d/as-of`, `d/create-database`, `d/connect`, `d/client` directly. `INFRATOMIC_DATOMIC_MODE` needs those same call sites to work against either the real dev-local client (`embedded`) or a Dev-Local Gateway HTTP client (`gateway`), decided once at process start, not per call.

A new namespace, `infratomic.state-backend.datomic`, re-exports the same function names (`q`, `pull`, `transact`, `with`, `with-db`, `db`, `history`, `as-of`, `create-database`, `connect`, `client`) as thin multimethods/protocol functions dispatching on the concrete type of the `client`/`conn`/`db` value passed in. `db.clj`, `query.clj`, `policy.clj`, and `sync.clj` change their require from `datomic.client.api` to this facade (still aliased `d` — call sites are textually unchanged beyond the one `:require` line). `db/client` becomes the single place that reads `INFRATOMIC_DATOMIC_MODE` and constructs either:
- an `EmbeddedClient` wrapping a real `datomic.client.api` client (`embedded` mode — the facade's implementation for this type just delegates straight to `datomic.client.api`, zero behavior change), or
- a `GatewayClient` wrapping an HTTP connection to the configured Dev-Local Gateway host/port (`gateway` mode).

**Alternative considered**: branch on mode at every call site (`(if gateway? (gateway/q ...) (d/q ...))`). Rejected — this is exactly the "hard-wired to embedded" shape the issue is trying to prove the backend *doesn't* have, scattered through four namespaces instead of centralized in one.

**Alternative considered**: make the Dev-Local Gateway speak real Datomic Pro's actual wire protocol so the State Backend could use the unmodified `datomic.client.api` (via `:server-type :peer-server`) against it. Rejected — that protocol is undocumented/proprietary and not the point; the alignment decision explicitly asks only for shape-mirroring, and this would be significant unnecessary complexity for no benefit within this issue's scope.

### Dev-Local Gateway: opaque handles over HTTP+EDN

The Dev-Local Gateway is a new, separately-run Clojure process (own `-main`, own deps.edn or an alias in the existing one) that:
1. Holds the real `com.datomic/local` client and every `conn`/`db` value it hands out in a server-side, atom-backed session registry, keyed by opaque string handles (random UUIDs) — mirroring how a real Datomic Pro peer-server keeps db values addressable by a small handle (basis-t) rather than shipping the whole index over the wire.
2. Exposes one HTTP endpoint per client-api operation this codebase needs: `create-database`, `connect`, `db`, `q`, `pull`, `transact`, `with`, `with-db`, `history`, `as-of`. Request and response bodies are EDN (`Content-Type: application/edn`), not JSON — Datalog queries and tx-data are already plain Clojure data (vectors/lists of symbols and keywords); EDN round-trips them exactly, where JSON would need an invented symbol-quoting scheme for no benefit.
3. For any operation whose real client-api return value is itself a client/conn/db object (`connect` → conn, `db`/`with-db` → db, `history`/`as-of` → db, `with` → a map whose `:db-after`/`:db-before` are db values), the gateway registers the new object under a fresh handle and returns the handle (or a map with handles substituted for the object) instead of the object itself.
4. For any operation taking a client/conn/db argument (`q`, `pull`, `transact`, `with`, `with-db`, `history`, `as-of`), the request carries the handle(s); the gateway resolves them back to the real registered objects before delegating to the real `datomic.client.api` call.

On the State Backend side, `GatewayClient`/`GatewayConn`/`GatewayDb` (the facade's gateway-mode types) wrap exactly one thing: the opaque handle string plus the configured gateway base URL. The facade's `q`/`pull`/etc. implementations for these types POST the operation's EDN-encoded arguments (substituting each Gateway* value with its handle) to the corresponding endpoint and EDN-decode the response, re-wrapping any handle found in the response into the matching `Gateway*` type.

**Alternative considered**: have the gateway serialize and ship whole db/conn state across the wire on every call. Rejected — real Datomic client-api values are already handle-like specifically because full index data isn't meant to cross a client/server boundary; opaque handles are both truer to the shape being mirrored and far simpler to implement.

**Alternative considered**: a single generic RPC endpoint (`POST /call {:op "q" :args [...]}`) instead of one endpoint per operation. Rejected as a minor implementation choice, not a design-significant one — one-endpoint-per-operation was chosen for simpler request routing and clearer per-operation error responses (e.g. a malformed `:where` returns a `400` from `/q` specifically), but either shape satisfies the alignment decision equally; noted here so the choice isn't silently redecided during implementation.

### Storage backing the Dev-Local Gateway

The Dev-Local Gateway wraps a real dev-local storage directory exactly like today's embedded `db/client` does (`:server-type :datomic-local`, a `:storage-dir`) — it is the same dev-local database engine, just running in its own process instead of in-process with the State Backend. Run as a sibling container/process (e.g. a second service in `docker-compose.yml` for local `gateway`-mode dev/testing, and a second container alongside the published State Backend image for the `docker run` verification flow in the issue's "How to verify").

### Unified stored Rule format

A Rule is stored as one EDN/Clojure map:

```clojure
{:rule/id    :security-groups-with-port-22-open   ; unique keyword, chosen by the registrant
 :rule/find  '[?sg]                                ; :find clause — SHALL bind exactly the resource entity/id var(s) the Rule flags
 :rule/in    '[$]                                  ; :in clause — '[$ %] when :rule/rule-defs is present, else '[$]
 :rule/where '[[?sg :aws-security-group/id ?sg-id]
               [?rule :aws-security-group-rule/security-group-id ?sg-id]
               ...]
 :rule/rule-defs nil}                              ; optional: a Datomic rule-vector (the `%` argument) for recursive rule sets
```

This is the one representation for both code-defined and runtime-registered Rules. `policy.clj`'s registry becomes an atom, `(atom {})`, keyed by `:rule/id`, seeded at namespace load with the existing rule(s) expressed in this shape (`security-groups-with-port-22-open` is representable as-is — its `:where` uses only `<=`/`>=`, both allowlisted predicates, and needs no `%` rule set). Evaluation (`policy/evaluate`, and the new ad-hoc query endpoint) runs `(d/q {:find (:rule/find rule) :in (:rule/in rule) :where (:rule/where rule)} db & (when (:rule/rule-defs rule) [(:rule/rule-defs rule)]))`, then resolves each bound `?sg`-style value back to a `:resource/id ` `:resource/type` pair via a pull, exactly as `evaluate` does today — the pull happens in the shared evaluation code, not embedded in the Rule's own query, so a Rule's `:find` only ever needs to bind the entity/id it's flagging.

**Alternative considered**: keep `:rule/query` as a `(fn [db] -> seq)` for code-defined Rules and add a second, data-shaped path only for runtime-registered ones. Rejected per the alignment decision (one representation, not two divergent Rule models).

### Shared query/rule validator

One function, `(validate-query {:find ... :in ... :where ...} rule-defs)`, used by both the ad-hoc query endpoint and Rule registration, walks every `:where` clause — recursing into `not`/`not-join`/`or`/`or-join` sub-clauses and into every rule body in `rule-defs` when a `%` rule set is supplied — and rejects the query if any clause is a function/predicate-invocation clause (Datalog's `[(sym args...) binding?]` shape — a vector whose first element is a list) whose `sym` is not in a fixed, explicit allowlist: `#{'< '> '<= '>= '= 'not= '==}`. A bare-list clause like `(reaches ?src ?dst)` is a **rule invocation**, not a function-invocation clause, and is never rejected by this check — only vector-wrapped-list predicate clauses are — and a rule invocation naming a rule absent from the supplied `%` set simply fails at `d/q` time with Datomic's own error, needing no extra validator logic. Returns `{:valid? true}` or `{:valid? false :reason "..."}`; both the ad-hoc query endpoint and the Rule-registration endpoint respond `400` with the reason on rejection.

Applied to: the ad-hoc query endpoint's request body, and a Rule's `:where` + `:rule/rule-defs` at registration time (not re-checked on every evaluation — a Rule already accepted into the registry is trusted at query time, same as a plain `d/q` call anywhere else in the codebase).

### Ad-hoc query HTTP endpoint

`POST /query`, `Content-Type: application/edn`, body is a Datalog query map (`{:find ... :in ... :where ...}`, optionally with a `%` rule-set argument alongside `$` in `:in` and a corresponding rule-defs value). Validated via the shared validator; on success, run against `(d/db conn)` and the response is the raw `d/q` result set, EDN-encoded. No result-shape assumption (unlike Rules, which the registry knows flag resources) — arbitrary `:find` shapes are allowed as long as the `:where`/rule bodies pass validation.

### Runtime Rule registration HTTP endpoint

`POST /rules`, `Content-Type: application/edn`, body is a Rule map (`:rule/id`, `:rule/find`, `:rule/in`, `:rule/where`, optional `:rule/rule-defs`). Validates via the shared validator; on success, `swap!`s the Rule into the atom-backed registry (an existing `:rule/id` is overwritten — registration is an upsert, matching every other upsert-by-id behavior already in this codebase) and responds `200`; a registered-at-runtime Rule is immediately visible to the next Policy Check, since `policy/evaluate` reads the registry atom fresh on every call rather than closing over a snapshot.

### `bootstrap` entrypoint

`-main`'s arg-dispatch grows one new branch: `clojure -M -m infratomic.state-backend.main bootstrap` (equivalently, the container's entrypoint with `bootstrap` as its arg) calls `(db/ensure-db! (db/client))` and exits `0`, printing a short confirmation, instead of starting Jetty. The implicit call inside `-main`'s normal (no-arg) startup path is untouched — both paths call the exact same `ensure-db!`, so there is only ever one bootstrap implementation. Useful for scripted/CI setup against a fresh Dev-Local Gateway db before the State Backend container itself needs to be up.

### Dockerfile and GHCR publish

A `Dockerfile` at the repo root (or `state-backend/Dockerfile` with a root-context build — the container needs both `state-backend/` and, if the Dev-Local Gateway lives alongside it, that code too) builds an uberjar via the existing Clojure CLI tooling and runs it under a JRE base image; `ENTRYPOINT`/`CMD` invoke `-main` (defaulting to server-start, `bootstrap` selectable as a documented override arg). A GitHub Actions workflow (this repo's first CI of any kind) builds the image on push/tag and publishes to `ghcr.io/<org>/infratomic` using the workflow's built-in `GITHUB_TOKEN` — no my.datomic.com or other external credential is needed anywhere in this pipeline, consistent with the "no client-pro, no my.datomic.com credentials" alignment decision.

### User guide

A single new operator-facing document (e.g. `docs/user-guide.md`) walking through: running the container against a Dev-Local Gateway + LocalStack, the State Backend's HTTP surface (`/state`, `/policy-check`, `/sync`, `/drift`, the new `/query` and `/rules`), the CLI, and how to exercise network reachability and IAM reachability queries. Consolidates material currently scattered across the README and ADRs into one walkthrough; does not replace the README or ADRs, which stay as contributor-facing/decision-record documents respectively.

## Risks / Trade-offs

- **The Dev-Local Gateway is a new, from-scratch network protocol with no existing test coverage to lean on** → Mitigated by keeping its operation set minimal (only what `db.clj`/`query.clj`/`policy.clj`/`sync.clj` actually call) and by the facade namespace making `gateway` mode exercisable against the exact same behavioral tests already written for `embedded` mode (same call sites, different underlying client type).
- **Opaque-handle session state in the Dev-Local Gateway is in-memory only, with no expiry** → Acceptable for this issue's scope (a demo/try-it container, not a production deployment target — Kubernetes/prod manifests are explicitly out of scope); worth a one-line callout in the user guide, not a design change.
- **A stored Rule's `:rule/find` could bind more than one variable, or a variable the shared evaluator doesn't know how to pull as a resource** → Mitigated by documenting (validator or registration-time check) that `:rule/find` SHALL bind exactly one variable, which SHALL resolve to a `:resource/id`-carrying entity — a Rule that violates this fails registration with a clear `400`, not a runtime error during a Policy Check.
- **Two new untrusted-Datalog-accepting HTTP surfaces (`/query`, `/rules`) share one validator — a gap in that validator is a gap in both at once** → This is the intended trade-off (one enforcement point, not two to keep in sync); mitigated by direct, dedicated tests against the validator itself covering both the allowlist boundary and the recursion/rule-invocation distinction, not just end-to-end tests through each endpoint.
- **EDN-over-HTTP for the Dev-Local Gateway is not how real Datomic Pro/Cloud's client actually talks on the wire** → Explicitly accepted per the alignment decision (shape-mirroring, not wire-compatibility); documented here so a future swap to a real Pro/Cloud backend is understood to replace the *implementation* of the facade's gateway-mode types, not the facade's call-site shape.

## Migration Plan

- Purely additive at the data layer: no existing schema, resource entity, or state-version shape changes. `embedded` mode's behavior is unchanged, so no migration is needed for existing local dev/test environments.
- `policy.clj`'s Rule registry changes from a `def` to an `atom`, and its one existing Rule is re-expressed in the stored data format — an internal representation change with no externally observable behavior difference for the existing `security-groups-with-port-22-open` Rule (same Violations, same Policy Check responses).
- Rollout is container-only: nothing about `gateway` mode, the Dev-Local Gateway process, `/query`, or `/rules` is reachable unless `INFRATOMIC_DATOMIC_MODE=gateway` is set or those routes are explicitly called — existing deployments/dev workflows using the implicit `embedded` default are unaffected.
