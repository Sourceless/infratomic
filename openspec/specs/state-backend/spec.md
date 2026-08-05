# state-backend Specification

## Purpose

Implements Terraform's `http` state backend protocol as a Clojure service, decomposing the sample app's Terraform state into an embedded Datomic database — a state-version entity plus one entity per managed resource — and reconstructing a valid state JSON document from those entities on `GET`, so infrastructure can eventually be queried instead of parsed from raw state JSON. No raw state JSON is stored: Datomic dev-local's 4096-byte-per-string limit makes verbatim storage of the sample app's real state document (~12.4KB) impossible.

## Requirements

### Requirement: State Backend serves current state via GET
The State Backend SHALL respond to `GET /state` with a Terraform-state-JSON document reconstructed from the most recently posted state's decomposed entities, when state exists. The reconstructed document need not be byte-identical to what was last posted, but SHALL be a state document Terraform's `http` backend client accepts, reflecting the same top-level metadata (format version, Terraform version, serial, lineage, outputs) and the same managed resources with their attributes as most recently posted. When no state has ever been posted, it SHALL respond in a way Terraform's `http` backend client treats as "no state yet" (`204` or `404`).

#### Scenario: Fetching state after at least one apply
- **WHEN** a client sends `GET /state` and at least one state has previously been posted
- **THEN** the response is `200` with a body that is a valid Terraform state JSON document reflecting the most recently posted top-level metadata and managed resources

#### Scenario: Fetching state before any apply
- **WHEN** a client sends `GET /state` and no state has ever been posted
- **THEN** the response is `204` or `404`, and Terraform proceeds as if uninitialized

#### Scenario: Reconstructed state round-trips without drift
- **WHEN** a client applies Terraform against the State Backend, the service is restarted, and the client then runs `terraform plan`
- **THEN** `terraform plan` reports no changes, confirming the state reconstructed via `GET` is equivalent to what was posted

### Requirement: State Backend persists posted state and derives resource entities
The State Backend SHALL respond to `POST /state` by, in a single transaction, storing the posted document's top-level metadata (format version, Terraform version, serial, lineage, outputs) as a new state-version entity, and upserting one resource entity per **managed** entry in the body's `resources[]` array (`mode == "managed"`), keyed by the resource's `(type, name)` pair. A resource entity SHALL carry the resource's type, name, raw attribute map, enough additional structural metadata to reconstruct a Terraform-acceptable state document, and a reference to the state version it was last seen in. Entries in `resources[]` with `mode == "data"` (data sources) SHALL NOT be persisted as resource entities, since Terraform always re-reads data sources fresh on every `plan`/`apply` regardless of prior state.

#### Scenario: Posting state for the first time
- **WHEN** a client sends `POST /state` with a valid Terraform state JSON body containing one or more managed resources
- **THEN** the backend persists the state's top-level metadata as the current state version, and one resource entity exists per **managed** entry in `resources[]`, each with the correct type, name, and attribute map

#### Scenario: Posting state again for an existing resource
- **WHEN** a client sends `POST /state` and a resource in the body has the same `(type, name)` as a resource entity from a prior post
- **THEN** the existing resource entity's attributes and state-version reference are updated in place, rather than a duplicate entity being created

#### Scenario: Posting state with data-source entries
- **WHEN** a client sends `POST /state` with a body whose `resources[]` includes entries with `mode == "data"`
- **THEN** no resource entity is created or updated for those entries, while managed entries are persisted normally

### Requirement: State Backend purges state via DELETE
The State Backend SHALL respond to `DELETE /state` by retracting the current state-version entity and all resource entities, and SHALL respond `200`.

#### Scenario: Deleting state
- **WHEN** a client sends `DELETE /state` while state exists
- **THEN** the response is `200`, a subsequent `GET /state` behaves as if no state has ever been posted, and no resource entities remain queryable

### Requirement: Malformed POST bodies are handled permissively
The State Backend SHALL reject a `POST /state` body that is not valid JSON with `400 Bad Request`. A body that is valid JSON but missing expected Terraform state fields (`resources`, `serial`, `lineage`) SHALL be accepted and stored rather than rejected, treating a missing `resources` array as zero resources and missing `serial`/`lineage` as absent.

#### Scenario: Posting a non-JSON body
- **WHEN** a client sends `POST /state` with a body that fails to parse as JSON
- **THEN** the response is `400 Bad Request` and no state or resource entities are created or modified

#### Scenario: Posting valid JSON missing optional fields
- **WHEN** a client sends `POST /state` with valid JSON that has no `resources`, `serial`, or `lineage` fields
- **THEN** the backend accepts and stores it as a state version with zero derived resource entities, without rejecting the request

### Requirement: State persists across service restarts
The State Backend SHALL retain previously posted state and resource entities across process restarts, using persistent storage rather than in-memory-only storage.

#### Scenario: Restarting the service
- **WHEN** the State Backend process is stopped and restarted after state has been posted
- **THEN** a subsequent `GET /state` returns a reconstructed state document reflecting the same top-level metadata and managed resources that were posted before the restart

### Requirement: Sample app's Terraform configuration uses the State Backend
The sample app's Terraform configuration under `terraform/` SHALL be configured with an `http` backend pointing at the State Backend, so `terraform apply` reads and writes state exclusively through the service rather than a local state file.

#### Scenario: Applying Terraform against the State Backend
- **WHEN** a developer runs `terraform init` and `terraform apply` in `terraform/` while the State Backend is running
- **THEN** `terraform apply` succeeds, and `terraform state list` reflects resource entities queryable from the State Backend's Datomic database
