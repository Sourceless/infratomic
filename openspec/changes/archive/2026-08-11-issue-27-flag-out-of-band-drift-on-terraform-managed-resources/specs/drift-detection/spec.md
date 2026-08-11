## Purpose

Detects when a Terraform-managed resource's live value has been changed out-of-band (directly against the environment, not through Terraform), by comparing each managed resource's most recent write source against what Terraform last asserted, and exposes that as a query-time Rule and endpoint operators can check on demand.

## ADDED Requirements

### Requirement: A query-time Rule flags drifted managed resources
The system SHALL provide a Rule function that, given the current database value, returns every managed Resource entity whose most recent `:resource/last-write-source` write is `:sync` and whose current attribute values differ from the values Terraform last asserted (as of the most recent transaction where `:resource/last-write-source` was `:terraform`). A managed resource whose most recent write source is `:terraform`, or whose `:sync`-sourced values match what Terraform last asserted, SHALL NOT be included.

#### Scenario: A resource changed out-of-band after being Terraform-applied is flagged
- **WHEN** a resource is created via Terraform apply, then changed directly against the environment (not through Terraform) to a different value, and Sync is run and observes that changed value
- **THEN** the drift Rule includes that resource in its results

#### Scenario: An unchanged Terraform-managed resource is not flagged
- **WHEN** a resource is created via Terraform apply and never changed out-of-band, and the drift Rule is evaluated (whether or not Sync has run)
- **THEN** the drift Rule does not include that resource in its results

#### Scenario: A discovered (never-Terraform-managed) resource is not flagged
- **WHEN** a resource exists only because Sync discovered it (it was never posted via Terraform apply, so it has no prior `:terraform`-sourced write)
- **THEN** the drift Rule does not include that resource in its results

### Requirement: The drift Rule is excluded from the pre-apply Policy Check
The drift Rule SHALL NOT be part of the set of Rules the Policy Check endpoint evaluates against a plan. It SHALL only ever be evaluated directly against live, already-persisted state.

#### Scenario: The Policy Check response is unaffected by drift
- **WHEN** a Terraform-managed resource currently has out-of-band drift flagged by the drift Rule, and a plan is submitted to the Policy Check endpoint
- **THEN** the Policy Check response's Violations are unaffected by that resource's drift — drift is never reported as a Policy Check Violation

### Requirement: Drift status is queryable via a read-only endpoint
The State Backend SHALL expose a read-only `GET /drift` endpoint that evaluates the drift Rule against current live state and returns the flagged resources, each identified by at least its type and id. Calling this endpoint SHALL NOT create, modify, or retract any resource entity or state version.

#### Scenario: Requesting drift status with no drift present
- **WHEN** `GET /drift` is called and no managed resource currently has out-of-band drift
- **THEN** the response indicates zero drifted resources

#### Scenario: Requesting drift status with drift present
- **WHEN** `GET /drift` is called and at least one managed resource currently has out-of-band drift
- **THEN** the response includes that resource, identified by at least its type and id

### Requirement: Terraform's last-asserted values remain recoverable after a drifting write
A `:sync`-sourced write to a previously Terraform-managed resource SHALL NOT destroy the values Terraform last asserted for that resource; those prior values SHALL remain reconstructable via the database's history, so the drift Rule can compare against them even after the live values have been overwritten.

#### Scenario: Terraform's prior value is still recoverable after drift is recorded
- **WHEN** a Terraform-managed resource's live value is changed out-of-band and Sync records the new value
- **THEN** the value Terraform last asserted for that resource is still recoverable from the database's history, distinct from the current live value
