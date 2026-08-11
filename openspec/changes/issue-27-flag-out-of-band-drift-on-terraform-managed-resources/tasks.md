## 1. Schema and write-source tagging

- [x] 1.1 Add `:resource/last-write-source` (`:db.type/keyword`, cardinality-one) to `db.clj`'s schema, docstring modeled directly on `:resource/managed?`'s.
- [x] 1.2 Add an idempotent backfill step in `ensure-db!` (mirroring `backfill-managed-flag!`) that sets `:resource/last-write-source :terraform` on any resource entity missing the attribute.
- [x] 1.3 Set `:resource/last-write-source :terraform` in `handler.clj`'s `resource->tx`, alongside the existing `:resource/managed? true`.
- [x] 1.4 Set `:resource/last-write-source :sync` on every tx-map `sync.clj`'s `resource-tx` produces (the `:discovered` branch and the `:updated`/Discovered-Resource-upsert branch).
- [x] 1.5 Unit tests: a `POST /state` write tags `:terraform`; a Sync-discovered or Sync-updated (Discovered Resource) write tags `:sync`; a pre-existing entity with no `:resource/last-write-source` backfills to `:terraform`.

## 2. Sync's diff-gated update-on-drift

- [x] 2.1 In `sync.clj`'s `resource-tx`, replace the Terraform-managed `:else` branch: pull the existing entity, reconstruct its stored attributes via `db/reconstruct-attributes`, and diff against the freshly translated live attribute map.
- [x] 2.2 When the diff finds no difference: no tx-data, `:resource/last-write-source` unchanged, outcome distinct from `:discovered`/`:updated`/`:drifted` (naming per design.md; keep disjoint from the new `:drifted` outcome).
- [x] 2.3 When the diff finds a difference: build tx-data via `db/resource-attr-tx` + `db/resource-upsert-retractions` (matching the existing Discovered-Resource-upsert branch) plus `:resource/last-write-source :sync`; outcome `:drifted`.
- [x] 2.4 Update `sync!`'s summary map to include a `:drifted` bucket (`{:type :id}` entries, same shape as `:discovered`/`:updated`), populated from the new `:drifted` outcome.
- [x] 2.5 Rewrite `sync_test.clj`'s `resource-tx-matching-a-terraform-managed-resource-is-skipped` to cover both the no-drift (no write) and drift (update + `:drifted` outcome + `:resource/last-write-source :sync`) cases.
- [x] 2.6 Add/extend an integration test (following `sync_integration_test.clj`'s pattern: apply via Terraform, mutate directly via the EC2 API, run `sync!`) asserting a Terraform-managed resource changed out-of-band is updated and tagged `:sync`, and that a re-run with no further changes makes no additional writes.

## 3. Drift Rule and history-based comparison

- [x] 3.1 Add a drift Rule function to `query.clj` (alongside `security-groups-with-port-22-open`): finds every `:resource/managed? true` resource whose current `:resource/last-write-source` is `:sync`, uses `d/history`/`d/as-of` scoped to that resource's `:resource/last-write-source` attribute to find the most recent `:terraform`-sourced transaction, reconstructs attributes as of that transaction, diffs against current live attributes, and returns the resource (at least `:resource/id`/`:resource/type`) when they differ.
- [x] 3.2 Do not register this Rule in `policy.clj`'s `rules` vector.
- [x] 3.3 Unit/integration tests: a resource with only a `:terraform` write is never flagged; a discovered-only resource (no prior `:terraform` write) is never flagged; a resource updated by Sync with the same value as Terraform's last assertion is not flagged; a resource updated by Sync with a different value is flagged; a Policy Check run against a plan is unaffected by an existing drifted resource (drift never appears as a Violation).

## 4. `GET /drift` endpoint

- [x] 4.1 Add a `drift-endpoint`-style function (new or existing namespace, per design.md) that runs the drift Rule against `(d/db conn)` and returns `{"drifted": [{"type": ..., "id": ...}, ...]}` as JSON.
- [x] 4.2 Wire `GET /drift` into `main.clj`'s `app-handler`, following the existing `/policy-check`/`/sync` manual-routing pattern (matching-method branch plus a `405` branch for other methods on `/drift`).
- [x] 4.3 Integration test: `GET /drift` returns an empty list with no drift present, and includes a drifted resource after Sync updates a Terraform-managed resource whose live value diverged.

## 5. CLI `drift-check` subcommand

- [x] 5.1 Add `default-drift-url`/`--drift-url` flag handling, mirroring `default-sync-url`/`--sync-url`.
- [x] 5.2 Add `trigger-drift-check` (GET, no body) mirroring `trigger-sync`'s fail-closed response-shape validation.
- [x] 5.3 Add `drift-check!`: prints a human-readable summary (count + list of drifted resources), and exits non-zero on request/shape failure or when drift is found, `0` only when the check succeeds and finds nothing.
- [x] 5.4 Wire `drift-check` into `-main`'s subcommand interception, alongside `sync`.
- [x] 5.5 Update `trigger-sync`/`sync!` to parse/print the new `drifted` key from `POST /sync`'s response and include it in the fail-closed shape check.
- [x] 5.6 CLI tests: `drift-check` with no drift (exit 0), with drift present (non-zero exit, resources listed), and on a failed/malformed response (non-zero exit, error reported).

## 6. Docs

- [x] 6.1 Add a new ADR (next available number 0009) covering the `:resource/last-write-source` + history-based drift detection mechanism and the diff-gated update-on-drift approach.
- [x] 6.2 Update CONTEXT.md's glossary with entries for the writer/source tag and drift/the drift Rule, in the existing `**Term**: definition. _Avoid_: ...` style.
