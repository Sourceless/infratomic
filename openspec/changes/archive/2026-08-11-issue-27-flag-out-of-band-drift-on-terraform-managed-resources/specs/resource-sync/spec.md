## MODIFIED Requirements

### Requirement: Resources are matched by AWS resource id, not by Terraform address
Sync SHALL match each resource it finds in LocalStack against existing Resource entities using its AWS-assigned resource id, never a Terraform `(type, name)` pair — Discovered Resources have no Terraform name to match on. A security group rule SHALL be matched by its own AWS-assigned rule id, distinct from the id of the security group it belongs to. When a match is a Terraform-managed resource, Sync SHALL compare the live attribute values it observed against that resource's currently stored attribute values; if they differ, Sync SHALL update the resource's stored attributes to the observed live values and record the write's source as `:sync` (see the writer/source tagging requirement); if they are identical, Sync SHALL make no write for that resource.

#### Scenario: A resource already known to the State Backend is not duplicated
- **WHEN** a resource already exists as a Resource entity (Terraform-managed or previously discovered) with a given AWS resource id, and Sync is triggered while that same AWS resource is still present in LocalStack
- **THEN** Sync does not create a new Resource entity for it; a previously-discovered resource is updated in place, and a Terraform-managed resource's stored attributes are updated only if the observed live values differ from what is stored

#### Scenario: Running Sync twice produces no duplicates
- **WHEN** Sync is triggered twice in a row with no changes to LocalStack's resources in between
- **THEN** the second run does not create any new Resource entities for resources the first run already discovered

#### Scenario: A Terraform-managed resource changed out-of-band is updated by Sync
- **WHEN** a Terraform-managed resource's live value in LocalStack has been changed directly (not through Terraform) since it was last stored, and Sync is triggered
- **THEN** Sync updates that resource's stored attributes to the observed live values and records the write's source as `:sync`, rather than skipping the resource

#### Scenario: An unchanged Terraform-managed resource produces no write
- **WHEN** a Terraform-managed resource's live value in LocalStack is identical to what is currently stored, and Sync is triggered
- **THEN** Sync makes no write for that resource, and its write source remains whatever it was before this Sync run

## ADDED Requirements

### Requirement: Every Sync write records its source
Every write Sync makes to a Resource entity — whether creating a newly discovered resource or updating a previously-stored resource (discovered or Terraform-managed) whose live value changed — SHALL record `:resource/last-write-source` as `:sync` on that entity.

#### Scenario: A newly discovered resource is tagged sync-sourced
- **WHEN** Sync ingests a resource it has not seen before
- **THEN** the resulting Resource entity's `:resource/last-write-source` is `:sync`

#### Scenario: A drift-updated resource is tagged sync-sourced
- **WHEN** Sync updates a previously Terraform-managed resource because its observed live value differs from what was stored
- **THEN** that resource's `:resource/last-write-source` is `:sync` after the update

### Requirement: Sync's summary distinguishes drift updates from discoveries and ordinary updates
`sync!`'s summary map SHALL include a `:drifted` bucket, listing each Terraform-managed resource Sync updated during that run because its observed live value differed from what was stored (identified by at least type and id). This is distinct from the existing `:discovered` bucket (new resources) and `:updated` bucket (previously-discovered, non-Terraform-managed resources that received new values). A Terraform-managed resource whose observed live value matched what was stored (no write made) SHALL NOT appear in `:drifted`, `:discovered`, or `:updated`.

#### Scenario: A sync run's summary reports a drifted resource separately
- **WHEN** a Sync run updates one previously Terraform-managed resource due to observed drift, discovers one entirely new resource, and updates one previously-discovered resource's values
- **THEN** the summary's `:drifted` bucket contains only the drift-updated resource, `:discovered` contains only the new resource, and `:updated` contains only the previously-discovered resource that received new values
