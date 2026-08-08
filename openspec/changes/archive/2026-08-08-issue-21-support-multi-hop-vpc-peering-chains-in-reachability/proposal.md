## Why

`reachable?`'s peering path (issue #18) is a single fixed hop: it only sees a target reachable via peering if one `aws_vpc_peering_connection` connects the source's VPC directly to the target's. A transit chain (A peered to B, B peered to C, with no direct A-C shortcut) is silently reported unreachable today, even though traffic genuinely can flow along that path. Issue #21 asks for a second, explicitly bounded-recursive function that walks chained peering connections up to a caller-supplied hop limit, without touching `reachable?`'s existing single-hop behavior.

## What Changes

- Add `reachable-within-hops? [db src dst max-hops]`: a new public query function answering whether `src` can reach `dst` by traversing a chain of `aws_vpc_peering_connection` hops, each hop being one peering connection traversed (A-B-C-D = 3 hops), bounded by a hard, provably-terminating counter decremented each recursive step (not an unbounded traversal checked afterward, and not one fixed-depth clause per hop count).
- Implement the traversal as a new, wholly separate Datomic rule set — `chain-reaches`, self-referentially recursive, calling a `chain-connects` disjunct for the "next hop" step — that reuses `reachable?`'s existing shared sub-rules (`forward-permits`, `sg-rule-permits`, `rule-matches-peer`) for the endpoint security-group check, but does not call into or modify `reaches`/`peering-connects`. `reachable?`'s signature, behavior, and cost profile stay exactly as they are today.
- `chain-connects` is named and structured so a later hop type (VPN, transit gateway, cross-account peering) is an additional disjunctive clause, not a rename or restructure of the recursion — mirroring the `reaches`/`peering-connects` naming precedent already established for `reachable?`.
- Only the true endpoints' (`src`/`dst`) security groups gate the result (source egress + target ingress, same as `reachable?`'s single hop); intermediate transit VPCs in the chain are pure routing/topology and are not checked, since AWS SGs attach to instances and there's no natural instance to anchor a check to at a transit-only VPC.
- `dst` accepts a CIDR string (e.g. `"0.0.0.0/0"`) as well as an instance identifier, for signature symmetry with `reachable?`. When `dst` is a CIDR, the final internet-gateway egress step is free — it does not consume `max-hops` budget, which counts peering hops only.
- Add a new, independent Terraform-shaped test fixture set (e.g. `chain-network-resources` in `query_test.clj`) modeling a 4-VPC A-B-C-D peering chain with no direct shortcuts, plus its broken-link and hop-limit-too-low variants — kept separate from the existing `network-resources` fixture so `vpc-c`'s role as `reachable?`'s own deliberately-unpeered negative case is untouched.
- Add paired positive/negative tests: full 4-hop chain success (A to D via B and C), a broken-link negative (a missing route/peering connection partway through the chain), and a hop-limit negative (a real longer path exists, but `max-hops` is set too low to reach it).
- Add **Hop** and **Peering Chain** to `CONTEXT.md`'s glossary, per the alignment decision: Hop is one `aws_vpc_peering_connection` traversed during a Peering Chain traversal; Peering Chain is a path of zero or more Hops connecting two VPCs, distinct from `reachable?`'s single fixed peering hop.

## Capabilities

### New Capabilities
(none — this extends the existing `resource-query` capability's reachability requirements; no new capability area is introduced)

### Modified Capabilities
- `resource-query`: adds a new requirement for `reachable-within-hops?`, bounded multi-hop peering-chain reachability, alongside the existing single-hop `reachable?` requirement (which is unchanged).

## Impact

- `state-backend/src/infratomic/state_backend/query.clj` — new `reachable-within-hops?` function and its backing `chain-reaches`/`chain-connects` Datomic rules; `reachable?` and `reaches-rules` untouched.
- `state-backend/test/infratomic/state_backend/query_test.clj` — new `chain-network-resources` fixture (4-VPC A-B-C-D chain, no direct shortcuts) and three new test cases (full-chain success, broken-link negative, hop-limit negative); existing `network-resources` fixture and its tests untouched.
- `CONTEXT.md` — two new glossary entries, Hop and Peering Chain.
- No changes to `db.clj`'s `resource-schema` (all resource types/attributes the traversal needs — `aws_vpc_peering_connection.vpc_id`/`peer_vpc_id`, `aws_route`, `aws_route_table_association`, `aws_instance`, security group rules — are already modeled per #18).
- No changes to `terraform/` application code (fixtures live only in the test namespace, matching the existing `network-resources` pattern which is also test-only, not `terraform/`-sourced).
