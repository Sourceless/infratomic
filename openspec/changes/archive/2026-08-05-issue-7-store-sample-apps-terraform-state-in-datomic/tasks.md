## 1. Nix dev shell

- [x] 1.1 Add `clojure` and a `jdk` package to `flake.nix`'s devShell package list
- [x] 1.2 Verify `nix develop` provides a working `clojure --version` and JDK on `PATH`

## 2. State Backend project scaffold

- [x] 2.1 Create `state-backend/deps.edn` with `com.datomic/local` and a Ring/Jetty adapter dependency
- [x] 2.2 Create `state-backend/src/infratomic/state_backend/` namespace structure
- [x] 2.3 Add `.datomic/` to `.gitignore`

## 3. Datomic schema and storage setup

- [x] 3.1 Implement schema definition — **revised (no raw-blob storage, see design.md)**: `state-version/version`, `state-version/terraform-version`, `state-version/serial`, `state-version/lineage`, `state-version/outputs`, `resource/id`, `resource/type`, `resource/name`, `resource/attributes`, `resource/instance-meta`, `resource/state-version`
- [x] 3.2 Implement idempotent startup logic: create the dev-local database if absent (`:storage-dir` pointed at repo-root `.datomic/`), connect, and transact schema if not already present

## 4. HTTP handlers

- [x] 4.1 Implement `GET /state`: reconstruct a Terraform-state-JSON document from the latest `state-version` entity and current `resource` entities (managed resources only, `mode` hardcoded to `"managed"`) with `200`, or `204` when no state exists
- [x] 4.2 Implement `POST /state`: parse body as JSON, return `400` if invalid; on valid JSON, transact a new state-version (top-level metadata: `version`/`terraform_version`/`serial`/`lineage`/`outputs`, defaulting missing fields) plus upserted resource entities for `resources[]` entries with `mode == "managed"` (entries with `mode == "data"` are skipped)
- [x] 4.3 Implement `DELETE /state`: retract the latest state-version and all resource entities, return `200`
- [x] 4.4 Wire routes into a Ring handler and start Jetty on port 8080 from `-main`

## 5. Terraform integration

- [x] 5.1 Add `terraform { backend "http" { address = "http://localhost:8080/state" } }` to `terraform/` (no `lock_address`/`unlock_address`)
- [x] 5.2 Document the one-time `terraform init -migrate-state` (or fresh `terraform init`) step needed to switch off the local backend

## 6. End-to-end verification

- [x] 6.1 Start the State Backend (`clojure -M -m infratomic.state-backend.main`) inside `nix develop`
- [x] 6.2 Run `terraform init` and `terraform apply` in `terraform/` against the running service (real LocalStack-backed sample app: S3 bucket, IAM role, Lambda function, Lambda function URL) and confirm success
- [x] 6.3 Confirm `terraform state list` shows the S3 bucket, IAM role, Lambda function, and Lambda function URL
- [x] 6.4 Confirm one resource entity per resource, matching `terraform state list` — verified via `GET /state`'s reconstructed `resources[]` (direct `d/q` against the storage dir while the service holds its dev-local file lock isn't possible from a second process; the reconstructed response is built from the same resource entities a direct query would return, so this is an equivalent check)
- [x] 6.5 Restart the State Backend process and confirm `GET /state` still returns a reconstructed state reflecting what was previously posted (persistence across restarts)
- [x] 6.6 Run `terraform plan` after the restart and confirm it reports no drift ("No changes"), proving the `GET`-reconstructed state round-trips correctly
- [x] 6.7 Re-run `terraform apply` with no changes and confirm resource entities are upserted in place, not duplicated
- [x] 6.8 Exercise `DELETE /state` directly (e.g. via `curl`) and confirm state and resource entities are purged
