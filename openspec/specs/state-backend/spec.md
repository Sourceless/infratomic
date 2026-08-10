# state-backend Specification

## Purpose

Implements Terraform's `http` state backend protocol as a Clojure service, decomposing the sample app's Terraform state into an embedded Datomic database — a state-version entity plus one entity per managed resource — and reconstructing a valid state JSON document from those entities on `GET`, so infrastructure can eventually be queried instead of parsed from raw state JSON. No raw state JSON is stored: Datomic dev-local's 4096-byte-per-string limit makes verbatim storage of the sample app's real state document (~12.4KB) impossible.

## Requirements

### Requirement: State Backend serves current state via GET
The State Backend SHALL respond to `GET /state` with a Terraform-state-JSON document reconstructed from the most recently posted state's decomposed entities, when state exists. The reconstructed document need not be byte-identical to what was last posted, but SHALL be a state document Terraform's `http` backend client accepts, reflecting the same top-level metadata (format version, Terraform version, serial, lineage, outputs) and the same managed resources with their attributes as most recently posted. The resources reflected in the document SHALL be limited to Terraform-managed resources — a Discovered (unmanaged) Resource entity ingested by Sync SHALL NOT appear, since Terraform never created it and must not be told it owns it. When no state has ever been posted, it SHALL respond in a way Terraform's `http` backend client treats as "no state yet" (`204` or `404`).

#### Scenario: Fetching state after at least one apply
- **WHEN** a client sends `GET /state` and at least one state has previously been posted
- **THEN** the response is `200` with a body that is a valid Terraform state JSON document reflecting the most recently posted top-level metadata and managed resources

#### Scenario: Fetching state before any apply
- **WHEN** a client sends `GET /state` and no state has ever been posted
- **THEN** the response is `204` or `404`, and Terraform proceeds as if uninitialized

#### Scenario: Reconstructed state round-trips without drift
- **WHEN** a client applies Terraform against the State Backend, the service is restarted, and the client then runs `terraform plan`
- **THEN** `terraform plan` reports no changes, confirming the state reconstructed via `GET` is equivalent to what was posted

#### Scenario: A Discovered Resource is excluded from GET
- **WHEN** a Discovered Resource entity exists (ingested by Sync) alongside Terraform-managed resources, and a client sends `GET /state`
- **THEN** the reconstructed state document's `resources[]` includes the Terraform-managed resources but not the Discovered Resource

### Requirement: State Backend persists posted state and derives resource entities
The State Backend SHALL respond to `POST /state` by, in a single transaction, storing the posted document's top-level metadata (format version, Terraform version, serial, lineage, outputs) as a new state-version entity, and upserting one resource entity per **managed** entry in the body's `resources[]` array (`mode == "managed"`), keyed by the resource's `(type, name)` pair. A resource entity SHALL carry the resource's type, name, enough additional structural metadata to reconstruct a Terraform-acceptable state document, a reference to the state version it was last seen in, and its attributes decomposed into datoms rather than stored as a single opaque JSON string: attributes covered by the resource type's entry in a data-driven schema map SHALL be stored as typed, structural Datomic attributes; all other attributes SHALL be stored as generic key/value sub-entities attached to the resource. Entries in `resources[]` with `mode == "data"` (data sources) SHALL NOT be persisted as resource entities, since Terraform always re-reads data sources fresh on every `plan`/`apply` regardless of prior state.

#### Scenario: Posting state for the first time
- **WHEN** a client sends `POST /state` with a valid Terraform state JSON body containing one or more managed resources
- **THEN** the backend persists the state's top-level metadata as the current state version, and one resource entity exists per **managed** entry in `resources[]`, each with the correct type, name, and decomposed attributes

#### Scenario: Posting state again for an existing resource
- **WHEN** a client sends `POST /state` and a resource in the body has the same `(type, name)` as a resource entity from a prior post
- **THEN** the existing resource entity's decomposed attributes and state-version reference are updated in place, rather than a duplicate entity being created, and datoms for attributes no longer present are retracted rather than left stale

#### Scenario: Posting state with data-source entries
- **WHEN** a client sends `POST /state` with a body whose `resources[]` includes entries with `mode == "data"`
- **THEN** no resource entity is created or updated for those entries, while managed entries are persisted normally

### Requirement: Modeled attributes are stored as typed, structural datoms
For a resource type with an entry in the data-driven schema map, each attribute the schema map declares SHALL be stored as its own typed Datomic attribute (e.g. a numeric port as `:db.type/long`) directly on the resource entity, enabling exact-match and range Datalog queries over that attribute without parsing a JSON blob.

