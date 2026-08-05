## Why

The sample app's Terraform state currently lives in a local `terraform.tfstate` file, parsed as raw JSON. To make infrastructure queryable through Datomic — the first real step toward infratomic's goal — state needs to be written to and read from a Datomic-backed service instead, with each resource surfaced as its own entity.

## What Changes

- Add a new Clojure service, the **State Backend** (`state-backend/`), implementing Terraform's `http` backend protocol: `GET`, `POST`, and `DELETE` on `/state` at `http://localhost:8080` (`LOCK`/`UNLOCK` and auth remain out of scope).
- **No raw Terraform state JSON is ever stored.** Datomic dev-local hard-enforces a 4096-byte limit per `:db.type/string` datom; the sample app's real state document (~12.4KB) exceeds that on the very first apply, so storing it verbatim (as originally specced) is impossible, not a future-scale concern. State is decomposed and stored entity-by-entity instead.
- On `POST`, in a single Datomic transaction: create a new `state-version` entity holding the state document's top-level metadata (`:state-version/version`, `:state-version/terraform-version`, `:state-version/serial`, `:state-version/lineage`, `:state-version/outputs`), and upsert one `resource` entity per **managed** (`mode == "managed"`) entry in `resources[]` (`:resource/id`, `:resource/type`, `:resource/name`, `:resource/attributes`, `:resource/instance-meta`, `:resource/state-version` ref), keyed by `(type, name)`. Entries with `mode == "data"` (data sources) are not persisted — Terraform always re-reads data sources fresh on every `plan`/`apply` regardless of what's in state, so omitting them from storage doesn't affect drift detection (confirmed empirically).
- On `GET`, reconstruct a Terraform-state-JSON document on the fly from the latest `state-version` entity plus the current `resource` entities, and serve it as the response body; `204`/`404` semantics for "no state yet" per the `http` backend protocol. The reconstructed document does not need to be byte-identical to what was last POSTed — Terraform parses JSON structurally, so field order/formatting differences don't cause drift (confirmed empirically against the real sample app via `terraform plan`).
- On `DELETE`, retract the latest `state-version` entity and all resource entities, returning `200`.
- Malformed (non-JSON) `POST` bodies return `400`; valid JSON missing `resources`/`serial`/`lineage` is stored permissively (defaults: zero resources, `nil` serial/lineage).
- Persist Datomic data via dev-local's `:storage-dir` pointed at a new gitignored `.datomic/` directory at the repo root, so state survives service restarts.
- Run the service as a plain JVM process (`clojure -M -m infratomic.state-backend.main`), not containerized — Datomic Local is single-process/embedded and doesn't fit `docker-compose.yml`'s container model.
- Widen the `nix develop` shell to provide `clojure` and `jdk` so the service can be built and run without external toolchain setup, superseding the earlier "no language-specific packages" scoping decision.
- Point `terraform/`'s backend config at the new service (`backend "http" { address = "http://localhost:8080/state" }`, no `lock_address`/`unlock_address`), replacing the implicit local-file backend.

## Capabilities

### New Capabilities
- `state-backend`: A Clojure service implementing Terraform's `http` state backend protocol (GET/POST/DELETE), decomposing state into a state-version entity and per-resource entities in an embedded Datomic dev-local database, and reconstructing a valid state JSON document from those entities on `GET`.

### Modified Capabilities
- `nix-dev-shell`: The dev shell's "no language-specific packages" requirement is superseded — `clojure` and `jdk` are added as an explicit, scoped exception for the state backend's toolchain needs.

## Impact

- New top-level directory `state-backend/` (`deps.edn`, `src/infratomic/state_backend/...`).
- `flake.nix`: devShell package list gains `clojure` and `jdk`.
- `terraform/`: new/modified backend configuration block; `terraform init -reconfigure` (or equivalent) needed once the backend changes.
- `.gitignore`: new entry for `.datomic/`.
- No changes to existing `local-aws-environment` or `s3-upload-test-app` capabilities — LocalStack and the sample Lambda app are unaffected; only where Terraform state is stored changes.
