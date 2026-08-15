## MODIFIED Requirements

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

#### Scenario: An `aws_instance`'s security group membership change is caught by this existing mechanism, not a new one
- **WHEN** a Terraform-managed `aws_instance`'s security group membership (`vpc_security_group_ids`) is changed directly against the environment (not through Terraform), and Sync is run and observes the changed membership
- **THEN** the drift Rule includes that instance in its results via this attribute-comparison mechanism, with no separate child-detection mechanism involved

### Requirement: Drift status is queryable via a read-only endpoint
The State Backend SHALL expose a read-only `GET /drift` endpoint that evaluates the drift Rule against current live state and returns the flagged resources, each identified by at least its type and id. A returned entry for a managed parent resource MAY additionally include a `new_children` key, a `removed_children` key, or both, each a list of `{type, id}` objects identifying new-child or removed-child drift on that parent (see the new-child and removed-child requirements below); an entry with neither key present means only plain attribute-level drift (or none) applies to that resource, preserving the existing flat `{type, id}` shape unchanged for that case. Calling this endpoint SHALL NOT create, modify, or retract any resource entity or state version.

#### Scenario: Requesting drift status with no drift present
- **WHEN** `GET /drift` is called and no managed resource currently has out-of-band drift of any kind
- **THEN** the response indicates zero drifted resources

#### Scenario: Requesting drift status with attribute-level drift present
- **WHEN** `GET /drift` is called and at least one managed resource currently has out-of-band attribute-level drift
- **THEN** the response includes that resource, identified by at least its type and id, with no `new_children` or `removed_children` keys

#### Scenario: Requesting drift status with new-child drift present
- **WHEN** `GET /drift` is called and a managed parent resource currently has at least one new out-of-band child (see the new-child requirement below)
- **THEN** the response includes an entry for that parent with a `new_children` list containing that child, each identified by at least its type and id

#### Scenario: Requesting drift status with removed-child drift present
- **WHEN** `GET /drift` is called and a managed parent resource currently has at least one managed child that has gone missing out-of-band (see the removed-child requirement below)
- **THEN** the response includes an entry for that parent with a `removed_children` list containing that child, each identified by at least its type and id

## ADDED Requirements

