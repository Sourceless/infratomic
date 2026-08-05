## Why

The sample app's Terraform state currently lives in a local `terraform.tfstate` file, parsed as raw JSON. To make infrastructure queryable through Datomic — the first real step toward infratomic's goal — state needs to be written to and read from a Datomic-backed service instead, with each resource surfaced as its own entity.

## What Changes

- Add a new Clojure service, the **State Backend** (`state-backend/`), implementing Terraform's `http` backend protocol: `GET`, `POST`, and `DELETE` on `/state` at `http://localhost:8080` (`LOCK`/`UNLOCK` and auth remain out of scope).
- On `POST`, in a single Datomic transaction: persist the raw state JSON verbatim as a new `state-version` entity (`:state-version/raw`, `:state-version/serial`, `:state-version/lineage`), and upsert one `resource` entity per entry in `resources[]` (`:resource/id`, `:resource/type`, `:resource/name`, `:resource/attributes`, `:resource/state-version` ref), keyed by `(type, name)`.
- On `GET`, serve the latest `state-version`'s raw JSON verbatim (never reconstructed from resource entities); `204`/`404` semantics for "no state yet" per the `http` backend protocol.
- On `DELETE`, retract the latest raw-state blob and all resource entities, returning `200`.
- Malformed (non-JSON) `POST` bodies return `400`; valid JSON missing `resources`/`serial`/`lineage` is stored permissively (defaults: zero resources, `nil` serial/lineage).
- Persist Datomic data via dev-local's `:storage-dir` pointed at a new gitignored `.datomic/` directory at the repo root, so state survives service restarts.
- Run the service as a plain JVM process (`clojure -M -m infratomic.state-backend.main`), not containerized — Datomic Local is single-process/embedded and doesn't fit `docker-compose.yml`'s container model.
- Widen the `nix develop` shell to provide `clojure` and `jdk` so the service can be built and run without external toolchain setup, superseding the earlier "no language-specific packages" scoping decision.
- Point `terraform/`'s backend config at the new service (`backend "http" { address = "http://localhost:8080/state" }`, no `lock_address`/`unlock_address`), replacing the implicit local-file backend.

## Capabilities

### New Capabilities
- `state-backend`: A Clojure service implementing Terraform's `http` state backend protocol (GET/POST/DELETE), persisting raw state and per-resource entities in an embedded Datomic dev-local database.

### Modified Capabilities
- `nix-dev-shell`: The dev shell's "no language-specific packages" requirement is superseded — `clojure` and `jdk` are added as an explicit, scoped exception for the state backend's toolchain needs.

## Impact

- New top-level directory `state-backend/` (`deps.edn`, `src/infratomic/state_backend/...`).
- `flake.nix`: devShell package list gains `clojure` and `jdk`.
- `terraform/`: new/modified backend configuration block; `terraform init -reconfigure` (or equivalent) needed once the backend changes.
- `.gitignore`: new entry for `.datomic/`.
- No changes to existing `local-aws-environment` or `s3-upload-test-app` capabilities — LocalStack and the sample Lambda app are unaffected; only where Terraform state is stored changes.