#### Scenario: A modeled numeric attribute is queryable by range
- **WHEN** a resource of a schema-mapped type is persisted with a modeled numeric attribute (e.g. `aws_security_group_rule`'s `from_port`/`to_port`)
- **THEN** a Datalog query can match that resource by a range or equality condition on the attribute's typed value, without parsing any string blob

### Requirement: Unmodeled attributes fall back to generic key/value datoms
Any attribute not covered by the schema map for its resource's type — including every attribute of a resource type with no schema map entry at all — SHALL be stored as a generic key/value sub-entity attached to the resource: a real, exact-match-searchable datom pairing the attribute's key with its value as a string, rather than folded into an opaque JSON blob.

#### Scenario: An attribute of an unmodeled resource type is searchable
- **WHEN** a resource of a type with no schema map entry is persisted with an attribute
- **THEN** that attribute is stored as a generic key/value datom and can be found by an exact-match Datalog query on its key and value

#### Scenario: An unmodeled attribute of an otherwise-modeled resource type is searchable
- **WHEN** a resource of a schema-mapped type is persisted with an attribute the schema map does not declare
- **THEN** that attribute is stored as a generic key/value datom alongside the resource's modeled attributes, not silently dropped

### Requirement: Nested unmodeled attribute values are flattened
An unmodeled attribute value that is a nested compound structure (a list or map) SHALL be flattened into multiple generic key/value datoms, one per leaf value, with keys built from the path to that leaf (dotted for map keys, indexed for list positions), so each leaf is individually searchable rather than stored as a single JSON blob under the top-level key.

#### Scenario: A nested map attribute is flattened
- **WHEN** an unmodeled attribute's value is a map, e.g. `{"variables": {"FOO": "bar"}}` under key `environment`
- **THEN** it is stored as a generic key/value datom with key `environment.variables.FOO` and value `"bar"`, independently searchable

#### Scenario: A list attribute is flattened
- **WHEN** an unmodeled attribute's value is a list
- **THEN** each element is stored as a generic key/value datom with an indexed key derived from the attribute's key and the element's position

### Requirement: Oversized attribute values fall back to opaque storage
A single attribute value (modeled or generic) that would exceed Datomic dev-local's 4096-byte-per-string limit SHALL NOT fail the transaction; it SHALL instead be stored via an opaque fallback (e.g. JSON-encoded, truncated, or hash-referenced) for that value alone, without affecting the storage of any other attribute on the same resource.

#### Scenario: One oversized attribute does not block persisting the resource
- **WHEN** a resource is persisted with one attribute value whose stored representation would exceed 4096 bytes, alongside other attributes within the limit
- **THEN** the transaction succeeds, the oversized attribute is stored via the opaque fallback, and all other attributes are stored via their normal (modeled or generic) representation

### Requirement: Decomposed attributes reconstruct into their original shape on GET
`GET /state` SHALL reconstruct each resource's attribute map from its decomposed datoms (modeled, generic, flattened, and oversized-fallback) into a value equivalent to what was originally posted, preserving nested structure and value types, so reconstruction remains transparent to Terraform's `http` backend client.

#### Scenario: A flattened nested attribute round-trips
- **WHEN** a resource with a flattened nested unmodeled attribute (per the flattening requirement) is fetched via `GET /state`
- **THEN** the reconstructed attribute map has the original nested map/list shape at that attribute's key, not the flattened dotted/indexed keys

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

### Requirement: Resource entities are tagged managed or discovered
Every Resource entity SHALL carry a flag identifying its origin: Terraform-managed (persisted via `POST /state`) or discovered (ingested by Sync). This flag SHALL determine the resource's visibility in `GET /state` and its eligibility for retraction when a `POST /state` no longer mentions it.

#### Scenario: A resource posted by Terraform is tagged managed
- **WHEN** a client sends `POST /state` with a managed resource entry
- **THEN** the resulting Resource entity is tagged Terraform-managed

### Requirement: Discovered Resources are not retracted by a Terraform apply
`POST /state`'s removal of resource entities no longer present in the posted body's managed `resources[]` (e.g. resources destroyed or removed from Terraform's state) SHALL only ever consider Terraform-managed resource entities for removal. A Discovered Resource entity SHALL NOT be removed as a side effect of any `POST /state`, regardless of whether it is mentioned in the posted body.

#### Scenario: A Discovered Resource survives a subsequent Terraform apply
- **WHEN** a Discovered Resource entity exists (ingested by Sync), and a client sends `POST /state` with a body whose managed `resources[]` does not mention it
- **THEN** the Discovered Resource entity still exists and is still queryable afterward
