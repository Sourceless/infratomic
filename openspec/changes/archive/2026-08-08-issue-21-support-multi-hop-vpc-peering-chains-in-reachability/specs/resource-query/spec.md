## ADDED Requirements

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
