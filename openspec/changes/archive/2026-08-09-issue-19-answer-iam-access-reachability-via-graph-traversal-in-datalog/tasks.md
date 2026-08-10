## 1. Spike: confirm query-time `d/with` fact derivation + recursive rule joins work together

- [x] 1.1 Write a throwaway/minimal test that parses one hand-built policy JSON string, asserts derived statement facts into a scratch db via `d/with`, and confirms a simple Datalog rule can join a persisted resource entity against a freshly-derived (never-transacted) statement entity in the same query
- [x] 1.2 If dev-local rejects or mishandles combining persisted and `d/with`-only facts in one rule query, stop and flag it before proceeding — the design's approach depends on this working

## 2. Schema: model ARN attributes only (no policy-content schema changes)

- [x] 2.1 Add `aws_iam_role.arn` → `:aws-iam-role/arn` (plus `id`/`name` if not already sufficiently modeled) to `resource-schema` in `state-backend/src/infratomic/state_backend/db.clj`
- [x] 2.2 Add `aws_iam_policy.arn` → `:aws-iam-policy/arn` to `resource-schema`
- [x] 2.3 Add `aws_s3_bucket.arn` → `:aws-s3-bucket/arn` to `resource-schema` (if not already modeled)
- [x] 2.4 Add `aws_iam_role_policy_attachment.role` and `.policy_arn` to `resource-schema` (join edge from a role to its attached managed policy)
- [x] 2.5 Declare the scratch-only `:iam-statement/*` schema idents (`effect`, `action` many-cardinality, `resource` many-cardinality, `principal` many-cardinality, `source` ref, `kind`) in `db.clj`'s fixed `schema`, transacted at `ensure-db!` time — documented as write-once-by-`d/with`-only, never `d/transact`
- [x] 2.6 Run `state-backend`'s existing test suite to confirm the schema additions don't regress current behavior (`clj -X:test` from `state-backend/`)

## 3. Terraform fixtures: IAM policy graph

- [x] 3.1 Add an `aws_iam_role_policy` (inline identity-based policy) on an existing or new role granting a specific action (e.g. `s3:GetObject`) on a specific resource ARN — the direct-identity-policy fixture
- [x] 3.2 Add an `aws_s3_bucket_policy` on a bucket granting a role principal a specific action — the resource-based-policy fixture
- [x] 3.3 Add a second `aws_iam_role` (the "source" role) with an `aws_iam_role_policy` granting it `sts:AssumeRole` on a target role's ARN, and give the target role a trust policy (`assume_role_policy`) granting `sts:AssumeRole` to the source role's ARN as principal — the role-assumption-chain fixture; give the target role its own grant on a resource so the chain has something to reach
- [x] 3.4 Add an `aws_iam_policy` (managed policy) plus an `aws_iam_role_policy_attachment` attaching it to a role — the managed-policy fixture
- [x] 3.5 Add a fixture pairing an explicit `Deny` statement (in an identity-based or resource-based policy) alongside an `Allow` for the same action/resource, on a role/resource pair distinct from the other fixtures — the deny-overrides-allow fixture, plus a variant where the `Deny` is for a different action/resource (to exercise "non-matching deny doesn't block")
- [x] 3.6 `terraform apply` against LocalStack and confirm `terraform state list` shows all new resources with no errors (LocalStack Community won't enforce any of this — only state-consistency matters, per the issue)

## 4. `iam` namespace: policy parsing and fact derivation

- [x] 4.1 Create `state-backend/src/infratomic/state_backend/iam.clj`
- [x] 4.2 Implement policy JSON parsing: normalize a parsed IAM policy document into a seq of statement maps (`effect`, `action` as a set, `resource` as a set, `principal` as a set of ARNs where present), handling both bare-string and array forms of `Action`/`Resource`
- [x] 4.3 Implement resolution of which resources carry which kind of policy-bearing attribute (`assume_role_policy` → kind `:trust`, `aws_iam_role_policy.policy` → kind `:identity`, `aws_s3_bucket_policy.policy` → kind `:resource`, `aws_iam_policy.policy` reached via `aws_iam_role_policy_attachment` → kind `:identity`), reading the raw JSON string back via the existing generic-attribute reconstruction path
- [x] 4.4 Implement derived-fact tx-map assembly: one scratch entity per statement, with `:iam-statement/source` ref'd back to the owning resource entity (or, for a managed policy, the attached role) and `:iam-statement/kind` set appropriately
- [x] 4.5 Implement the glob-to-regex predicate function (`*` → `.*`, `?` → `.`, literal chars escaped) and unit-test it directly (not just via the rule) against wildcard and literal cases

## 5. `iam` namespace: `grants` recursive rule and `iam-reachable?`

- [x] 5.1 Implement the identity-side-allow and resource-side-allow rule clauses (glob-matched action/resource/principal via `[(iam/glob-matches? ...)]` predicate clauses)
- [x] 5.2 Implement the scoped deny-override negation (`not-join`), covering both identity-side and resource-side statements matched to the same action/resource
- [x] 5.3 Implement the role-assumption-edge clause: `grants ?principal "sts:AssumeRole" ?target-role` requiring both the target role's trust-policy grant and the source's own identity-based `sts:AssumeRole` grant, gated by the same deny-override logic
- [x] 5.4 Implement the recursive resource-access-via-assumed-role clause chaining `grants ?principal "sts:AssumeRole" ?mid` with `grants ?mid ?action ?resource`
- [x] 5.5 Implement the public `iam-reachable? db principal resource action` function: derive facts, `d/with`, run `grants`, return a boolean

## 6. Tests

- [x] 6.1 Direct identity-based policy: positive test in `state-backend/test/infratomic/state_backend/iam_test.clj`
- [x] 6.2 Resource-based policy: positive test
- [x] 6.3 Role-assumption chain: positive test covering at least two assumption hops
- [x] 6.4 Role-assumption chain: negative test where one edge is missing either the trust-policy or identity-policy side
- [x] 6.5 No granting policy anywhere: negative test
- [x] 6.6 Deny overrides allow: positive test (matching deny blocks) and negative test (non-matching deny elsewhere does not block)
- [x] 6.7 Glob matching: a test exercising a wildcard `Action`/`Resource` pattern actually matching a concrete action/resource, distinct from the fixture-level tests above
- [x] 6.8 Run `clj -X:test` from `state-backend/` and confirm `0 failures, 0 errors`

## 7. ADR and domain glossary

- [x] 7.1 Write `docs/adr/0005-derive-iam-policy-facts-at-query-time-via-speculative-db.md` documenting the query-time `d/with` fact-derivation technique, the glob-matching predicate, and the trust-policy-as-resource-based-policy unification
- [x] 7.2 Add `CONTEXT.md` glossary terms: Principal, Policy Statement, Trust Policy, IAM-reachable
