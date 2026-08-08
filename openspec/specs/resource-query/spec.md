# resource-query Specification

## Purpose

Provides queryable access to the resources deployed via the sample Terraform app, stored in the State Backend's Datomic database, so infrastructure questions can be answered as structural queries over decomposed entities instead of by parsing raw state JSON.

## Requirements

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

### Requirement: Query network reachability between resources
The query namespace SHALL provide a `reachable?`-style function that, given a source resource identifier and a target (either a resource identifier or a CIDR/IP string such as `"0.0.0.0/0"`), answers whether network traffic from the source can reach the target by traversing the deployed VPC/subnet/route-table/security-group graph. The traversal SHALL consider a path reachable when a route exists from the source's subnet to the target (directly within the same subnet, via a local route within the same VPC, via a route to a VPC peering connection for cross-VPC targets, or via a route to an internet gateway for internet-bound targets) and the forward-direction security group rules permit it (the source's egress rules and, for a resource target, the target's ingress rules); the return path SHALL NOT be separately checked, since AWS security groups are stateful. A resource SHALL always be considered reachable from itself.

#### Scenario: Two resources in the same subnet can reach each other
- **WHEN** `reachable?` is called with two workload resources placed in the same subnet, with security group rules permitting the traffic
- **THEN** it returns a truthy/reachable result

#### Scenario: Same-subnet reachability blocked by security groups
- **WHEN** `reachable?` is called with two workload resources placed in the same subnet, but the source's egress rules or the target's ingress rules do not permit the traffic
- **THEN** it returns a falsy/not-reachable result

#### Scenario: Resources in different VPCs can reach each other via peering
- **WHEN** `reachable?` is called with two workload resources in different VPCs, a VPC peering connection exists between those VPCs, each VPC's route table has a route to the peering connection for the other VPC's CIDR, and security group rules permit the traffic
- **THEN** it returns a truthy/reachable result

#### Scenario: Cross-VPC reachability blocked by missing peering connection
- **WHEN** `reachable?` is called with two workload resources in different VPCs and no VPC peering connection exists between those VPCs
- **THEN** it returns a falsy/not-reachable result

#### Scenario: Cross-VPC reachability blocked by a missing route despite peering
- **WHEN** `reachable?` is called with two workload resources in different VPCs, a VPC peering connection exists between those VPCs, but at least one side's route table has no route to the peering connection
- **THEN** it returns a falsy/not-reachable result

#### Scenario: A resource can reach the public internet
- **WHEN** `reachable?` is called with a workload resource as the source and `"0.0.0.0/0"` as the target, and that workload's subnet's route table has a route to an internet gateway, with the source's egress rules permitting the traffic
- **THEN** it returns a truthy/reachable result

#### Scenario: Internet reachability blocked by a missing route or gateway
- **WHEN** `reachable?` is called with a workload resource as the source and `"0.0.0.0/0"` as the target, and that workload's subnet's route table has no route to an internet gateway
- **THEN** it returns a falsy/not-reachable result

#### Scenario: A resource always reaches itself
- **WHEN** `reachable?` is called with the same resource as both source and target
- **THEN** it returns a truthy/reachable result, regardless of security group or route configuration

### Requirement: Query multi-hop VPC peering chain reachability
The query namespace SHALL provide a `reachable-within-hops?`-style function that, given a source resource identifier, a target (either a resource identifier or a CIDR/IP string such as `"0.0.0.0/0"`), and a maximum hop count, answers whether the source can reach the target by traversing a chain of `aws_vpc_peering_connection` hops, where one hop is one peering connection traversed. The traversal SHALL be expressed as genuine self-referential recursive Datalog — a rule whose body invokes itself — bounded by a hard counter that is checked and decremented on each recursive step, not a fixed set of clauses duplicated per hop count and not an unbounded traversal merely checked against the limit afterward. The traversal SHALL succeed only when a chain of peering connections of length at most the given hop count connects the source's VPC to the target's VPC (or, for a CIDR target, to a VPC with a route to an internet gateway, in which case the final internet-gateway step SHALL NOT consume hop budget), and the forward-direction security group rules at the true endpoints permit it (the source's egress rules and, for a resource target, the target's ingress rules); intermediate VPCs in the chain are not security-group-checked. This function SHALL NOT alter the behavior, signature, or cost profile of the existing single-hop `reachable?` function.

#### Scenario: A full multi-hop peering chain is reachable within the hop limit
- **WHEN** `reachable-within-hops?` is called with a source and target connected only by a chain of peering connections through intermediate VPCs (no direct peering shortcut between source and target), and `max-hops` is at least the number of peering connections in that chain
- **THEN** it returns a truthy/reachable result

#### Scenario: A broken link in the middle of the chain blocks reachability
- **WHEN** `reachable-within-hops?` is called with a source and target whose only potential path is a peering chain, but a peering connection or its route is missing partway through that chain
- **THEN** it returns a falsy/not-reachable result, regardless of `max-hops`

#### Scenario: The hop limit is too low to reach an otherwise-valid target
- **WHEN** `reachable-within-hops?` is called with a source and target connected by a genuine, unbroken peering chain, but `max-hops` is lower than the number of peering connections that chain requires
- **THEN** it returns a falsy/not-reachable result, even though a longer valid path exists

#### Scenario: A resource always reaches itself regardless of hop limit
- **WHEN** `reachable-within-hops?` is called with the same resource as both source and target
- **THEN** it returns a truthy/reachable result, even when `max-hops` is `0`

#### Scenario: Existing single-hop reachability is unaffected
- **WHEN** `reachable?` is called with any source and target as before
- **THEN** its result and behavior are unchanged by the addition of `reachable-within-hops?`
