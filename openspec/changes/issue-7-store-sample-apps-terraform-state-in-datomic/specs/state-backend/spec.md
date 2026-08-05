## Purpose

Implements Terraform's `http` state backend protocol as a Clojure service, persisting the sample app's Terraform state in an embedded Datomic database and exposing each managed resource as its own entity, so infrastructure can eventually be queried instead of parsed from raw state JSON.

## ADDED Requirements

### Requirement: State Backend serves current state via GET
The State Backend SHALL respond to `GET /state` with the raw JSON of the most recently posted Terraform state, verbatim, when state exists. When no state has ever been posted, it SHALL respond in a way Terraform's `http` backend client treats as "no state yet" (`204` or `404`).

#### Scenario: Fetching state after at least one apply
- **WHEN** a client sends `GET /state` and at least one state has previously been posted
- **THEN** the response is `200` with a body identical to the most recently posted raw state JSON

#### Scenario: Fetching state before any apply
- **WHEN** a client sends `GET /state` and no state has ever been posted
- **THEN** the response is `204` or `404`, and Terraform proceeds as if uninitialized

### Requirement: State Backend persists posted state and derives resource entities
The State Backend SHALL respond to `POST /state` by, in a single transaction, storing the posted JSON body verbatim as a new state version and upserting one resource entity per entry in the body's `resources[]` array, keyed by the resource's `(type, name)` pair. A resource entity SHALL carry the resource's type, name, raw attribute map, and a reference to the state version it was last seen in.

#### Scenario: Posting state for the first time
- **WHEN** a client sends `POST /state` with a valid Terraform state JSON body containing one or more resources
- **THEN** the backend persists the raw body verbatim as the current state version, and one resource entity exists per entry in `resources[]`, each with the correct type, name, and attribute map

#### Scenario: Posting state again for an existing resource
- **WHEN** a client sends `POST /state` and a resource in the body has the same `(type, name)` as a resource entity from a prior post
- **THEN** the existing resource entity's attributes and state-version reference are updated in place, rather than a duplicate entity being created

### Requirement: State Backend purges state via DELETE
The State Backend SHALL respond to `DELETE /state` by retracting the current raw state and all resource entities, and SHALL respond `200`.

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
- **THEN** a subsequent `GET /state` returns the same raw state that was posted before the restart

### Requirement: Sample app's Terraform configuration uses the State Backend
The sample app's Terraform configuration under `terraform/` SHALL be configured with an `http` backend pointing at the State Backend, so `terraform apply` reads and writes state exclusively through the service rather than a local state file.

#### Scenario: Applying Terraform against the State Backend
- **WHEN** a developer runs `terraform init` and `terraform apply` in `terraform/` while the State Backend is running
- **THEN** `terraform apply` succeeds, and `terraform state list` reflects resource entities queryable from the State Backend's Datomic database
