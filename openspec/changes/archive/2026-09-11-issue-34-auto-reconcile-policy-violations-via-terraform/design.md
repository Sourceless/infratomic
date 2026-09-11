## Context

See proposal.md for motivation. The pieces this change wires together already exist and are deliberately decision-free:

- `terraform.clj`'s `apply!`/`import!`/`destroy!` (issue #33) each acquire a per-address Datomic lock, run the real `terraform` binary non-interactively against a caller-supplied working directory, write an unconditional `:invocation/*` record, and release the lock — via `with-lock-and-invocation`. `apply!` is untargeted (whole working directory); `import!` requires a pre-existing config block and explicitly refuses to synthesize one; `destroy!` is `-target`-scoped.
- `policy.clj`'s `rule-registry` (atom, keyed by `:rule/id`) holds stored-Datalog Rules (`:rule/find`/`:rule/in`/`:rule/where`). `evaluate` runs the registry against a *speculative* `d/with` db built from a submitted plan; its private `run-rule` (db + Rule -> bound entity ids) is reusable as-is against any db value, including a real `(d/db conn)`.
- `query.clj`'s `drifted-resources` merges attribute-level, New-Child, and Removed-Child Drift; `sync.clj`'s `resource-tx` is the source of truth for `:resource/managed?`.
- `sync.clj`'s `sync!` is the existing scheduled/on-demand trigger point this change hooks into.

This is a cross-cutting change (new namespace, new persisted entity type, a new Terraform execution pattern distinct from the existing three primitives) spanning `reconcile.clj` (new), `terraform.clj`, `query.clj`, `db.clj`, and `sync.clj` — hence a design doc.

## Goals / Non-Goals

**Goals:**
- Decide-and-act on policy violations against live state, once per Sync, using the existing execution/locking/audit primitives unmodified wherever the managed path applies.
- Add config synthesis as a clearly separate concern (own namespace section, own working directory, own spec capability) rather than stretching `import!`'s existing contract.

