## Context

`:resource/managed?` (`db.clj`) is the existing precedent for a per-write boolean tag set by every write path: `handler.clj`'s `resource->tx` sets it `true` on every `POST /state` upsert, `sync.clj`'s `resource-tx` sets it `false` on every Sync-created/updated entity. Both paths funnel their attribute writes through the same shared seam, `db/resource-attr-tx`/`db/decompose-attributes`, called from `resource->tx` (POST) and `resource-tx` (Sync).

Today, `sync.clj`'s `resource-tx` has three outcomes: no existing match → `:discovered` (new Discovered Resource); existing match is a Discovered Resource → `:updated` (upsert in place); existing match is Terraform-managed → `:skipped-already-managed`, `{:tx-data [] ...}` (no write at all). This change replaces the third branch with a diff-then-maybe-write branch.

Datomic dev-local never physically deletes datoms on ordinary retraction (no excision is used anywhere in this codebase) — the transaction log already retains every prior value. This codebase has no existing use of `d/history`/`d/as-of`/`d/since` or transaction metadata; the drift Rule is the first place "value as of a specific write" semantics are needed, per the confirmed alignment (a single `:resource/last-write-source` entity attribute plus `d/history`/`d/as-of`, no separate `:tx/source` tx-metadata attribute).

See proposal.md for the full motivation and issue #27 for acceptance criteria and alignment decisions this design implements verbatim (mechanism, naming, outcome shape, and Rule invocation surface were all pre-decided in alignment — this document is about wiring them into the existing code shape, not re-deciding them).

## Goals / Non-Goals

**Goals:**
- Tag every Resource-entity write (POST and Sync) with `:resource/last-write-source` (`:terraform` / `:sync`).
- Make Sync update a Terraform-managed resource's stored attributes when the observed live value differs, instead of skipping it outright.
- Provide a query-time-only drift Rule, `GET /drift` endpoint, and `drift-check` CLI subcommand — all read-only, none touching the apply/Policy-Check path.

