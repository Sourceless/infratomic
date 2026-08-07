## Context

See proposal.md for motivation. Two existing constraints shape this design:

- Datomic dev-local allows only one process to hold an open connection to a given database at a time (documented and empirically relied on elsewhere in this repo — see `state-backend/test/infratomic/state_backend/query_integration_test.clj`'s docstring). The State Backend service must already be running (serving `GET /state` for `terraform plan`) whenever `apply` runs, so a separate CLI process cannot open its own `d/connect` to the same storage.
- `db.clj`'s `decompose-attributes`/`resource-attr-tx` are pure `(type, attributes-map) -> tx-fragment` functions with no dependency on state-shaped input; `handler.clj`'s surrounding tx-building (`resource->tx`, `instance-meta`, `managed-resources`) is specific to state JSON's `resources[].instances[0].attributes` shape and is not reused.

## Goals / Non-Goals

**Goals:**
- Evaluate a Terraform plan's resources against policy Rules before `apply` runs, entirely from inside the already-running State Backend process.
- Make the existing `security-groups-with-port-22-open` Rule work unmodified against a plan that creates a brand-new, not-yet-applied security group and rule.
- Keep the CLI a thin, stateless HTTP client with no Datomic dependency at all.

**Non-Goals:**
- Handling resources in non-root Terraform modules (`configuration.module_calls`) — the sample app has none; plan-decomposition glue only reads `planned_values.root_module.resources[]` and `configuration.root_module.resources[]`.
- Resolving conditional, interpolated, or multi-reference expressions to an Address Stand-in (per ADR-0004, only direct single-reference expressions are resolved).
- A rule-definition DSL, CI/pre-commit wiring, auto-remediation, or state-backend POST-time enforcement (all explicitly out of scope per the issue).

## Decisions

### Policy Check runs as a new endpoint inside the State Backend process
Rather than the CLI opening its own connection, `main.clj`'s Ring handler gains a new route (e.g. `POST /policy-check`) backed by a new `infratomic.state-backend.policy` namespace. It receives the full `terraform show -json` document, builds speculative tx-data, calls `(d/with (d/with-db conn) {:tx-data ...})` off the process's existing `conn`, and evaluates Rules against the resulting `:db-after`. `d/with` is a pure, non-mutating operation on a db value — concurrent policy checks and real `/state` traffic don't interfere with each other or with the real db.

Alternatives considered: the CLI holding its own dev-local connection (rejected — the file lock is exclusive and the service must already be running); the CLI reading `.datomic/` storage directly without going through the service (rejected — same lock, and duplicates connection-management code the service already has).

### New plan-decomposition namespace, not a reuse of handler.clj
A new function (analogous in spirit to `resource->tx` but for plan shape) takes one `planned_values.root_module.resources[]` entry and produces a tx-map: `:resource/id` = `(str type "." name)` (identical computation to today, both fields present verbatim on plan entries), `:resource/type` = `type`, `:resource/name` = `name`, and `(db/resource-attr-tx type values)` merged in (`values` standing in for state's `attributes` — same map shape, so `decompose-attributes` needs no changes). No `:resource/instance-meta` is built and no `:resource/state-version` reference is set — the speculative db is only ever evaluated by Rules, never reconstructed back into a state document.

### Address Stand-in resolution (ADR-0004)
Before building each resource's tx-map, the glue code resolves Address Stand-ins:
1. For each modeled identifying attribute (per `resource-schema`) whose value in `values` is `null`, substitute the resource's own address (`type + "." + name`) as that attribute's value before calling `resource-attr-tx`.
2. For a `null` attribute that plan JSON's `configuration.root_module.resources[].expressions.<key>.references` records as a single-element list pointing at exactly one other resource, substitute that referenced resource's address instead of its own. A multi-element or absent `references` list is left `null` (step 1 doesn't apply here since this is a cross-resource reference, not the resource's own identity).
This is pure data transformation ahead of `resource-attr-tx` — `decompose-attributes` itself is untouched, and `security-groups-with-port-22-open` needs no changes: it joins on whatever string values happen to be present, and an Address Stand-in is just a string.

### Rule contract and registry
A Rule is `(fn [db] -> seq-of-maps)`, matching `query.clj`'s existing shape. A private var in the new policy namespace holds `[query/security-groups-with-port-22-open]` (referencing the existing function, not a copy). The Policy Check endpoint runs every registered Rule against the speculative `db-after`, and for each non-empty result, emits one Violation map per returned resource: `{:rule <keyword-or-name identifying the rule> :resource/id ... :resource/type ...}` (exact key names are an implementation choice within this contract, not a spec-level concern — the spec only requires that a Violation identifies the rule and the resource). The endpoint's JSON response is `{"violations": [...]}`.

### CLI structure
New top-level `cli/deps.edn` project with `infratomic.cli.main`. `-main` inspects the first non-flag argument: if it's `apply`, run the gated flow (`terraform plan -out=tfplan` → `terraform show -json tfplan` → POST to the Policy Check endpoint → branch on violations); for anything else, `(apply shell-out "terraform" args)` with inherited stdio and the subprocess's exit code passed to `System/exit`. Uses `clojure.java.shell`/`ProcessBuilder` with `:inheritIO` semantics so passthrough subcommands behave identically to running `terraform` directly (interactive prompts, colored output, etc. all work). The Policy Check endpoint's base URL is a CLI config point (env var or CLI flag), not hardcoded, so it isn't coupled to the sample app's `localhost:8080`.

Alternatives considered: a `bin/terraform` shell shim calling into the CLI (deferred — UX polish, not required by acceptance criteria); folding the CLI into `state-backend/`'s existing `deps.edn` (rejected — the CLI has a different runtime shape, a subprocess-wrapping tool with no Datomic dependency at all, and keeping it a separate top-level project keeps that boundary explicit, matching the alignment decision).

## Risks / Trade-offs

- [Risk] A plan resource's `configuration.root_module.resources[].expressions` structure varies by provider/attribute and may not always contain a clean `references` list for the exact attribute in question → Mitigation: resolution is best-effort; an unresolved attribute is simply left absent (as it is today), degrading to today's behavior rather than erroring.
- [Risk] Running policy checks and real `/state` traffic against the same `conn` concurrently → Mitigation: `d/with` never mutates `conn`'s live db; only `d/transact` (used solely by `/state POST`) does, so there's no read/write conflict to guard against.
- [Trade-off] The CLI shells out to a real `terraform` binary that must be present on `PATH` and is not sandboxed/version-pinned by this change — acceptable, since acceptance criteria assume an operator already has Terraform installed.
