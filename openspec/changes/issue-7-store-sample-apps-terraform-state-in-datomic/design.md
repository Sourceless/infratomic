## Context

See proposal.md - Why. This is the first Clojure code and first Datomic usage in the repo (no prior patterns to reuse). Relevant constraints already settled during interrogate-and-align (issue #7):

- Terraform's `http` backend requires the `GET` response body to be the exact raw state JSON last posted — Terraform diffs/parses it client-side, so any reconstruction drift (key order, field presence, formatting) risks confusing Terraform even if semantically equivalent.
- Datomic dev-local (now published as `com.datomic/local` on Maven Central, no license gate) is single-process/embedded; no transactor container.
- Domain terminology (State Backend, State Version, Resource entity, Raw State) is recorded in `CONTEXT.md`; the dual-storage rationale is recorded in `docs/adr/0001-dual-storage-in-state-backend.md`. This design follows both rather than re-deriving them.

## Goals / Non-Goals

**Goals:**
- Implement enough of the `http` backend protocol (`GET`/`POST`/`DELETE`) for `terraform apply` against the sample app to work end-to-end via the service.
- Store both the raw state blob (for protocol fidelity) and per-resource entities (for future querying) from the same `POST`, per the dual-storage ADR.
- Make state durable across `clojure -M` process restarts.

**Non-Goals:**
- `LOCK`/`UNLOCK` support (issue explicitly out of scope; avoided entirely by omitting `lock_address`/`unlock_address` from the backend config so Terraform never issues them).
- Decomposing resource attributes into individual Datomic attributes/datoms — attribute maps are stored as opaque strings.
- Any query capability beyond what's needed to verify one entity per resource exists (e.g. no query API, no UI).
- Authentication/authorization on the service.
- Multi-workspace or multi-state support — one fixed `/state` path, one Datomic database, matching the sample app's single-workspace scope.

## Decisions

**HTTP layer**: A minimal Ring-based handler (`ring-jetty-adapter` or similar) routing on method: `GET /state`, `POST /state`, `DELETE /state`. Chosen over a heavier framework (e.g. Pedestal, Reitit) because the surface area is three routes on one path — a router library would add indirection without payoff at this scale.

**Datomic schema**:
```clojure
;; state-version
{:db/ident :state-version/raw        :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :state-version/serial     :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
{:db/ident :state-version/lineage    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

;; resource
{:db/ident :resource/id              :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
{:db/ident :resource/type            :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :resource/name            :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :resource/attributes      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
{:db/ident :resource/state-version   :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
```
`:resource/id` is the upsert key, computed as `(str type "." name)` (e.g. `"aws_s3_bucket.uploads"`), matching the alignment decision that `(type, name)` forms unique identity. `:resource/attributes` holds the resource's raw attribute map JSON-encoded as a string (dual-storage ADR: attributes stored raw, not decomposed).

**GET semantics**: Query for the most recent `state-version` entity (by transaction time, i.e. highest `:db/txInstant` or simply "last transacted" via `d/db` + a `:find (max ?tx)`-style query), and return its `:state-version/raw` verbatim as the response body with `Content-Type: application/json`. No state-version entity yet → `204`.

**POST semantics**: Parse body as JSON first (reject non-JSON with `400` before touching Datomic). Build one `d/transact` call containing: a new state-version entity (tempid) with `:state-version/raw` set to the *original raw body string* (not a re-serialization of the parsed map, to avoid any round-trip drift), plus one upsert map per `resources[]` entry referencing that tempid via `:resource/state-version`. Missing `resources`/`serial`/`lineage` default to `[]`/`nil`/`nil` respectively per the permissive-parsing alignment decision.

**DELETE semantics**: Retract the latest state-version entity and all resource entities in one transaction (`[:db/retractEntity eid]` for each). Chosen over leaving historical versions live-but-unreferenced because `GET`'s "no state" check needs a clean signal; Datomic's transaction log still retains history for anyone using `d/history`/`d/as-of`, so no audit trail is lost.

**Storage location**: `:storage-dir` set to `<repo-root>/.datomic/`, added to `.gitignore` alongside the existing `terraform.tfstate`/`.terraform/` entries — same pattern already used for other local, regenerable state in this repo.

**Service entry point**: `state-backend/src/infratomic/state_backend/main.clj` with a `-main` that opens the dev-local client, creates the database if absent, ensures the schema is transacted (idempotent, safe to run on every startup), and starts the Jetty server on port 8080. Run via `clojure -M -m infratomic.state-backend.main` from inside `nix develop` (needs the `nix-dev-shell` delta in this same change).

## Risks / Trade-offs

- [Raw state or a resource's attribute map exceeds Datomic's 4096-char string limit] → Accepted as a known limitation per the alignment decision (YAGNI at 4-resource sample-app scale); would need chunking or a blob store if the sample app grows significantly.
- [Retracting all resource entities on `DELETE` loses "current" resource visibility even though Datomic's history is intact] → Acceptable per alignment: `DELETE` is meant to represent "no state," and history remains queryable via `d/history`/`d/as-of` for anyone who needs it.
- [Storing the raw POST body as the literal string Terraform sent (rather than a re-serialized JSON) is a slightly unusual persistence choice] → Necessary for GET fidelity; re-serializing risks Terraform receiving a byte-different-but-semantically-equal document, which is riskier than storing the original string.
- [No LOCK/UNLOCK means two concurrent `terraform apply` runs could race] → Accepted: issue scope explicitly excludes locking, and the sample app is single-developer/local-only.

## Migration Plan

This is new infrastructure with no prior state-backend deployment, so there's no data migration. The only "migration" is Terraform's own: switching `terraform/`'s backend from the implicit local backend to the new `http` backend requires `terraform init -migrate-state` (or a fresh `terraform init` if the existing local `terraform.tfstate` is discarded, since the sample app is a disposable local dev environment). This is a one-time manual step documented in tasks.md, not an automated migration.
