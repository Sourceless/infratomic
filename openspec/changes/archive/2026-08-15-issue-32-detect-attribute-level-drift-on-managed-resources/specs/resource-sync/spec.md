## MODIFIED Requirements

### Requirement: Resources are matched by AWS resource id, not by Terraform address
Sync SHALL match each resource it finds in LocalStack against existing Resource entities using its AWS-assigned resource id, never a Terraform `(type, name)` pair — Discovered Resources have no Terraform name to match on. A security group rule SHALL be matched by its own AWS-assigned rule id, distinct from the id of the security group it belongs to. `aws_iam_role_policy_attachment` — which has no AWS-assigned id of its own — SHALL instead be matched by the composite pair of its role name and policy ARN, both together. When a match is a Terraform-managed resource, Sync SHALL compare the live attribute values it observed against that resource's currently stored attribute values; if they differ, Sync SHALL update the resource's stored attributes to the observed live values and record the write's source as `:sync` (see the writer/source tagging requirement); if they are identical, Sync SHALL make no write for that resource.

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

#### Scenario: An IAM role policy attachment is matched by role and policy ARN together, not a single id
- **WHEN** a Terraform-managed IAM role has a Terraform-managed policy attachment already stored, and Sync is triggered while that same attachment (same role, same policy ARN) still exists
- **THEN** Sync matches it to its existing Resource entity by the combination of role name and policy ARN, and does not create a duplicate Resource entity for it

#### Scenario: A hand-attached policy on a known role is discovered, not matched to an unrelated attachment
- **WHEN** a Terraform-managed IAM role already has one Terraform-managed policy attachment, and a different policy is attached to that same role directly against the environment (not through Terraform)
- **THEN** Sync ingests the newly attached policy as a new Discovered Resource, distinct from the existing Terraform-managed attachment, rather than treating them as the same resource

## ADDED Requirements

### Requirement: Sync fetches IAM role policy attachments for every already-known Terraform-managed IAM role
For every `aws_iam_role` Resource entity currently stored as Terraform-managed, Sync SHALL query LocalStack's IAM API for that role's attached managed policies and consider each attached policy a candidate `aws_iam_role_policy_attachment` resource, translated to the same Terraform-attribute-shaped map (`role`, `policy_arn`) `POST /state` already knows how to decompose for that type. Sync SHALL NOT attempt to enumerate IAM roles that exist in LocalStack but have no corresponding Terraform-managed `aws_iam_role` Resource entity — unlike every other resource type Sync covers, IAM role policy attachment discovery is scoped to roles Sync already knows about via prior Terraform applies, not a blanket live scan.

#### Scenario: Attachments are fetched for a known managed role
- **WHEN** a Terraform-managed IAM role exists with one or more policies attached directly against the environment, and Sync is triggered
- **THEN** each attached policy is considered as a candidate `aws_iam_role_policy_attachment` resource for that role

#### Scenario: No attachments are fetched for a role Sync has no record of
- **WHEN** an IAM role exists in LocalStack that was never applied via Terraform (so no `aws_iam_role` Resource entity for it exists), and Sync is triggered
- **THEN** Sync does not query for, or ingest, any policy attachments for that role
