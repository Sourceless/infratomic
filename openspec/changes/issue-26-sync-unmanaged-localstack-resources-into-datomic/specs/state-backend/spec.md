## MODIFIED Requirements

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

## ADDED Requirements

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
