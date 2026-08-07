## ADDED Requirements

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