**Non-Goals:**
- A targeted-`apply!` primitive (alignment: accepted as out of scope; untargeted `apply!`'s blast radius on other pending drift in the same working directory is unchanged by this work).
- Generalizing `:rule/find`/the Rule registry to multi-arity or otherwise changing `evaluate`/the Policy Check path — the child-binding companion query is additive and reconciliation-only.
- Direct AWS API mutation (issue's own Out of Scope).

## Decisions

### `reconcile.clj` as a new namespace, mirroring `policy.clj`/`sync.clj`'s shape
A new `infratomic.state-backend.reconcile` namespace owns "decide + act": pure decision logic (which resources violate which Rules, managed-vs-unmanaged dispatch, New-Child-Drift child resolution) plus a thin function `sync.clj` calls at the end of `sync!`, closing over the shared `conn`. This is the one namespace in the codebase whose job is closing the loop between detection (read-only, elsewhere) and action (`terraform.clj`, elsewhere) — every prior sub-ticket kept those strictly separate, so nothing existing is a natural home for this.

Alternative considered: fold reconciliation directly into `sync.clj`. Rejected — `sync!`'s existing job (discover/ingest live resources) is a distinct, already-well-scoped concern from "decide what to remediate"; keeping them in separate namespaces mirrors the existing `policy.clj`/`query.clj` split between decision logic and query primitives.

### Live-state Rule evaluation reuses `policy.clj`'s `run-rule`, not `evaluate`
Reconciliation runs `run-rule` (already db-value-agnostic) against `(d/db conn)` directly for every registered Rule, once per managed resource pass — not `evaluate`, which is hardwired to build a speculative `d/with` db from a submitted plan. This is exactly the "no existing path runs the registry against live state" gap the research flagged; `run-rule`'s existing signature already supports it without modification.

Alternative considered: extend `evaluate` with an optional "run against live db instead of a plan" mode. Rejected — `evaluate`'s contract (speculative, non-persistent, plan-shaped input) is exercised elsewhere (Policy Check endpoint, CLI) and alignment explicitly says that path stays untouched; branching it would blur two genuinely different operations (plan-time check vs. live-state reconciliation) into one function.

### Dispatch keys off `:resource/managed?` of the specific violating entity, not the Rule's own bound entity
The dispatch decision (`apply!` vs. import+destroy vs. record-only) is made per concrete resource that is actually the remediation target — after the child-binding companion query resolves a child entity for a parent-binding Rule, dispatch looks at *that child's* `:resource/managed?` (always `false`, since a New-Child-Drift child is by definition a Discovered Resource), not the parent SG's. This is what makes "one rule, applied per-resource" (alignment) fall out naturally: the parent-vs-child resolution step and the managed-vs-unmanaged dispatch step are independent and compose, rather than New-Child Drift needing its own branch in the dispatch logic itself.

### Child-binding companion query lives in `query.clj`, keyed by SG id, called only from `reconcile.clj`
`offending-port-22-rules-for-sg` takes a security group's `:resource/id`/entity and returns the specific `aws_security_group_rule` entities matching the same predicate the registered Rule already encodes (port 22, `0.0.0.0/0`). It duplicates the *shape* of `security-groups-with-port-22-open-rule`'s join/predicates rather than trying to factor them into one shared definition, because the two consumers (registered Rule vs. this query) genuinely want different result granularity (bind `?sg` vs. bind `?rule`) and alignment explicitly rejected generalizing `:rule/find` to multi-arity to serve both from one definition.

### `:reconciliation/*` schema
New entity type, distinct from `:invocation/*` (same rationale as the alignment decision: Invocation means "attempt", this means "finding + decision"). Fields, following the existing `:invocation/*`/`:lock/*` naming convention in `db.clj`:
- `:reconciliation/resource` — ref to the Resource entity
- `:reconciliation/rule` — the violated Rule's `:rule/id` (keyword)
- `:reconciliation/action` — `:reconciliation.action/apply` / `:reconciliation.action/import-destroy` / `:reconciliation.action/none`
- `:reconciliation/invocation` — optional ref to the `:invocation/*` entity the action produced (absent for `:none`)
- `:reconciliation/at` — timestamp, mirroring `:invocation/at`

Written unconditionally for every violating resource on every reconciliation pass (including repeat `:none` records on repeat Sync runs for a still-violating, still-non-drifted resource) — reconciliation does not attempt idempotent dedup of records across passes, matching `:invocation/*`'s own "written unconditionally, every call" precedent. A history of repeated `:none` findings is itself useful audit signal (the violation persisted across N Sync cycles), not noise to suppress.

### Config synthesis: `import` block + `plan -generate-config-out` + `apply`, in a scratch working directory
Implemented as a new function in `terraform.clj` (e.g. `synthesize-import-and-destroy!`), alongside `apply!`/`import!`/`destroy!` but explicitly not reusing `import!` (whose contract is "fails if no config block exists" — synthesis is what makes that block exist in the first place). Sequence, entirely within a fresh scratch directory:
1. Create the scratch directory; write its minimal provider/backend config plus one `import { to = <resource/id>, id = <derived-id> }` block.
2. `terraform plan -generate-config-out=<path>` — the AWS provider writes the resource's full configuration block from live state.
3. `terraform apply -auto-approve` — binds the resource under Terraform management in this scratch directory's own (throwaway) state.
4. `terraform destroy -target=<address> -auto-approve`.
5. Discard the scratch directory unconditionally (`finally`), regardless of where steps 2-4 succeeded or failed.

This sequence is wrapped by the same `with-lock-and-invocation` machinery `apply!`/`import!`/`destroy!` use, so locking and Invocation-recording stay uniform across all four Terraform-invoking code paths; the scratch directory only affects *where* Terraform runs, not how the invocation is locked or audited.

Alternative considered: write attribute-map-to-HCL serialization ourselves (generic or per-type). Rejected per alignment — directly resolves issue #33's stated non-goal without inventing a new serialization layer, and the provider's own schema-driven generation is strictly more correct than a hand-maintained mapping.

### Import id derivation is a small per-type lookup, not a generalized attribute-to-id DSL
A resource type is either "AWS id is a valid Terraform import id" (use `id` attribute directly) or "needs Terraform's synthetic composite id" (`db/id-space-mismatched-types`, already named in the codebase per research) — a small data-driven table (type -> composite-id-builder fn) covering `aws_security_group_rule` and `aws_route` (the two the alignment names), extensible later without a design change.

## Risks / Trade-offs

- [Untargeted `apply!`'s blast radius reasserts other pending drift in the shared working directory as a side effect of remediating one violation] → Accepted per alignment; no targeted-apply primitive is in scope for this change.
- [Scratch working directory creation/teardown adds latency to every Sync cycle that finds an unmanaged violation] → Bounded by the same per-address Lock/TTL machinery already governing `apply!`/`import!`/`destroy!`; a slow or hung synthesis blocks only that one resource's future reconciliation attempts (TTL: 10 minutes), not the rest of Sync.
- [`terraform plan -generate-config-out` is a relatively new Terraform CLI feature; provider-generated config could omit or mis-render an attribute for a given resource type] → Risk is bounded because the generated config only needs to survive one `apply`+immediate `destroy` in a throwaway directory, never long-term maintenance; a generation gap that fails the sequence surfaces as a normal Invocation failure, not silent data loss.
- [Repeated `:none` reconciliation records accumulate unboundedly for a persistently-violating, non-drifted resource across many Sync cycles] → Accepted; matches `:invocation/*`'s existing "no built-in retention policy" precedent, and repeat findings are meaningful audit signal, not pure noise.

## Migration Plan

Additive only: new namespace, new schema attributes, one new call at the end of `sync!`. No existing entity, endpoint, or CLI behavior changes shape. Rollout is "merge and deploy" — no data migration, no phased flag needed given the schema is purely additive and reconciliation attempting no action on non-violating resources is itself the safe default (Non-violating-resources-untouched requirement).
