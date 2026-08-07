## Purpose

Provisions a multi-VPC, multi-subnet network graph in the sample Terraform app — VPCs, subnets, a route table, an internet gateway, a VPC peering connection, and workload instances placed across them — so the State Backend's query namespace has real, traversable network topology to answer reachability questions against.

## ADDED Requirements

### Requirement: Multiple VPCs are provisioned
The sample app SHALL provision at least two `aws_vpc` resources, so cross-VPC reachability scenarios (both permitted via peering and blocked) have distinct VPCs to traverse between.

#### Scenario: Two VPCs exist after apply
- **WHEN** `terraform apply` completes successfully
- **THEN** at least two `aws_vpc` resources exist and are listed by `terraform state list`

### Requirement: Multiple subnets are provisioned across the VPCs
The sample app SHALL provision multiple `aws_subnet` resources, each referencing its owning VPC via `vpc_id`, with at least two subnets in the same VPC (for same-subnet and same-VPC-different-subnet scenarios) and at least one subnet in each VPC (for cross-VPC scenarios).

#### Scenario: Subnets exist after apply
- **WHEN** `terraform apply` completes successfully
- **THEN** multiple `aws_subnet` resources exist, each with a `vpc_id` referencing one of the provisioned VPCs

### Requirement: A route table with routes is provisioned
The sample app SHALL provision at least one `aws_route_table` with explicit `aws_route` entries: a route to an `aws_internet_gateway` (for internet-bound reachability) and a route to an `aws_vpc_peering_connection` (for cross-VPC reachability), each associated with the relevant subnet(s) via `aws_route_table_association`.

#### Scenario: Route table and routes exist after apply
- **WHEN** `terraform apply` completes successfully
- **THEN** an `aws_route_table` exists with a route targeting an `aws_internet_gateway` and a route targeting an `aws_vpc_peering_connection`, each associated with a subnet

### Requirement: An internet gateway is provisioned
The sample app SHALL provision an `aws_internet_gateway` attached to one of the VPCs, so a subnet's route table can route `0.0.0.0/0` traffic to it for internet-bound reachability scenarios.

#### Scenario: Internet gateway exists after apply
- **WHEN** `terraform apply` completes successfully
- **THEN** an `aws_internet_gateway` exists, attached to one of the provisioned VPCs

### Requirement: A VPC peering connection is provisioned
The sample app SHALL provision an `aws_vpc_peering_connection` between the two VPCs, so cross-VPC reachability has a real peering path to traverse.

#### Scenario: Peering connection exists after apply
- **WHEN** `terraform apply` completes successfully
- **THEN** an `aws_vpc_peering_connection` exists referencing both provisioned VPCs

### Requirement: Workloads are placed across the network graph
The sample app SHALL provision `aws_instance` resources representing workloads, each placed in a specific subnet and associated with one or more of the sample app's security groups, spanning same-subnet, same-VPC-different-subnet, and cross-VPC placements so all reachability scenarios have concrete endpoints.

#### Scenario: Workloads exist after apply
- **WHEN** `terraform apply` completes successfully
- **THEN** multiple `aws_instance` resources exist, each with a `subnet_id` referencing a provisioned subnet and at least one associated security group, placed so that at least one pair shares a subnet and at least one pair spans different VPCs

### Requirement: Existing security groups are reused within the new topology
The sample app's existing `ssh_open` and `https_only` security groups SHALL be placed within one of the newly provisioned VPCs (via `vpc_id`) and reused by the workload fixtures, rather than remaining VPC-less or gaining new VPC-less duplicates.

#### Scenario: Existing security groups belong to a provisioned VPC
- **WHEN** `terraform apply` completes successfully
- **THEN** the `ssh_open` and `https_only` security groups each have a `vpc_id` referencing one of the provisioned VPCs, and at least one workload instance uses each
