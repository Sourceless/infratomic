## Why

`reachable?`/`reachable-within-hops?` (#18/#20/#21/#22) can answer "can traffic from A reach B?" over the network graph, but nothing in the repo can answer the IAM equivalent — "can principal A access resource B?" — from the deployed IAM policy graph itself. Issue #19 asks for that as a real, queryable invariant: a recursive Datalog traversal over identity-based policies, resource-based policies, and role-assumption chains, with explicit-deny-overrides-allow semantics, so IAM access can be verified rather than manually traced through policy JSON.

## What Changes

- Add Terraform IAM fixtures (via `data.aws_iam_policy_document`, matching `terraform/iam.tf`'s existing convention): an identity-based policy (`aws_iam_role_policy`) granting direct access, a resource-based policy (`aws_s3_bucket_policy`) granting access, a role-assumption chain (`aws_iam_role.assume_role_policy` trust policy plus the assuming role's own `sts:AssumeRole` identity policy), a managed policy (`aws_iam_policy` + `aws_iam_role_policy_attachment`), and a fixture pairing an explicit `Deny` alongside an `Allow` for the same action/resource.
- Add a new `infratomic.state-backend.iam` namespace implementing: policy-JSON parsing (`assume_role_policy`, `aws_iam_role_policy.policy`, `aws_s3_bucket_policy.policy`, `aws_iam_policy.policy` + its `aws_iam_role_policy_attachment`), an IAM-style glob-to-regex matcher for actions/resources/principals, transient query-time fact derivation into a never-committed `d/with` scratch db, a recursive `grants` Datomic rule set, and the public `iam-reachable? db principal resource action` entry point.
- The `grants` rule evaluates identity-side allow, resource-side allow, and a scoped deny-override (glob-matched to the same action/resource, checked on both the identity and resource side of every edge) — and is reused recursively for role-assumption edges (trust policy = a resource-based policy scoped to `sts:AssumeRole`, requiring both the target role's trust policy and the source role's own `sts:AssumeRole` identity grant), not a separate mechanism.
- No persisted schema changes: policy JSON attributes (`assume_role_policy`, `policy`, etc.) continue to be stored exactly as today, as opaque strings via the existing generic/oversized-value path — `resource-schema` gains no new entries for policy content. Only `arn` attributes (already computed/exported by Terraform) are read to resolve principal/resource identity.
- Add an ADR documenting: reusing Policy Check's `d/with`-speculative-db technique for query-time (not plan-time) fact derivation, the glob-matching predicate, and unifying trust-policy evaluation with resource-based-policy evaluation under one `grants` rule.
- Add paired positive/negative tests: direct-identity-policy, resource-policy, role-assumption-chain (multi-hop), no-access-anywhere, and deny-overrides-allow.
- Add new `CONTEXT.md` glossary terms: Principal, Policy Statement, Trust Policy, IAM-reachable — kept distinct from the existing Reachable/network-reachability vocabulary.

## Capabilities

### New Capabilities
- `iam-reachability`: Terraform IAM fixtures (identity-based policy, resource-based bucket policy, role-assumption chain, managed policy, deny-alongside-allow) plus an `iam-reachable?` query function answering IAM access reachability as query-time-derived recursive Datalog rules over the deployed IAM policy graph.

### Modified Capabilities
(none — network reachability's `resource-query` capability is unaffected; this is a new, separate query surface per the issue's explicit IAM-only scope)

## Impact

- `state-backend/src/infratomic/state_backend/iam.clj` (new) — policy JSON parsing, glob matching, scratch-db fact derivation, the `grants` recursive rule set, and `iam-reachable?`.
- `state-backend/test/infratomic/state_backend/iam_test.clj` (new) — direct-identity-policy, resource-policy, role-assumption-chain, no-access, and deny-overrides-allow test cases.
- `terraform/iam.tf` — new fixtures: an `aws_iam_role_policy` (identity-based grant), an `aws_s3_bucket_policy` (resource-based grant), a second role plus its trust policy and identity-based `sts:AssumeRole` grant (assumption chain), an `aws_iam_policy` + `aws_iam_role_policy_attachment` (managed policy), and a deny-alongside-allow fixture.
- `docs/adr/0005-*.md` (new) — query-time speculative-db fact derivation, glob matching, trust-policy-as-resource-based-policy unification.
- `CONTEXT.md` — new terms: Principal, Policy Statement, Trust Policy, IAM-reachable.
- No changes to `state-backend/src/infratomic/state_backend/db.clj` (`resource-schema`), `query.clj`, or `policy.clj` — this is deliberately a separate namespace and mechanism, per the alignment decision.