### Requirement: A query-time Rule flags new out-of-band children of managed resources
The system SHALL provide a Rule function that, given the current database value, returns every managed (`:resource/managed? true`) parent resource that has at least one associated child resource of a foreign-key-bearing child type - `aws_security_group_rule` (via `security_group_id`), `aws_route` (via `route_table_id`), `aws_route_table_association` (via `route_table_id` and, independently, `subnet_id`), or `aws_iam_role_policy_attachment` (via `role`, matched against the parent role's name) - where that child resource is itself a Discovered Resource (`:resource/managed? false`), joined to the parent by that child type's foreign-key attribute equalling the parent's own identifying attribute. A managed parent with no such child, or whose only children of these types are themselves Terraform-managed, SHALL NOT be included. An `aws_route` or `aws_route_table_association` entry that has no Terraform `aws_route`/`aws_route_table_association` counterpart (the AWS-implicit default local route or main-table association) SHALL NOT be treated as a child for this purpose.

#### Scenario: A hand-added security group rule is flagged as new-child drift on its security group
- **WHEN** a Terraform-managed security group exists, an ingress rule is added directly against the environment (not through Terraform) referencing that security group's id, and Sync is run and discovers that rule
- **THEN** the new-child Rule includes the security group in its results, with the rule identified among its new children

#### Scenario: A hand-added route is flagged as new-child drift on its route table
- **WHEN** a Terraform-managed route table exists, an explicit route is added directly against the environment referencing that route table's id, and Sync is run and discovers that route
- **THEN** the new-child Rule includes the route table in its results, with the route identified among its new children

#### Scenario: A hand-added route table association is flagged as new-child drift on both its route table and its subnet
- **WHEN** a Terraform-managed route table and a Terraform-managed subnet both exist, an association between them is added directly against the environment, and Sync is run and discovers that association
- **THEN** the new-child Rule includes both the route table and the subnet in its results, each with that association identified among its new children

#### Scenario: A hand-added IAM role policy attachment is flagged as new-child drift on its role
- **WHEN** a Terraform-managed IAM role exists, a policy is attached to it directly against the environment (not through Terraform), and Sync is run and discovers that attachment
- **THEN** the new-child Rule includes the role in its results, with the attachment identified among its new children

#### Scenario: A Terraform-managed child is not flagged as new-child drift
- **WHEN** a Terraform-managed parent resource has a child resource of one of the covered types that is itself Terraform-managed (applied normally, not out-of-band)
- **THEN** the new-child Rule does not include that child among the parent's new children

#### Scenario: An AWS-implicit route or association is not flagged as new-child drift
- **WHEN** Sync runs against a Terraform-managed route table that has AWS's implicit default local route or implicit main-table association
- **THEN** the new-child Rule does not treat that implicit route or association as a new child

### Requirement: A query-time Rule flags managed children that have disappeared out-of-band
The system SHALL provide a Rule function that, given the current database value, returns every managed parent resource that has at least one previously-known managed child of a foreign-key-bearing child type (the same four types and foreign-key relationships as the new-child Rule) whose most recent full Sync pass ran and did not find it present in the live environment. A managed parent with no such missing child SHALL NOT be included. This detection SHALL be based solely on an additive signal recorded by Sync, distinct from and never affecting the resource entity's own identity, type, or modeled attribute values.

#### Scenario: A managed security group rule removed out-of-band is flagged as removed-child drift on its security group
- **WHEN** a Terraform-managed security group has a Terraform-managed ingress rule, that rule is deleted directly against the environment (not through Terraform), and Sync is run and does not find it
- **THEN** the removed-child Rule includes the security group in its results, with the rule identified among its removed children

#### Scenario: A managed route removed out-of-band is flagged as removed-child drift on its route table
- **WHEN** a Terraform-managed route table has a Terraform-managed explicit route, that route is deleted directly against the environment, and Sync is run and does not find it
- **THEN** the removed-child Rule includes the route table in its results, with the route identified among its removed children

#### Scenario: A managed route table association removed out-of-band is flagged as removed-child drift on both its route table and its subnet
- **WHEN** a Terraform-managed route table association exists between a Terraform-managed route table and a Terraform-managed subnet, that association is deleted directly against the environment, and Sync is run and does not find it
- **THEN** the removed-child Rule includes both the route table and the subnet in its results, each with that association identified among its removed children

#### Scenario: A managed IAM role policy attachment removed out-of-band is flagged as removed-child drift on its role
- **WHEN** a Terraform-managed IAM role has a Terraform-managed policy attachment, that attachment is detached directly against the environment (not through Terraform), and Sync is run and does not find it
- **THEN** the removed-child Rule includes the role in its results, with the attachment identified among its removed children

#### Scenario: A managed child a Sync pass has not yet checked for is not flagged as removed
- **WHEN** a Terraform-managed child of one of the covered types was created via Terraform apply and no Sync pass covering its type has run since
- **THEN** the removed-child Rule does not include its parent in its results on account of that child

#### Scenario: A managed child that reappears after being flagged removed is no longer flagged
- **WHEN** a managed child was previously flagged as removed by a Sync pass that did not find it, and a later Sync pass finds it present again
- **THEN** the removed-child Rule no longer includes its parent in its results on account of that child

### Requirement: Removed-child detection never changes what GET /state reports
Detecting that a previously-known managed child has disappeared out-of-band SHALL NOT retract that child's Resource entity, and SHALL NOT retract or alter any of that entity's modeled or generic attribute values. `GET /state` reconstruction SHALL report that child exactly as it did before the disappearance was detected, for as long as its Resource entity remains Terraform-managed.

#### Scenario: A removed-child-flagged resource is still reported by GET /state
- **WHEN** a managed child has been flagged as removed by the removed-child Rule
- **THEN** `GET /state` still reports that resource, with its attributes unchanged from their last Terraform- or Sync-asserted values
