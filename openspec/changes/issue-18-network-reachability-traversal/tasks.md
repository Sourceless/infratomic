## 1. Spike: confirm Datomic client recursive rules work in dev-local

- [x] 1.1 Write a throwaway/minimal recursive Datomic rule (`:in $ %`) against the existing dev-local test harness and confirm it returns the expected transitive-closure result
- [x] 1.2 If dev-local rejects or mishandles the rules syntax, stop and flag it before proceeding — the design's approach depends on this working

## 2. Schema: model the new resource types

- [x] 2.1 Add `aws_vpc` (`id`, `cidr_block`) to `resource-schema` in `state-backend/src/infratomic/state_backend/db.clj`
- [x] 2.2 Add `aws_subnet` (`id`, `vpc_id`, `cidr_block`) to `resource-schema`
- [x] 2.3 Add `aws_route_table` (`id`, `vpc_id`) to `resource-schema`
- [x] 2.4 Add `aws_route` (`id`, `route_table_id`, `destination_cidr_block`, `gateway_id`, `vpc_peering_connection_id`) to `resource-schema`
- [x] 2.5 Add `aws_route_table_association` (`id`, `subnet_id`, `route_table_id`) to `resource-schema`
- [x] 2.6 Add `aws_internet_gateway` (`id`, `vpc_id`) to `resource-schema`
- [x] 2.7 Add `aws_vpc_peering_connection` (`id`, `vpc_id`, `peer_vpc_id`) to `resource-schema`
- [x] 2.8 Add `aws_instance` (`id`, `subnet_id`, `vpc_security_group_ids` as many-cardinality) to `resource-schema`

## 3. Schema: extend existing modeled types

- [x] 3.1 Add `aws_security_group_rule.type` → `:aws-security-group-rule/type` to `resource-schema`
- [x] 3.2 Add `aws_security_group_rule.source_security_group_id` → `:aws-security-group-rule/source-security-group-id` to `resource-schema`
- [x] 3.3 Add `aws_security_group.vpc_id` → `:aws-security-group/vpc-id` to `resource-schema`
- [x] 3.4 Run `state-backend`'s existing test suite to confirm the schema additions don't regress current behavior (`clj -X:test` from `state-backend/`)

## 4. Terraform fixtures: network topology

- [x] 4.1 Add two `aws_vpc` resources
- [x] 4.2 Add `aws_subnet` resources: at least two subnets in one VPC (for same-subnet and same-VPC-different-subnet cases) and at least one subnet in the other VPC
- [x] 4.3 Add an `aws_internet_gateway` attached to the VPC that needs internet-bound reachability
- [x] 4.4 Add an `aws_vpc_peering_connection` between the two VPCs (plus the accepter/requester resources LocalStack needs, if any)
- [x] 4.5 Add `aws_route_table` resource(s) with `aws_route` entries: a local route is implicit (no resource needed), an explicit route to the internet gateway, and an explicit route to the peering connection
- [x] 4.6 Add `aws_route_table_association` resources wiring subnets to their route table(s)
- [x] 4.7 Move `ssh_open` and `https_only` (`terraform/security_groups.tf`) into one of the new VPCs via `vpc_id`
- [x] 4.8 Add `aws_instance` workloads placed across subnets/VPCs/security groups, covering: two in the same subnet, two in the same VPC but different subnets, and two in different VPCs — with SG rule pairs (egress+ingress, by CIDR or `source_security_group_id`) permitting traffic between the intended-reachable pairs
- [x] 4.9 Add the negative-fixture counterparts: an SG-blocked same-subnet pair, a cross-VPC pair with no peering connection at all, a cross-VPC pair where peering exists but the querying side's route is missing, and an internet-bound pair with no route to the internet gateway
- [x] 4.10 `terraform apply` against LocalStack and confirm `terraform state list` shows all new resources with no errors

## 5. Query: `reachable?` and its backing rules

- [x] 5.1 Implement the shared forward-direction SG-check sub-rule (source egress + target ingress, matching by CIDR or `source_security_group_id`) in `state-backend/src/infratomic/state_backend/query.clj`
- [x] 5.2 Implement the `reaches` recursive rule's self, same-subnet, and local-route-within-VPC clauses
- [x] 5.3 Implement the `reaches` rule's peering-route clause
- [x] 5.4 Implement the `reaches` rule's internet-gateway-route clause, handling a CIDR-string target (`"0.0.0.0/0"`) distinctly from a resource-identifier target
- [x] 5.5 Implement the public `reachable?` function wrapping the `reaches` rule set

## 6. Tests

- [x] 6.1 Same-subnet: positive (reachable) and negative (SG-blocked) test cases in `state-backend/test/infratomic/state_backend/query_test.clj`
- [x] 6.2 Cross-VPC: positive (peering + routes + SG all permit) test case
- [x] 6.3 Cross-VPC negative: no peering connection at all
- [x] 6.4 Cross-VPC negative: peering connection exists but the querying side's route is missing
- [x] 6.5 Internet-bound: positive (route to IGW + permitting egress) and negative (no route to IGW) test cases
- [x] 6.6 Self-reachability: a resource always reaches itself, regardless of SG/route configuration
- [x] 6.7 Run `clj -X:test` from `state-backend/` and confirm `0 failures, 0 errors`

## 7. Domain glossary

- [x] 7.1 Record "Workload" as the domain term for an `aws_instance` placed in the network graph, distinct from "the service" (existing alias for the State Backend), per alignment decision 10
