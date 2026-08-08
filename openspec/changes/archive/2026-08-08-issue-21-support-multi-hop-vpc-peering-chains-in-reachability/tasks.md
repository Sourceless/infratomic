## 1. Empirical spike: bounded recursive Datomic rule

- [x] 1.1 In a scratch/REPL context (or a throwaway test), verify `datomic.client.api` (dev-local) accepts a genuinely self-referential rule with an arithmetic guard-and-decrement (`[(> ?hops 0)]` / `[(- ?hops 1) ?hops-1]`) — e.g. a minimal `vpc-chain-reaches` over a 3-VPC in-memory fixture. Confirm it terminates correctly at `max-hops = 0` (no match unless same VPC) and at a real 2-hop chain.
- [x] 1.2 Delete/discard the scratch spike once confirmed; it exists only to de-risk the design before the real implementation.

## 2. Refactor shared permission sub-rules

- [x] 2.1 In `state-backend/src/infratomic/state_backend/query.clj`, extract `forward-permits`, `sg-rule-permits`, `rule-matches-peer`, and `egress-permits-cidr` out of `reaches-rules` into a new private var (e.g. `permission-rules`).
- [x] 2.2 Recompose `reaches-rules` from its existing network-path clauses plus `permission-rules`, and confirm its resulting rule content — and `reachable?`'s behavior — is unchanged (run the existing `reachable?-*` tests in `query_test.clj`; all must still pass unmodified).

## 3. Implement `reachable-within-hops?`

- [x] 3.1 Define `chain-connects` — one disjunctive clause per hop type (today: peering only), joining a route table by `aws-route-table/vpc-id` to an `aws_route` with `vpc-peering-connection-id` set, resolved to the peering connection's other VPC (two symmetric clauses for `vpc-id`/`peer-vpc-id`), per design.md.
- [x] 3.2 Define `vpc-chain-reaches` — the base case (same VPC, any `?hops`) and the recursive case (`[(> ?hops 0)]`, `chain-connects`, decrement, recurse), per design.md.
- [x] 3.3 Define `chain-reaches` — self clause, resource-target clause (resolve `?src`/`?dst` to VPCs via subnet, delegate to `vpc-chain-reaches`, gate on `forward-permits`), and CIDR-target clause (resolve `?src`'s VPC, delegate to `vpc-chain-reaches` to find an IGW-having VPC within budget, gate on `egress-permits-cidr`), reusing `permission-rules` from task 2.
- [x] 3.4 Compose `chain-rules` = the task 3.1–3.3 clauses + `permission-rules`, and implement `reachable-within-hops? [db src dst max-hops]` using the same boolean-via-`(boolean (seq (d/q ...)))` idiom as `reachable?`, with a docstring covering the hop definition and the free-IGW-step rule.

## 4. Test fixtures and cases

- [x] 4.1 Add `chain-network-resources` to `query_test.clj`: 4 VPCs (A-B-C-D) each with one subnet and one `vpc_id`-owned route table, three peering connections (A↔B, B↔C, C↔D, no shortcuts), matching routes on both sides of each peering connection, an IGW + default route on one VPC (for the CIDR-target scenario), and workload instances at each VPC using a fresh permissive security group (not reused from `network-resources`).
- [x] 4.2 Add a broken-link variant of the fixture (omit the B→C route or the B-C peering connection) as a separate `def` or fixture-building function, not a mutation of the main fixture.
- [x] 4.3 Add `reachable-within-hops?-full-chain` test: full A-B-C-D fixture, `max-hops` at least 3, expect truthy.
- [x] 4.4 Add `reachable-within-hops?-broken-link` test: broken-link fixture, generous `max-hops`, expect falsy.
- [x] 4.5 Add `reachable-within-hops?-hop-limit-too-low` test: full unbroken fixture, `max-hops` below the required 3, expect falsy.
- [x] 4.6 Add `reachable-within-hops?-self` test: same instance as `src`/`dst`, `max-hops = 0`, expect truthy.
- [x] 4.7 Run `clj -X:test` from `state-backend/` and confirm `0 failures, 0 errors`, including all pre-existing tests (`reachable?-*` and everything else) alongside the new ones.

## 5. Docs

- [x] 5.1 Add **Hop** and **Peering Chain** glossary entries to `CONTEXT.md` (done during propose stage, per the alignment decision — verify still present and accurate before archiving).
