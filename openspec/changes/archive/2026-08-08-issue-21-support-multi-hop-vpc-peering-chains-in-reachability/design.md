## Context

See `proposal.md` for motivation. Key constraints from the existing codebase and the alignment decisions:

- `query.clj`'s `reaches-rules` (private var backing `reachable?`) is a single disjunctive, non-recursive rule set: one `reaches` clause-head per network path (self, same-subnet, local-route, peering, IGW), plus shared sub-rules `forward-permits`, `sg-rule-permits`, `rule-matches-peer`, `egress-permits-cidr`, and `peering-connects`. None of its clause bodies call `reaches` itself — this repo has never exercised genuine self-referential recursive Datalog rules or arithmetic-guarded recursion (`[(> ?hops 0)]` + a decrement) against `datomic.client.api` (dev-local) before.
- `resource-schema` (`db.clj`) already models every attribute the chain traversal needs: `aws-vpc-peering-connection/vpc-id` + `/peer-vpc-id`, `aws-route-table/vpc-id`, `aws-route/route-table-id` + `/vpc-peering-connection-id` + `/destination-cidr-block` + `/gateway-id`, `aws-internet-gateway/vpc-id`, `aws-instance/subnet-id`, `aws-subnet/vpc-id`. No schema changes are needed.
- Alignment decisions (issue #21 comments) are binding for the items they cover: one `aws_vpc_peering_connection` traversed = one hop; recursion must be a hard, provably-terminating counter (checked and decremented each step), not unbounded-then-limited; the new rule set reuses `forward-permits`/`sg-rule-permits`/`rule-matches-peer` but not `reaches`/`peering-connects`; only true-endpoint SGs are checked; function is `reachable-within-hops? [db src dst max-hops]`; `dst` accepts a CIDR with the IGW step free of hop budget; a new independent fixture set; `chain-reaches`/`chain-connects` naming for extensibility.

## Goals / Non-Goals

**Goals:**
- Implement `reachable-within-hops?` as one genuinely self-referential recursive Datomic rule set, with a hard hop-counter guard, so the recursion is provably bounded (not a fixpoint walk checked afterward).
- Reuse `forward-permits`/`sg-rule-permits`/`rule-matches-peer` at the code level (not by copy-paste), so `reachable?`'s SG-matching semantics and this function's stay identical by construction.
- Keep `reachable?` and `reaches-rules`'s observable behavior, signature, and cost profile completely unchanged.
- Structure `chain-connects` (the "one hop" disjunct) so a future hop type is an additional clause, not a rename or restructure of `chain-reaches`'s recursion.

**Non-Goals:**
- Modeling VPN gateways, transit gateways, or cross-account peering as new hop types (explicitly out of scope per the issue).
- Checking security groups at intermediate/transit VPCs in the chain (per alignment — transit VPCs are pure routing/topology, no anchor instance exists to check).
- Subnet-level route-table anchoring for intermediate hops (see Decisions below) — the chain traversal reasons at VPC granularity throughout, not per-subnet.

## Decisions

### Extract shared permission sub-rules into their own var, referenced by both rule sets
`reaches-rules` currently defines `forward-permits`, `sg-rule-permits`, `rule-matches-peer`, and `egress-permits-cidr` inline, alongside its network-path clauses. Per the alignment decision to reuse (not duplicate) these, `query.clj` extracts them into a new private var, e.g. `permission-rules`, and composes both `reaches-rules` (`= network-path clauses ++ permission-rules`) and the new `chain-rules` (`= chain clauses ++ permission-rules`) from it. `reachable?`'s public behavior, signature, and cost are unaffected — `reaches-rules`'s *resulting rule content* is byte-for-byte identical, only its internal composition changes to expose the shared clauses. `peering-connects` is deliberately NOT extracted/reused (alignment: the new rule set "does not touch, extend, or call into `reaches` itself," and `peering-connects`'s single-hop semantics don't generalize to the chain's per-hop join, which additionally needs to originate from a route table by `vpc_id` rather than a route-table-association).

**Alternative considered**: copy `forward-permits`/`sg-rule-permits`/`rule-matches-peer` verbatim into `chain-rules`. Rejected — duplicated rule clauses drift silently if `reachable?`'s SG-matching logic is ever changed, exactly what the alignment decision was written to avoid.

### `chain-reaches` recursion shape: VPC-to-VPC, hop-counted, hard-bounded
One new rule set, `chain-rules`, structured as:

1. **`chain-reaches ?src ?dst ?hops`, self clause**: `[(= ?src ?dst)]` — a resource always reaches itself, at any `max-hops` including `0`, matching `reachable?`'s own self clause and the issue's explicit self-reachability acceptance criterion.
2. **`chain-reaches ?src ?dst ?hops`, resource-target clause**: resolves `?src`/`?dst` (both `aws_instance`) to their owning VPCs via `subnet_id` → `aws_subnet.vpc_id`, delegates the VPC-level walk to `vpc-chain-reaches`, then gates on `forward-permits ?src ?dst` (the shared, reused sub-rule) — endpoints only, exactly as `reachable?` does today.
3. **`chain-reaches ?src "0.0.0.0/0" ?hops`, CIDR-target clause**: resolves `?src`'s VPC, delegates to `vpc-chain-reaches` to find *some* VPC reachable within the hop budget that itself has a route to an `aws_internet_gateway`, then gates on `egress-permits-cidr ?src "0.0.0.0/0"`. The IGW step itself is not a `chain-connects` hop and consumes no budget — `vpc-chain-reaches` only counts peering hops to *arrive* at the IGW-having VPC.
4. **`vpc-chain-reaches ?vpc ?vpc ?hops`, base case**: a VPC always "chain-reaches" itself, regardless of `?hops` (including negative/irrelevant values) — zero-hop termination for same-VPC source/target pairs.
5. **`vpc-chain-reaches ?src-vpc ?dst-vpc ?hops`, recursive case**: `[(> ?hops 0)]` guards against recursing past budget; `(chain-connects ?src-vpc ?mid-vpc)` finds one hop; `[(- ?hops 1) ?hops-1]` decrements; `(vpc-chain-reaches ?mid-vpc ?dst-vpc ?hops-1)` recurses. This is the genuinely self-referential, hard-bounded recursion the issue requires: the guard-then-decrement pair on every call is what makes the bound provable, as opposed to an unbounded transitive-closure rule with the limit applied to its result afterward.
6. **`chain-connects ?vpc-a ?vpc-b`, one disjunctive clause per hop type (only peering exists today)**: a route table belonging to `?vpc-a` (`aws-route-table/vpc-id`) has an `aws_route` whose `vpc_peering_connection_id` names a peering connection whose `vpc_id`/`peer_vpc_id` pair includes `?vpc-b` (two symmetric clauses, one per side, mirroring `peering-connects`'s own two clauses — but defined fresh inside `chain-connects`, not reused, per the decision above). A future hop type (VPN, transit gateway, cross-account) is one more `chain-connects` clause with its own join shape — `chain-reaches`/`vpc-chain-reaches` need no changes.

### Route-table join is VPC-level, not subnet-anchored, for every hop including the first
`reachable?`'s existing single peering clause anchors the route lookup to the *source instance's specific subnet* (via `aws_route_table_association`), because the isolated-route-table negative fixture (`rt-a-isolated`) depends on that granularity. `chain-connects` instead joins a route table to a VPC directly via `aws-route-table/vpc-id`, uniformly for every hop — including the first, from `?src`'s own VPC.

**Rationale**: intermediate hops (VPC B to VPC C, etc.) have no anchor instance/subnet at all — per alignment, transit VPCs are pure topology. Anchoring only the *first* hop to `?src`'s subnet while every later hop uses VPC-level route-table matching would make the traversal's semantics inconsistent hop-to-hop for no benefit the issue's acceptance criteria ask for (the issue's negative scenarios are "a link is missing" and "hops too low," not "the right route table specifically"). Using VPC-level matching uniformly keeps `chain-connects`'s definition identical regardless of hop position, which is simpler to implement, verify, and extend.

**Alternative considered**: anchor the first hop to `?src`'s subnet/route-table-association like `reachable?` does, and only later hops at VPC level. Rejected — inconsistent join semantics between hop 1 and hop 2+ within the same recursive rule adds real implementation complexity for a distinction the acceptance criteria never asks for; the "broken link" negative fixture is constructed instead by giving the affected VPC's route table no matching route to the next hop at all (see fixture note below), which exercises the same failure mode without needing subnet-level anchoring.

### Fixture: `chain-network-resources`, a 4-VPC A-B-C-D chain
New, independent from `network-resources` (per alignment). VPCs A-B-C-D, each with one subnet and one route table (`vpc_id`-owned), three peering connections (A↔B, B↔C, C↔D — deliberately no A↔C, A↔D, or B↔D shortcuts), and matching routes on each side of each peering connection. Workload instances at A and D (plus one at each intermediate VPC, for completeness/future use) reuse the existing `sg-open`-style permissive security group pattern from `network-resources` (a fresh, equivalent SG defined locally, not the same fixture data) so hop-chain tests aren't also implicitly testing SG logic.

- **Full-chain positive**: `reachable-within-hops? db "instance-a" "instance-d" 3` — exactly 3 hops available and allowed.
- **Broken-link negative**: a variant of the fixture where the B→C route (or the B-C peering connection itself) is omitted; `reachable-within-hops? db "instance-a" "instance-d" 5` (a generous hop budget) still returns `false`, since no path exists at all.
- **Hop-limit negative**: the full, unbroken fixture, but called with `reachable-within-hops? db "instance-a" "instance-d" 2` — a real 3-hop path exists but the budget is one hop short.

## Risks / Trade-offs

- **First recursive Datomic rule in this repo, now compounded with arithmetic guard clauses.** Mitigated the same way issue #18's design flagged: verify the simplest possible recursive rule (e.g. `vpc-chain-reaches` alone, over a 3-VPC in-memory fixture) in isolation before building out `chain-connects`/`chain-reaches` on top — first task in `tasks.md`.
- **VPC-level route-table join (see Decision above) is coarser than `reachable?`'s subnet-anchored join.** If a VPC has multiple route tables and only some have the chain-relevant route, `chain-connects` will find a path via any one of them, even one not actually associated with the querying subnet. Acceptable per the Decision's rationale (transit hops have no subnet anchor to begin with, and the acceptance criteria don't test this distinction) but worth flagging: this function answers "does *a* valid route exist within this VPC," not "does *this specific subnet's* route table have it," which is a deliberately different (coarser, chain-appropriate) question than `reachable?` answers for its one hop.
- **Recursive rule depth vs. `max-hops` growth.** Each recursive call is one more `vpc-chain-reaches` invocation; for the issue's 4-VPC/3-hop scope this is trivial, but a caller passing a very large `max-hops` against a large peering graph could still explore broadly (bounded by hop count, not by graph size within that count). Not a concern at the issue's scope; noted for future extension awareness only.
