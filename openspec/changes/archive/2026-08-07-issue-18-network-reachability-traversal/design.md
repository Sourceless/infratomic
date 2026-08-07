## Context

See `proposal.md` for motivation. Key constraints from the existing codebase:

- `resource-schema` (`state-backend/src/infratomic/state_backend/db.clj:28-51`) is a single data-driven map; adding a resource type or attribute is meant to be a map entry, not new code (ADR-0003). Today it only covers `aws_security_group` and `aws_security_group_rule` — none of the new resource types this change introduces (`aws_vpc`, `aws_subnet`, `aws_route_table`, `aws_route`, `aws_route_table_association`, `aws_internet_gateway`, `aws_vpc_peering_connection`, `aws_instance`) are modeled at all yet.
- Both sides of any join need to be modeled as typed attributes for a real Datalog join (ADR-0003's stated rationale for modeling `security_group_id` = `id`). Every edge this change's traversal needs — subnet→vpc, route-table→vpc, route→gateway/peering, route-table-association→subnet/route-table, instance→subnet, instance→security-groups, sg→vpc, sg-rule→sg — must be modeled the same way.
- No query in the repo currently uses Datomic rules (`:in $ %`) or recursion (`query.clj:93-113`'s `security-groups-with-port-22-open` is the closest prior art: a real join, but non-recursive). `datomic.client.api` (dev-local) supports the same rules syntax as on-prem/cloud, but this repo has never exercised it.
- Alignment decisions (issue #18 comments) are binding for the items they cover: workload = `aws_instance`; existing SGs move into the new VPC topology; `aws_security_group_rule.type` and `.source_security_group_id` become modeled; `aws_security_group.vpc_id` becomes modeled; reachability is one `reachable?` function backed by one recursive rule set; the internet is represented as the CIDR string `"0.0.0.0/0"`, not a sentinel; cross-VPC gets two distinct negative fixtures; scope is strictly network-layer.

## Goals / Non-Goals

**Goals:**
- Model every new resource type and join edge the traversal needs, following the existing `resource-schema` pattern exactly (mechanical extension, not new code paths).
- Implement `reachable?` as genuine recursive Datalog (rules), matching the issue's explicit architectural acceptance criterion.
- Keep the four scenarios' Terraform fixtures minimal but realistic (LocalStack Community EC2 is metadata-only, so `aws_instance` fixtures only need to be state-consistent, not bootable).

**Non-Goals:**
- IAM/policy reachability, NACLs — out of scope per the issue and alignment decision 9.
- Multi-hop transit gateways, NAT gateways, VPN, or any routing construct beyond local/peering/IGW routes — not requested by the acceptance criteria.
- General-purpose graph visualization or explanation of *why* a path is blocked — `reachable?` returns a boolean-ish result, not a path trace.

## Decisions

### New resource types get full `resource-schema` entries, mechanically
The three attribute additions named in the alignment decisions (SG rule `type`, SG rule `source_security_group_id`, SG `vpc_id`) extend *existing* modeled types. The wholly new resource types this change introduces have no schema entry at all yet, so each needs a complete entry following the established pattern (an `id` join key plus whichever attributes participate in traversal joins):

| Resource type | Modeled attributes | Purpose |
|---|---|---|
| `aws_vpc` | `id`, `cidr_block` | join target for subnet/route-table/sg/igw/peering `vpc_id`; CIDR for peering-route destination matching |
| `aws_subnet` | `id`, `vpc_id`, `cidr_block` | join target for instance `subnet_id`; owning-VPC join |
| `aws_route_table` | `id`, `vpc_id` | join target for route/association `route_table_id` |
| `aws_route` | `id`, `route_table_id`, `destination_cidr_block`, `gateway_id`, `vpc_peering_connection_id` | the route edges themselves (IGW and peering paths) |
| `aws_route_table_association` | `id`, `subnet_id`, `route_table_id` | join edge from a subnet to its route table |
| `aws_internet_gateway` | `id`, `vpc_id` | join target for `aws_route.gateway_id` |
| `aws_vpc_peering_connection` | `id`, `vpc_id`, `peer_vpc_id` | join target for `aws_route.vpc_peering_connection_id`; both sides of the peering |
| `aws_instance` | `id`, `subnet_id`, `vpc_security_group_ids` (many) | reachability endpoints; join to subnet and to their security groups |

Routes are modeled as a separate `aws_route` resource (not inline blocks on `aws_route_table`), matching the existing convention established for security group rules (`security-groups` capability: "Ingress/egress rules are separate resources").

**Alternative considered**: inline `route {}` blocks on `aws_route_table`, mirroring older Terraform AWS provider style. Rejected — the sample app's own established convention (and current provider guidance) is separate resources, and it keeps each route independently joinable/attributable like SG rules already are.

### `reachable?` recursive rule shape
One Datomic rules vector, `reaches`, with disjunctive clauses (each clause a valid "or" branch of the recursive rule) roughly:

1. **Self**: `[(reaches ?src ?src)]` — identity, no traversal.
2. **Same subnet**: both instances share `subnet_id`, gated by forward SG check.
3. **Local route within VPC**: both instances' subnets belong to the same `vpc_id` (the "local" route AWS adds implicitly, never an explicit `aws_route`), gated by forward SG check.
4. **Peering route**: source's subnet's route table has an `aws_route` with `vpc_peering_connection_id` set, whose peering connection's `vpc_id`/`peer_vpc_id` includes the target's VPC, gated by forward SG check.
5. **Internet-gateway route**: source's subnet's route table has an `aws_route` with `destination_cidr_block` = `"0.0.0.0/0"` and `gateway_id` referencing an `aws_internet_gateway`, and the target is the literal string `"0.0.0.0/0"` (not another instance), gated by the source's egress rules only (no target ingress to check — the target isn't a modeled resource).

The forward SG check (used by clauses 2–4) is a shared sub-rule: source has an `aws_security_group_rule` with `type = "egress"` permitting the traffic (by CIDR or `source_security_group_id`), and — for resource targets — the target has a matching `type = "ingress"` rule. Return-path rules are never checked (AWS security groups are stateful, per alignment decision 6).

`reachable?`'s target parameter is polymorphic: a resource identifier (matched against instance/subnet/vpc joins) or a CIDR string (matched only in clause 5, against `destination_cidr_block`). This keeps the public API a single function per alignment decision 7, without a sentinel keyword.

**Alternative considered**: four separate scenario-specific functions (`same-subnet-reachable?`, `cross-vpc-reachable?`, etc.). Rejected per alignment decision 6 — a single rule set is more architecturally honest to "graph traversal" and avoids drift between near-duplicate implementations.

### Datomic rules syntax — verify empirically early
This repo has never used `:in $ %` / recursive rules against `datomic.client.api` (dev-local). Before building out all five clauses, implement and test the simplest possible recursive rule (e.g. two-hop local-route traversal) in isolation to confirm dev-local's query engine accepts the same rules syntax as on-prem/cloud Datomic. If it doesn't, this design's rule-based approach needs revisiting before the rest of the fixtures are built — flagged as the first task.

## Risks / Trade-offs

- **Schema surface area growth**: eight new resource types added to `resource-schema` in one change is a lot of mechanical additions. Mitigated by the "one table entry per attribute, no new code" pattern already established — risk is fixture/test volume, not design complexity.
- **LocalStack Community `aws_instance` fidelity**: EC2 instance support is metadata-only in LocalStack Community. Mitigated by alignment decision 1 — this is explicitly acceptable since only Terraform state attributes matter for this query, not real boot/networking behavior.
- **Recursive rule correctness is easy to get subtly wrong** (e.g. accidentally allowing a return-path check that masks a real bug, or over-matching CIDR ranges). Mitigated by the paired positive/negative fixture requirement from the issue — every path has an explicit "should NOT be reachable" test, not just a happy path.
- **Peering route asymmetry**: a peering connection existing doesn't imply routes exist on both sides (this is a genuine, common AWS misconfiguration, per alignment decision 8). The design's clause 4 checks only the *source* side's route table, which is correct for one-directional `reachable?` — but the "peering exists, route missing on one side" negative fixture must be constructed so the missing route is on the querying side to actually exercise this failure path, not the far side (which forward-only traversal would never check anyway).
