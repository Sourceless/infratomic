# Derive IAM policy-statement facts at query time via a speculative `d/with` db, never persisted

`iam-reachable?` needs to answer "can this IAM principal perform this action
on this resource?" as genuine recursive Datalog traversal (per issue #19's
explicit architectural acceptance criterion) over identity-based policies,
resource-based policies, and role-assumption chains — not as an
application-level walk of parsed policy JSON in Clojure. But policy JSON
(`aws_iam_role.assume_role_policy`, `aws_iam_role_policy.policy`,
`aws_s3_bucket_policy.policy`, `aws_iam_policy.policy`) is stored today, and
stays stored, as an opaque string via `db.clj`'s generic `:resource/attribute`
key/value path (ADR-0003) — there are no `Effect`/`Action`/`Resource`/
`Principal` datoms for a Datalog rule to join against.

We considered two ways to get real datoms to traverse:

1. **Persist decomposed policy statements** as ordinary schema-mapped datoms,
   extending `resource-schema` the same way #18/#20/#21 extended it for every
   other join edge in this codebase. Rejected: policy JSON's statement
   structure is qualitatively different from every other modeled attribute —
   parsing embedded JSON at write time, not just flattening Terraform-native
   maps/vectors — and persisting data that's trivially re-derivable from data
   already stored adds write-path complexity and staleness risk for no
   read-path benefit once query-time derivation works.
2. **Derive policy-statement facts at query time**, into a scratch db that's
   never committed, then run a real recursive Datalog rule against it.
   Chosen.

`policy.clj`'s `evaluate` already established this repo's other `d/with`-
speculative-db technique: build tx-data, `(d/with (d/with-db conn)
{:tx-data ...})`, evaluate query functions against `:db-after`, never
`d/transact`. That technique speculatively transacts a *plan's* not-yet-real
resources so Policy Check's existing Rules can be reused unmodified against
them. `iam-reachable?` reuses the same *primitive* for a different purpose:
deriving read-only fact datoms (parsed policy statements) from data that's
already real and persisted, so a genuine recursive Datalog rule set (`grants`,
in the new `infratomic.state-backend.iam` namespace) has real datoms to
traverse. The two never share code — one derives facts from a plan for
Policy Check, the other derives facts from already-applied state for a read
query — but confirmed (via a throwaway spike, tasks.md 1.1) that Datomic
dev-local's client API accepts `d/with` directly on a plain `(d/db conn)`
value (not only one obtained via `d/with-db`), and that a rule query can join
a persisted entity against a freshly-`d/with`-derived, never-transacted
entity in the same query — the mechanism this design depends on.

`iam-reachable?`'s scratch schema (`:iam-statement/effect`/`action`/
`resource`/`principal`/`source`/`kind`) is declared as ordinary `db.clj`-style
schema entries, transacted alongside the fixed schema at `ensure-db!` time
(so `d/with` has somewhere to assert them) — but documented as write-once-by-
`d/with`-only: no `:iam-statement/*` datom is ever written by `d/transact`,
and no `:iam-statement/*` entity is ever visible outside the single
`iam-reachable?` call that derived it. This is a real, if unusual, use of a
persisted schema declaration purely to support ephemeral, per-call facts —
accepted because Datomic requires an attribute's schema to exist before any
db value (including a `d/with` scratch one) can assert it, and duplicating
that schema into some parallel non-persisted mechanism would be more, not
less, complexity for no benefit.

Two further decisions fall out of this, both implemented in the `grants`
rule set:

- **Glob matching as a Datomic predicate clause, not value equality.** IAM's
  `*`/`?` wildcard semantics (e.g. `Action: "s3:Get*"`) are implemented as a
  pure `glob-matches?` predicate (glob compiled to an anchored regex),
  invoked from `grants` as `[(infratomic.state-backend.iam/glob-matches?
  ?pattern ?value)]` — fully qualified so it resolves regardless of which
  namespace's `d/q` call evaluates the rule (confirmed via a second spike:
  Datomic resolves an unqualified predicate symbol like `<=`/`not=` against
  `clojure.core`, but a project-defined predicate function needs its full
  namespace to resolve reliably).
- **Trust-policy evaluation unified with resource-based-policy evaluation
  under one `grants` rule**, not a separate `assumes?` rule plus
  application-level hop-by-hop chaining. A trust policy is modeled as an
  ordinary resource-based statement (kind `:trust`, sourced from the assumed
  role's own entity), evaluated by the same allow/deny-override machinery as
  every other resource-based statement. The role-assumption edge itself
  (`grants ?principal "sts:AssumeRole" ?target-role`) is its own `grants`
  clause requiring *both* the target's trust-policy grant and the source's
  own identity-based `sts:AssumeRole` grant — unlike ordinary direct access,
  where either an identity-side or resource-side Allow alone suffices — since
  AWS role assumption genuinely requires both sides. A second, genuinely
  self-referential `grants` clause chains `grants ?principal "sts:AssumeRole"
  ?mid` with `grants ?mid ?action ?resource`, so a chain of more than one
  assumed role is followed without hand-unrolling hops, mirroring
  `query.clj`'s `chain-reaches`/`vpc-chain-reaches` shape (guard/recurse) for
  network reachability's own multi-hop case.

Accepted trade-off: query-time JSON parsing runs on every `iam-reachable?`
call, with no caching — every policy-bearing resource's JSON is re-parsed
each time. Acceptable for this issue's scope (correctness and architectural
honesty — real recursive Datalog, no persisted derived data — matter more
than query latency here); a caching layer is a natural, non-breaking future
addition if it's ever needed, not a concern for this change.