**Non-Goals** (per issue's Out of scope):
- Auto-remediating drift (reverting the environment to Terraform's value, or re-applying Terraform to overwrite the drifted value).
- Adding drift checking to the pre-apply Policy Check — `policy.clj`'s `rules` vector is not touched by this change.

## Decisions

### `:resource/last-write-source` as a plain entity attribute, not tx-metadata
Add `:resource/last-write-source` (`:db.type/keyword`, cardinality-one) to the schema, set on every resource-entity upsert tx-map (`resource->tx` in `handler.clj` sets `:terraform`; `resource-tx` in `sync.clj` sets `:sync` on every branch that produces tx-data). This mirrors `:resource/managed?` exactly — same attribute shape, same "set on every write" discipline, same places it gets merged into the tx-map.

Alternative considered and rejected (per alignment): a separate `[:db/add "datomic.tx" :tx/source ...]` transaction-metadata attribute, queried via a history db keyed by transaction. Rejected as unneeded extra granularity — the acceptance criteria only need "what was the source of the most recent write to this resource," which a plain entity attribute plus `d/history` on that one attribute already answers, without introducing a wholly new querying idiom (tx-metadata) into a codebase that has never used one.

Backfill: like `:resource/managed?`'s `backfill-managed-flag!`, add an analogous one-time idempotent migration in `ensure-db!` for any resource entity written before this attribute existed. Every such pre-existing entity was written via `POST /state` (Sync/`:resource/last-write-source` postdates it entirely), so it backfills to `:terraform`.

### Drift Rule: `d/history`-based comparison, not a shadow copy of Terraform's values
The drift Rule (`query.clj`, new function, not registered in `policy.clj`):
1. Finds every managed resource (`:resource/managed? true`) whose current `:resource/last-write-source` is `:sync`.
2. For each, queries `(d/history db)` restricted to that resource's `:resource/last-write-source` attribute to find the most recent transaction where it held `:terraform`, then uses `(d/as-of db that-tx)` to pull the resource's attribute values as of that transaction (via the same `db/reconstruct-attributes` reconstruction `GET /state` already uses).
3. Diffs those as-of values against the resource's current live attribute values (`db/reconstruct-attributes` again, against the live `db`).
4. Includes the resource in the result if any attribute differs.

Alternative considered and rejected (per alignment, research Q1's third option): maintain a separate shadow copy of "Terraform's last-known values" written alongside `:resource/last-write-source`, avoiding the need for `d/history` entirely. Rejected — it would duplicate the attribute-decomposition machinery (typed + generic + overflow storage) a second time for shadow values, whereas Datomic's transaction log already retains this for free; the history-based read is more code in the query path but zero extra code in the write path.

### Sync's diff-gated update: reuse `db/reconstruct-attributes`, not a raw datom comparison
In `sync.clj`'s `resource-tx`, the `:else` branch (existing match is Terraform-managed) becomes: pull the existing entity's full attribute set, reconstruct it via `db/reconstruct-attributes` (the same function `GET /state` uses to turn stored datoms back into a Terraform-shaped attribute map), and compare it against the freshly translated live attribute map (the same map already passed into `resource-tx` for the other two branches). If equal, no tx-data, no `:resource/last-write-source` change, outcome unchanged from today's meaning conceptually (nothing written) but is now reported as a new outcome kind reflecting "no drift found" rather than reused literally as `:skipped-already-managed` if that name proves confusing — actual bucket/outcome naming is an implementation-tasks-level detail, not a design fork, as long as it's disjoint from `:discovered`/`:updated`/`:drifted` per the resource-sync spec delta. If different, build tx-data via `db/resource-attr-tx` + `db/resource-upsert-retractions` (the exact same two functions `resource-tx`'s `:updated` branch already calls) plus `:resource/last-write-source :sync`, and report outcome `:drifted`.

This reuses the existing attribute-decomposition/reconstruction round-trip machinery rather than inventing a second diffing strategy, and guarantees the diff is comparing like-for-like (both sides pass through the same modeled/generic/overflow shape).

### `sync!`'s summary gains `:drifted` as a third list bucket
`sync!`'s summary map today is `{:discovered [...] :updated [...] :skipped-already-managed <count>}`. Add `:drifted [...]` (same `{:type :id}` shape as `:discovered`/`:updated`), populated from the new `:drifted` outcome. `cli/main.clj`'s `trigger-sync`/`sync!` destructure and validate `discovered`/`updated`/`skipped-already-managed` today (fail-closed on missing keys); add `drifted` to both the destructuring and the fail-closed shape check, and print it in the CLI's human-readable summary.

### `GET /drift` follows the existing manual-routing pattern in `main.clj`
`main.clj`'s `app-handler` already does `cond`-based manual routing for `/policy-check` and `/sync` (each: matching-method branch, then a `405` branch for any other method on that path) ahead of falling through to `handler/handler`. Add `/drift` the same way: a `GET`-matching branch calling a new `drift/drift-endpoint`-style function (or inline in `query.clj`/a small new namespace — an implementation-tasks decision) that runs the drift Rule against `(d/db conn)` and returns `{"drifted": [...]}` as JSON, plus a `405` branch for any other method on `/drift`.

### CLI `drift-check` subcommand follows the existing `sync` subcommand's shape
`cli/main.clj`'s `-main` already special-cases `sync` as a State-Backend-only pseudo-subcommand (intercepted before real-`terraform` passthrough, using `--sync-url`/`default-sync-url`). Add `drift-check` the same way: a new `--drift-url`/`default-drift-url` (defaulting to `http://localhost:8080/drift`), a `trigger-drift-check` analogous to `trigger-sync` (GET this time, no body) with the same fail-closed response-shape validation, and a `drift-check!` analogous to `sync!` that prints a summary and determines the exit code: non-zero on request/shape failure (matching `sync!`'s existing fail-closed exit behavior) **and** non-zero when `drifted` is non-empty (new — makes it usable as a CI gate); `0` only when the request succeeded and `drifted` is empty.

## Risks / Trade-offs

- **[Risk]** `d/history` queries are new territory for this codebase (zero prior usage) → **Mitigation**: scope the history query to a single attribute (`:resource/last-write-source`) and a single entity per call, keeping the query shape simple and directly testable against a small, deterministic transaction sequence (assert `:terraform`, assert `:sync`, query) rather than a broad history scan.
- **[Risk]** Changing `resource-sync/spec.md`'s "already-managed → left untouched" requirement and rewriting `sync_test.clj`'s corresponding test touches previously-shipped, tested, ADR'd behavior from #26 → **Mitigation**: confirmed intentional in alignment; the spec delta and design both call out the exact requirement/test being revised so the change is traceable, not an accidental regression.
- **[Trade-off]** Reusing `db/reconstruct-attributes` for the diff means Sync's per-resource cost grows (a full reconstruct-and-compare on every already-managed match, not just a skip) → accepted, since correctness (comparing like-for-like shapes) matters more than Sync's runtime here, and Sync is an explicitly-triggered, not automatic/timer-driven, operation (existing `resource-sync/spec.md` requirement, unchanged).

## Migration Plan

Additive schema change (new attribute) plus one new idempotent backfill step in `ensure-db!`, following `backfill-managed-flag!`'s exact precedent — safe on every process start, a no-op after the first post-deploy run. No data migration needed for `:resource/last-write-source` beyond that backfill; no destructive schema changes.
