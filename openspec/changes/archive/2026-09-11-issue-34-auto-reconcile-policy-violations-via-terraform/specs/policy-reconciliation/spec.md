## Purpose

Automatically fixes policy-violating resources by running Terraform, so a governance bypass (an out-of-band resource, or a resource that violates a rule registered after it was deployed) doesn't stay exploitable until a human intervenes by hand.

## ADDED Requirements

### Requirement: Reconciliation runs automatically as part of every Sync
Reconciliation SHALL run automatically as the final step of every Sync invocation — both on-demand and scheduled — without requiring a separate endpoint or a distinct trigger from the caller.

#### Scenario: An on-demand Sync triggers reconciliation
- **WHEN** Sync is triggered on demand (e.g. the CLI's `sync` subcommand over HTTP) and completes
- **THEN** reconciliation runs against current live state as part of that same Sync invocation, with no further action required by the caller

#### Scenario: A scheduled Sync triggers reconciliation
- **WHEN** the fixed-interval scheduled Sync run completes
- **THEN** reconciliation runs against current live state as part of that same scheduled run, exactly as it would for an on-demand Sync

### Requirement: Live state is evaluated against the policy Rule registry independent of drift
Reconciliation SHALL evaluate every registered policy Rule against current live state for every Terraform-managed Resource, regardless of whether that resource is currently flagged by the drift Rule. A managed resource that violates a registered Rule SHALL be considered for remediation even when its own attribute values have not drifted from what Terraform last asserted.

#### Scenario: A non-drifted managed resource violating a rule registered after deployment is considered
- **WHEN** a Terraform-managed resource was applied before a policy Rule was registered, has never drifted, and currently violates that Rule
- **THEN** reconciliation identifies it as a policy violation, independent of the drift Rule's result for that resource

### Requirement: Remediation path is dispatched by whether the violating resource itself is Terraform-managed
For each Resource identified as violating a registered policy Rule, reconciliation SHALL determine whether that specific resource is Terraform-managed (`:resource/managed?` `true`) or not, and dispatch remediation using that determination alone — applied per violating resource, not per drift category:
- Managed and drifted: remediate via `terraform apply` against the resource's existing configuration.
- Not managed (a Discovered Resource, including a rogue child resource whose parent happens to be managed): remediate via synthesized `terraform import` followed by `terraform destroy`.
- Managed and not drifted: no remediation action; the violation is recorded only (see the remediation-record requirement).

#### Scenario: A managed, drifted, policy-violating resource is applied
- **WHEN** a Terraform-managed resource has drifted out-of-band to a state that violates a registered policy Rule
- **THEN** reconciliation runs `terraform apply` using that resource's existing Terraform configuration

#### Scenario: An unmanaged, policy-violating resource is imported and destroyed
- **WHEN** a Discovered (unmanaged) Resource violates a registered policy Rule
- **THEN** reconciliation runs a synthesized `terraform import` followed by `terraform destroy` against that resource, and does not run a bare `terraform apply`

#### Scenario: A managed, non-drifted, policy-violating resource is recorded but not remediated
- **WHEN** a Terraform-managed resource violates a registered policy Rule but has not drifted (its live values match what Terraform last asserted)
- **THEN** reconciliation takes no remediation action against that resource, and records the violation with action `:none`

### Requirement: New-child drift on an already-managed parent is remediated as an unmanaged child, not via apply on the parent
When a policy Rule's violation is attributable to a specific child resource that was added out-of-band to an already Terraform-managed parent (New-Child Drift), reconciliation SHALL treat that specific child resource — not its managed parent — as the unit of remediation, and remediate it via synthesized import+destroy. Reconciliation SHALL NOT rely on a bare `terraform apply` against the parent to remove such a child, since Terraform's configuration never declared it and `apply` cannot remove what it never declared.

#### Scenario: A rogue ingress rule added to a managed security group is destroyed, not the group re-applied
- **WHEN** an ingress rule violating a registered policy Rule is added directly against the environment to an already Terraform-managed security group (out-of-band), and Sync observes it as New-Child Drift
- **THEN** reconciliation remediates the specific offending ingress rule via synthesized import+destroy, and the managed security group itself is not the target of a `terraform apply` remediation for this violation

### Requirement: The violating child entity is resolved for rules that only bind the parent
When a registered policy Rule's result binds a parent resource but the actual policy violation is attributable to one of that parent's child resources, reconciliation SHALL resolve the specific violating child entity (or entities) before dispatching remediation, rather than treating the parent itself as the remediation target.

#### Scenario: The specific violating security group rule is resolved from a flagged security group
- **WHEN** a registered policy Rule flags a security group as violating (because one of its ingress rules opens port 22 to the world), and that security group has one or more ingress rules matching the violation
- **THEN** reconciliation resolves the specific offending ingress rule entity or entities as the remediation target, distinct from the security group entity the Rule itself returned

### Requirement: Every remediation decision is recorded
Reconciliation SHALL persist a record of every remediation decision it makes — one per violating resource per reconciliation pass — capturing at minimum: the Resource, the Rule violated, the action taken (`:apply`, `:import-destroy`, or `:none`), and a timestamp. When the action created an Invocation (an `apply!`/`import!`/`destroy!` call), the record SHALL reference that Invocation. This record is a new, distinct entity type from Invocation — Invocation's meaning stays "an execution attempt, not a finding."

#### Scenario: An apply remediation is recorded with its Invocation
- **WHEN** reconciliation remediates a managed, drifted, policy-violating resource via `terraform apply`
- **THEN** a remediation record exists for that resource and rule with action `:apply`, a timestamp, and a reference to the Invocation the apply produced

#### Scenario: An import+destroy remediation is recorded
- **WHEN** reconciliation remediates an unmanaged, policy-violating resource via synthesized import+destroy
- **THEN** a remediation record exists for that resource and rule with action `:import-destroy` and a timestamp

#### Scenario: A record-only decision is recorded
- **WHEN** reconciliation identifies a managed, non-drifted, policy-violating resource
- **THEN** a remediation record exists for that resource and rule with action `:none` and a timestamp, with no Invocation reference

### Requirement: Non-violating resources are left untouched
Reconciliation SHALL NOT take any remediation action, and SHALL NOT create a remediation record, for a resource that does not violate any registered policy Rule.

#### Scenario: A compliant resource produces no action and no record
- **WHEN** reconciliation runs and a Resource (managed or Discovered) does not violate any registered policy Rule
- **THEN** no `terraform apply`, `import`, or `destroy` is run against that resource, and no remediation record is created for it
