## Why

Today infra changes are verified only by diffing `terraform plan` output — nothing in the repo can answer "can service A actually reach service B?" from the deployed network graph itself. Issue #18 asks for that as a real, queryable invariant: a recursive Datalog traversal over VPCs, subnets, route tables, and security groups, backed by sample-app fixtures rich enough to exercise same-subnet, cross-VPC, and internet-bound reachability (plus self-reachability), each with a paired negative case.

## What Changes

- Add sample Terraform fixtures: two VPCs, multiple subnets, a route table with routes (local, peering, IGW), an `aws_internet_gateway`, an `aws_vpc_peering_connection`, and `aws_instance` workloads placed across subnets/VPCs/security groups to serve as reachability endpoints.
- Move the existing `ssh_open` and `https_only` security groups (`terraform/security_groups.tf`) into one of the new VPCs and reuse them in the reachability fixtures.
- Extend `resource-schema` (data-only, per ADR-0003) with three new modeled attributes needed for the traversal to be real Datalog joins rather than app-level graph walks:
  - `aws_security_group_rule.type` (ingress/egress) → `:aws-security-group-rule/type`
  - `aws_security_group_rule.source_security_group_id` → `:aws-security-group-rule/source-security-group-id`
  - `aws_security_group.vpc_id` → `:aws-security-group/vpc-id`
- Add a single public `reachable?` query function backed by one recursive Datalog rule set (e.g. `reaches`) covering: same-subnet, local-route-within-VPC, peering-route, internet-gateway-route, and self (identity) — each network path gated by the forward-direction SG check (source egress + target ingress) only, since AWS security groups are stateful.
- `reachable?`'s target argument accepts either a resource identifier or a CIDR/IP string (e.g. `"0.0.0.0/0"`), so "the public internet" needs no special sentinel — it's just the default route, matching AWS's own model.
- Add paired positive/negative tests for all four scenarios (same-subnet, cross-VPC, internet-bound, self), including two distinct cross-VPC negative fixtures: no peering connection at all, and a peering connection that exists but is missing its route on one side.
- Introduce "Workload" as the domain term for an `aws_instance` placed in the network graph (not "Service", which collides with the existing alias for the State Backend itself).

## Capabilities

### New Capabilities
- `network-topology`: Terraform fixtures establishing a multi-VPC, multi-subnet network graph (VPCs, subnets, route tables, internet gateway, VPC peering connection) with workload instances placed across it, reusing the existing security groups, so the query layer has real infrastructure to traverse.

### Modified Capabilities
- `resource-query`: adds a network reachability requirement — a `reachable?` function answering same-subnet, cross-VPC (via peering), internet-bound (via IGW/default route), and self reachability as a single recursive Datalog rule set, gated by forward-direction security group rules.

## Impact

- `state-backend/src/infratomic/state_backend/db.clj` — `resource-schema` gains three new modeled attributes (SG rule `type`, SG rule `source_security_group_id`, SG `vpc_id`).
- `state-backend/src/infratomic/state_backend/query.clj` — new `reachable?` function and its backing Datalog rules (`reaches` et al.).
- `state-backend/test/infratomic/state_backend/query_test.clj` — new reachability test cases (four positive + five negative: same-subnet, cross-VPC ×2 negatives, internet ×1 negative, self has none, plus each positive's paired negative).
- `terraform/security_groups.tf` — existing SGs gain `vpc_id`, moved into new VPC fixtures.
- `terraform/` — new files for VPCs, subnets, route tables, IGW, peering connection, and workload (`aws_instance`) resources.
- No changes to `docker-compose.yml` (`ec2` service already enabled) or IAM/NACL modeling (explicitly out of scope).
