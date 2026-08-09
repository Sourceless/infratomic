## Purpose

Discovers resources that exist in LocalStack but are not known to the State Backend through Terraform, and ingests them as Discovered (unmanaged) Resource entities, so existing policy Rules can evaluate real deployed infrastructure regardless of whether Terraform created it.

## ADDED Requirements

### Requirement: Sync discovers resources across every modeled resource type
When triggered, Sync SHALL query LocalStack's EC2 API for every resource type the State Backend models for storage (security groups, security group rules, VPCs, subnets, route tables, routes, route table associations, internet gateways, VPC peering connections, and instances), not only the types exercised by a single verification scenario.

#### Scenario: Triggering a sync with multiple resource types present
- **WHEN** LocalStack has resources of several modeled types (e.g. a security group, a VPC, a subnet) and Sync is triggered
- **THEN** resources of every one of those modeled types are considered for discovery, not just one type

### Requirement: Unmatched resources are ingested as Discovered Resources
For each resource Sync finds in LocalStack that has no existing Resource entity already matching its AWS resource id, Sync SHALL create a new Resource entity tagged as discovered (unmanaged), with its attributes decomposed and stored the same way a Terraform-managed resource's attributes are.

#### Scenario: A resource created outside Terraform is discovered
- **WHEN** a security group is created directly via LocalStack's EC2 API (not through Terraform) and Sync is triggered
- **THEN** a new Resource entity exists for that security group, tagged as discovered (unmanaged), with its attributes queryable the same way a Terraform-managed resource's are

### Requirement: Resources are matched by AWS resource id, not by Terraform address
Sync SHALL match each resource it finds in LocalStack against existing Resource entities using its AWS-assigned resource id, never a Terraform `(type, name)` pair — Discovered Resources have no Terraform name to match on. A security group rule SHALL be matched by its own AWS-assigned rule id, distinct from the id of the security group it belongs to.

#### Scenario: A resource already known to the State Backend is not duplicated
- **WHEN** a resource already exists as a Resource entity (Terraform-managed or previously discovered) with a given AWS resource id, and Sync is triggered while that same AWS resource is still present in LocalStack
- **THEN** Sync does not create a new Resource entity for it; a previously-discovered resource is instead updated in place, and a Terraform-managed resource is left untouched (still tagged managed)

#### Scenario: Running Sync twice produces no duplicates
- **WHEN** Sync is triggered twice in a row with no changes to LocalStack's resources in between
- **THEN** the second run does not create any new Resource entities for resources the first run already discovered

### Requirement: Discovered Resources are evaluated by existing Rules identically to Terraform-managed resources
Once ingested, a Discovered Resource entity SHALL be visible to the State Backend's existing query and Rule functions (e.g. the port-22-open security group Rule) exactly as a Terraform-managed resource is, with no distinction made based on how the resource was ingested.

#### Scenario: A discovered insecure security group is flagged
- **WHEN** a security group with port 22 open to `0.0.0.0/0` is created directly via LocalStack (not Terraform), Sync is triggered, and the port-22-open Rule is evaluated against current state
- **THEN** that security group is included in the Rule's results, exactly as a Terraform-managed security group with the same configuration would be

### Requirement: Sync runs only when explicitly triggered
Sync SHALL only run in response to an explicit trigger — it SHALL NOT run automatically, on a timer, or as a side effect of any other operation (e.g. `POST`/`GET /state`, a Policy Check).

#### Scenario: No sync happens without being triggered
- **WHEN** the State Backend is running, resources exist in LocalStack that are not yet known to it, and Sync is never explicitly triggered
- **THEN** no Discovered Resource entities are created for those resources
