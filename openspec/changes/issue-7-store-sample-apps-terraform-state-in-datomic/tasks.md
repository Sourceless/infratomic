## 1. Nix dev shell

- [x] 1.1 Add `clojure` and a `jdk` package to `flake.nix`'s devShell package list
- [x] 1.2 Verify `nix develop` provides a working `clojure --version` and JDK on `PATH`

## 2. State Backend project scaffold

- [x] 2.1 Create `state-backend/deps.edn` with `com.datomic/local` and a Ring/Jetty adapter dependency
- [x] 2.2 Create `state-backend/src/infratomic/state_backend/` namespace structure
- [x] 2.3 Add `.datomic/` to `.gitignore`

## 3. Datomic schema and storage setup

- [x] 3.1 Implement schema definition (`state-version/raw`, `state-version/serial`, `state-version/lineage`, `resource/id`, `resource/type`, `resource/name`, `resource/attributes`, `resource/state-version`)
- [x] 3.2 Implement idempotent startup logic: create the dev-local database if absent (`:storage-dir` pointed at repo-root `.datomic/`), connect, and transact schema if not already present

## 4. HTTP handlers

- [x] 4.1 Implement `GET /state`: return latest `state-version/raw` verbatim with `200`, or `204` when no state exists
- [x] 4.2 Implement `POST /state`: parse body as JSON, return `400` if invalid; on valid JSON, transact a new state-version (raw body string, `serial`, `lineage`, defaulting missing fields) plus upserted resource entities from `resources[]`
- [x] 4.3 Implement `DELETE /state`: retract the latest state-version and all resource entities, return `200`
- [x] 4.4 Wire routes into a Ring handler and start Jetty on port 8080 from `-main`

## 5. Terraform integration

- [x] 5.1 Add `terraform { backend "http" { address = "http://localhost:8080/state" } }` to `terraform/` (no `lock_address`/`unlock_address`)
- [x] 5.2 Document the one-time `terraform init -migrate-state` (or fresh `terraform init`) step needed to switch off the local backend

## 6. End-to-end verification

- [x] 6.1 Start the State Backend (`clojure -M -m infratomic.state-backend.main`) inside `nix develop`
- [ ] 6.2 Run `terraform init` and `terraform apply` in `terraform/` against the running service and confirm success
- [ ] 6.3 Confirm `terraform state list` shows the S3 bucket, IAM role, Lambda function, and Lambda function URL
- [ ] 6.4 Query Datomic directly (e.g. `(d/q '[:find ?type ?name :where [?e :resource/type ?type] [?e :resource/name ?name]] db)`) and confirm one entity per resource, matching `terraform state list`
- [ ] 6.5 Restart the State Backend process and confirm `GET /state` still returns the previously posted state (persistence across restarts)
- [ ] 6.6 Re-run `terraform apply` with no changes and confirm resource entities are upserted in place, not duplicated
- [ ] 6.7 Exercise `DELETE /state` directly (e.g. via `curl`) and confirm state and resource entities are purged
