## Why

Once Sync (#26) can ingest resources that exist outside Terraform, the next gap is resources Terraform *does* manage but that someone has since changed directly against the environment (e.g. via `aws`/`awslocal`) — that out-of-band change silently persists until the next `terraform plan`/`apply` happens to notice it. Tagging every write with its source and adding a query-time Rule that flags managed resources whose latest write didn't come from Terraform closes that gap without touching the apply path.

## What Changes

- Add a `:resource/last-write-source` attribute (`:terraform` / `:sync`), set on every write to a Resource entity by both `POST /state` and Sync — mirroring the existing `:resource/managed?` tagging pattern.
- **BREAKING**: Sync no longer skips a Terraform-managed resource whose AWS-observed attributes differ from what's stored. Instead it diffs live attributes (via `db/reconstruct-attributes`) against the freshly translated live values and, only when they differ, writes the new values and flips `:resource/last-write-source` to `:sync`. A no-op sync (nothing changed) still produces zero writes. This reverses `resource-sync/spec.md`'s current "a Terraform-managed resource is left untouched" behavior and the corresponding `sync_test.clj` assertion.
- `sync!`'s summary map gains a new `:drifted` outcome bucket (`{:type :id}` entries) distinct from `:updated`, meaning "a Terraform-managed resource Sync just updated because live state diverged." `:updated` keeps its existing meaning (a previously-discovered, non-Terraform-managed resource got new values).
- Add a new query-time-only drift Rule (`query.clj`, alongside `security-groups-with-port-22-open`) that finds every managed resource whose most recent `:resource/last-write-source` write is `:sync`, uses `d/history`/`d/as-of` to find the last time that attribute was `:terraform` and reads the resource's attribute values as of that transaction, and flags the resource when those values differ from the live current values. This Rule is explicitly **not** added to `policy.clj`'s `rules` vector — it never runs as part of the pre-apply Policy Check.
- Add a new read-only `GET /drift` HTTP endpoint on the State Backend that runs the drift Rule against the live db and returns `{"drifted": [{"type": ..., "id": ...}, ...]}` — no request body, no auth, no query params.
- Add a new `infratomic drift-check` CLI subcommand (parallel to `infratomic sync`) that calls `GET /drift` and prints a human-readable summary. Exits non-zero both on a malformed/failed response and when drift is found (so it's usable as an automation gate); exits `0` only when the check succeeds and finds nothing.
- Prior Terraform-asserted values are never destroyed by a `:sync` write — they remain reconstructable via Datomic history, which is exactly what the drift Rule relies on.

Out of scope (per issue): auto-remediating drift (revert or re-apply), and adding drift checking to the pre-apply Policy Check.

## Capabilities

### New Capabilities
- `drift-detection`: The writer/source tag's role in drift, the query-time drift Rule (history-based comparison against Terraform's last-asserted value, deliberately excluded from the Policy Check registry), and the `GET /drift` endpoint.

### Modified Capabilities
- `resource-sync`: Sync's "already-managed match" behavior changes from "left untouched" to "diff and update on drift, tagging the write `:sync`"; `sync!`'s summary gains the `:drifted` bucket.
- `state-backend`: Every `POST /state` write now also records `:resource/last-write-source :terraform` on the resource entities it upserts, alongside the existing managed/discovered tagging.
- `terraform-cli`: Adds the `drift-check` subcommand alongside the existing `sync` subcommand.

## Impact

- `state-backend/src/infratomic/state_backend/db.clj`: new `:resource/last-write-source` schema attribute; `resource-attr-tx`/`decompose-attributes` set it on every write; migration/backfill precedent (`backfill-managed-flag!`) may need an analogous one-time backfill for pre-existing entities.
- `state-backend/src/infratomic/state_backend/sync.clj`: `resource-tx`'s `:else` branch (currently skip) becomes a diff-then-maybe-update branch; `sync!`'s summary map gains `:drifted`.
- `state-backend/src/infratomic/state_backend/query.clj`: new drift Rule function.
- `state-backend/src/infratomic/state_backend/policy.clj`: unchanged — explicitly not touched, since the drift Rule must not be registered there.
- `state-backend/src/infratomic/state_backend/main.clj`: new `GET /drift` route.
- `cli/src/infratomic/cli/main.clj`: `trigger-sync` parses/validates the new `drifted` key; new `drift-check` subcommand.
- `openspec/specs/resource-sync/spec.md`: revise the "Resources are matched by AWS resource id" requirement's already-managed scenario.
- `state-backend/test/infratomic/state_backend/sync_test.clj`: revise `resource-tx-matching-a-terraform-managed-resource-is-skipped`.
- `CONTEXT.md`: new/updated glossary entries for the writer/source tag and drift.
- `docs/adr/`: new ADR (next available number 0009) covering the writer/source tag + history-based drift detection approach.
