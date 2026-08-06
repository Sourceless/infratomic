## Purpose

Provides queryable access to the resources deployed via the sample Terraform app, stored in the State Backend's Datomic database, so infrastructure questions can be answered as structural queries over decomposed entities instead of by parsing raw state JSON.

## ADDED Requirements

### Requirement: Query all deployed resources
The query namespace SHALL provide a function that returns every currently deployed resource, each identified by at least its `:resource/id` and `:resource/type`.

#### Scenario: Listing all resources after apply
- **WHEN** the sample Terraform app has been applied and the query for all deployed resources is called
- **THEN** it returns one entry per resource currently persisted in the database, matching what `terraform state list` would show

### Requirement: Query resources by type
The query namespace SHALL provide a function that, given a Terraform resource type (e.g. `"aws_security_group"`), returns only the currently deployed resources of that type.

#### Scenario: Filtering by a type with matches
- **WHEN** the query for resources by type is called with a type present among deployed resources
- **THEN** it returns only resources of that type, and no resources of any other type

#### Scenario: Filtering by a type with no matches
- **WHEN** the query for resources by type is called with a type not present among deployed resources
- **THEN** it returns an empty result

### Requirement: Query resources by attribute value
The query namespace SHALL provide a function that, given an attribute key and a value, returns every currently deployed resource that has that attribute set to that value — searching both generic key/value attributes and modeled/typed attributes, so the search is unified regardless of how a given attribute happens to be stored.

#### Scenario: Matching a generic (unmodeled) attribute
- **WHEN** the query for resources by attribute value is called with a key/value pair that only exists as a generic key/value attribute on some resource
- **THEN** that resource is included in the result

#### Scenario: Matching a modeled (typed) attribute
- **WHEN** the query for resources by attribute value is called with a key/value pair that corresponds to a modeled, typed attribute on some resource
- **THEN** that resource is included in the result, regardless of whether the query value is supplied as the attribute's native type or an equivalent representation

#### Scenario: No matching resources
- **WHEN** the query for resources by attribute value is called with a key/value pair no deployed resource has
- **THEN** it returns an empty result

### Requirement: Query security groups with port 22 open to the internet
The query namespace SHALL provide a function that returns every `aws_security_group` resource that has at least one associated `aws_security_group_rule` permitting ingress on port 22 from `0.0.0.0/0`, resolved via the rule's `security_group_id` back to its owning security group. Security groups with no such rule SHALL NOT be included, and this is a Datalog query over the typed `aws_security_group_rule` structure (port range, protocol, CIDR blocks), not an application-level scan of a JSON blob.

#### Scenario: A security group with port 22 open to the internet
- **WHEN** the sample app has been applied and includes a security group with an `aws_security_group_rule` permitting ingress on port 22 from `0.0.0.0/0`
- **THEN** the query for insecure security groups includes that security group

#### Scenario: A security group without port 22 open to the internet
- **WHEN** the sample app has been applied and includes a security group with no rule opening port 22 to `0.0.0.0/0` (e.g. no port-22 rule at all, a port-22 rule restricted to a specific CIDR, or a port range that excludes 22)
- **THEN** the query for insecure security groups does not include that security group
