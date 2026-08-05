## Context

See proposal.md - Why. This is the first Clojure code and first Datomic usage in the repo (no prior patterns to reuse). Relevant constraints already settled during interrogate-and-align (issue #7):

- Datomic dev-local (now published as `com.datomic/local` on Maven Central, no license gate) is single-process/embedded; no transactor container. It also hard-enforces a **4096-byte limit per `:db.type/string` datom**, with no configuration knob to raise it (confirmed by decompiling `com.datomic/local` 1.0.291's `datomic.dev-local.log-representation/enforce-tx-limits!`).
- Domain terminology (State Backend, State Version, Resource entity, Raw State) is recorded in `CONTEXT.md`; the dual-storage rationale is recorded in `docs/adr/0001-dual-storage-in-state-backend.md`.

**Revision (post-implementation-blocker):** the original design in this document stored the raw POST body verbatim as `:state-version/raw` and served it back byte-for-byte on `GET`, on the assumption that Terraform's `http` backend client requires exact-fidelity round-tripping. Implementing against the real sample app proved two things:
1. The 4096-byte limit is hit immediately, not at some future scale — the sample app's real state document (S3 bucket, IAM role, Lambda function, Lambda function URL) is ~12.4KB on the very first `apply`, ~3x over the limit. Raw-blob storage as specced cannot satisfy the issue's acceptance criteria at all, ever, for this app.
2. Terraform's `http` backend client parses the `GET` response as JSON and works with the resulting Go structs — it does not byte-diff it against what it last POSTed. Empirically reconstructing a semantically-equivalent (not byte-identical) state document from decomposed data and serving that on `GET` produces `terraform plan` output of "No changes. Your infrastructure matches the configuration." after a full `apply` + service restart, i.e. no drift.

Given (1) and (2), this design now decomposes state into Datomic entities with **no raw blob stored anywhere**, and `GET` reconstructs the state JSON document from those entities. See "Decisions" below for what was empirically found necessary to reconstruct.

## Goals / Non-Goals

**Goals:**
- Implement enough of the `http` backend protocol (`GET`/`POST`/`DELETE`) for `terraform apply` against the sample app to work end-to-end via the service.
- Decompose posted state into per-resource Datomic entities (for future querying) and a small state-version entity holding top-level metadata, staying under dev-local's 4096-byte string limit for every stored value.
- Reconstruct a Terraform-acceptable state JSON document from those entities on `GET`, verified to produce no `terraform plan` drift against the real sample app.
- Make state durable across `clojure -M` process restarts.

**Non-Goals:**
- `LOCK`/`UNLOCK` support (issue explicitly out of scope; avoided entirely by omitting `lock_address`/`unlock_address` from the backend config so Terraform never issues them).
- Decomposing resource attributes into individual Datomic attributes/datoms — attribute maps are stored as opaque strings.
- Persisting data-source (`mode == "data"`) entries from `resources[]` — Terraform always re-reads data sources fresh on every `plan`/`apply` regardless of prior state, so they're excluded from storage and reconstruction with no drift impact (confirmed empirically; see Decisions).
- Any query capability beyond what's needed to verify one entity per resource exists (e.g. no query API, no UI).
- Authentication/authorization on the service.
- Multi-workspace or multi-state support — one fixed `/state` path, one Datomic database, matching the sample app's single-workspace scope.

## Decisions

**HTTP layer**: A minimal Ring-based handler (`ring-jetty-adapter` or similar) routing on method: `GET /state`, `POST /state`, `DELETE /state`. Chosen over a heavier framework (e.g. Pedestal, Reitit) because the surface area is three routes on one path — a router library would add indirection without payoff at this scale.

**Datomic schema**:
```clojure
;; state-version: top-level metadata for one posted state document. A new
;; entity is created on every POST (never updated in place), matching the
;; existing "latest by tx" query pattern. Holds no raw JSON — every field is
;; small and well under the 4096-byte string limit.
{:db/ident :state-version/version            :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
{:db/ident :state-version/terraform-version  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :state-version/serial             :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
{:db/ident :state-version/lineage            :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :state-version/outputs            :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

;; resource
{:db/ident :resource/id              :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
{:db/ident :resource/type            :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :resource/name            :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :resource/attributes      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :resource/instance-meta   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :resource/state-version   :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
```
`:resource/id` is the upsert key, computed as `(str type "." name)` (e.g. `"aws_s3_bucket.uploads"`), matching the alignment decision that `(type, name)` forms unique identity. `:resource/attributes` holds the resource's raw attribute map JSON-encoded as a string (dual-storage ADR: attributes stored raw, not decomposed). `:state-version/outputs` holds the state document's `outputs` map JSON-encoded as a string — needed for `terraform plan` to see the same output values across a `GET`/reconstruct round-trip.

`:resource/instance-meta` is a JSON-encoded string of `{"schema_version": ..., "provider": ..., "sensitive_attributes": [...], "private": "...", "dependencies": [...]}` — everything from the posted resource's single `instances[0]` entry other than `attributes`, plus the resource-level `provider` string. This was empirically determined: reconstructing state for the sample app's four managed resources (all singleton — no `count`/`for_each`, so no `index_key` handling is needed) requires at minimum `schema_version` and `attributes` for `terraform apply`/`plan` to succeed at all; `provider`, `sensitive_attributes`, `private`, and `dependencies` were tested and found *not* to be strictly required to avoid `terraform plan` drift for this app's resource types, but are kept anyway (cost is a few hundred bytes per resource, comfortably under the 4096-byte limit — largest observed was ~2KB for the Lambda function's `attributes` alone) because they match what Terraform itself emits and matter for correctness in cases `plan`-diffing doesn't exercise (e.g. destroy-graph ordering via `dependencies`). `identity_schema_version` (a newer Terraform 1.12+ "resource identity" field) was tested and found unnecessary — Terraform tolerates its absence — so it is not stored.

Only `resources[]` entries with `"mode": "managed"` are persisted as resource entities; entries with `"mode": "data"` (data sources — the sample app has two: `data.archive_file.upload_handler`, `data.aws_iam_policy_document.lambda_assume_role`) are skipped entirely. This was verified empirically: Terraform always re-reads data sources fresh on every `plan`/`apply` regardless of what's in the prior state, so their absence from the reconstructed state produces no drift — `terraform plan` still reports "No changes."

**GET semantics**: Query for the most recent `state-version` entity (by transaction time, via `d/q` with `(max ?tx)`) and every current `resource` entity referencing it (or, equivalently, every current `resource` entity — since a resource is always upserted to point at the state-version it was most recently seen in). Reconstruct a state JSON document: `{"version": ..., "terraform_version": ..., "serial": ..., "lineage": ..., "outputs": <decoded>, "resources": [<one entry per resource entity, mode hardcoded to "managed">]}`. Each resource entry is built by decoding `:resource/attributes` and `:resource/instance-meta` and assembling `{"mode": "managed", "type": ..., "name": ..., "provider": <from instance-meta>, "instances": [{"schema_version": ..., "attributes": <decoded>, "sensitive_attributes": ..., "private": ..., "dependencies": ...} <omitting private/dependencies keys when absent, matching Terraform's own output shape>]}`. Serialize with `Content-Type: application/json`. No state-version entity yet → `204`.

**POST semantics**: Parse body as JSON first (reject non-JSON with `400` before touching Datomic). Build one `d/transact` call containing: a new state-version entity (tempid) with `:state-version/version`/`terraform-version`/`serial`/`lineage` from the parsed body's top-level fields and `:state-version/outputs` as the JSON-encoded `outputs` map, plus one upsert map per `resources[]` entry *whose `mode` is `"managed"`* referencing that tempid via `:resource/state-version`. Missing `resources`/`serial`/`lineage` default to `[]`/`nil`/`nil` respectively per the permissive-parsing alignment decision; `resources[]` entries with `mode == "data"` are filtered out before building upserts.

**DELETE semantics**: Retract the latest state-version entity and all resource entities in one transaction (`[:db/retractEntity eid]` for each). Chosen over leaving historical versions live-but-unreferenced because `GET`'s "no state" check needs a clean signal; Datomic's transaction log still retains history for anyone using `d/history`/`d/as-of`, so no audit trail is lost.

**Storage location**: `:storage-dir` set to `<repo-root>/.datomic/`, added to `.gitignore` alongside the existing `terraform.tfstate`/`.terraform/` entries — same pattern already used for other local, regenerable state in this repo.

**Service entry point**: `state-backend/src/infratomic/state_backend/main.clj` with a `-main` that opens the dev-local client, creates the database if absent, ensures the schema is transacted (idempotent, safe to run on every startup), and starts the Jetty server on port 8080. Run via `clojure -M -m infratomic.state-backend.main` from inside `nix develop` (needs the `nix-dev-shell` delta in this same change).

## Risks / Trade-offs

- [Reconstructed state JSON is not byte-identical to what Terraform last POSTed — could a future Terraform version or a larger/different resource graph rely on some field this design drops or reconstructs differently?] → Mitigated, not eliminated: empirically validated end-to-end against the real sample app (all four managed resource types, singleton instances) — `terraform apply` succeeds from a cold start, and `terraform plan` after a service restart reports no drift. A resource's individual `attributes` or `instance-meta` blob could in principle still exceed 4096 bytes for a sufficiently large single resource (unlikely at this scale — largest observed today is ~2KB); would need further decomposition if that ever happens. Untested: `count`/`for_each` resources (`index_key` handling) and non-singleton providers — out of scope since the sample app uses neither.
- [Persisting only `mode == "managed"` resources means data-source entries never appear in a reconstructed `GET` response] → Accepted as empirically validated: Terraform re-reads data sources fresh on every `plan`/`apply` regardless of prior state, so this doesn't cause drift for this app. If a future resource depended on a data source's value being present in state itself (rather than recomputed), this assumption would need revisiting.
- [Retracting all resource entities on `DELETE` loses "current" resource visibility even though Datomic's history is intact] → Acceptable per alignment: `DELETE` is meant to represent "no state," and history remains queryable via `d/history`/`d/as-of` for anyone who needs it.
- [No LOCK/UNLOCK means two concurrent `terraform apply` runs could race] → Accepted: issue scope explicitly excludes locking, and the sample app is single-developer/local-only.

## Migration Plan

This is new infrastructure with no prior state-backend deployment, so there's no data migration. The only "migration" is Terraform's own: switching `terraform/`'s backend from the implicit local backend to the new `http` backend requires `terraform init -migrate-state` (or a fresh `terraform init` if the existing local `terraform.tfstate` is discarded, since the sample app is a disposable local dev environment). This is a one-time manual step documented in tasks.md, not an automated migration.
