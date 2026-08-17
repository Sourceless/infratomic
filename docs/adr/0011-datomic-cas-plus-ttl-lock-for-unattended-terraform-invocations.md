# Guard unattended Terraform invocations with a Datomic CAS-plus-TTL per-resource-address lock, not an in-process mutex or whole-state lock

Issue #33: `apply!`/`import!`/`destroy!` (`infratomic.state-backend.terraform`)
run the real `terraform` binary unattended, on the State Backend's behalf,
against a caller-supplied working directory. Two concurrent invocations
against the *same* resource address (e.g. two overlapping reconciliation
attempts once #34 exists) must not both run `terraform` at once - a real
`terraform` process holds no cooperative lock of its own here (the
project's `backend "http"` config deliberately omits `lock_address`/
`unlock_address`, a separate, already-known gap unrelated to this one -
see `terraform/provider.tf`'s comment). Something on the State Backend
side has to serialize them itself.

## Decision: a Datomic-backed Lock entity per resource address, CAS-acquired, TTL-stale-reclaimable

A `:lock/resource-address` (`:db.unique/identity`) + `:lock/acquired-at`
entity pair, one per address that's ever been locked. Acquisition
(`try-acquire-lock!`) is a two-transaction sequence, not one:

1. `ensure-lock-entity!` - an idempotent map-form upsert of
   `{:lock/resource-address address}` alone, never touching
   `:lock/acquired-at`. Safe under real concurrency: two callers racing
   to create the same identity value upsert to the same entity (Datomic
   serializes the two transactions; the second is a no-op re-assertion),
   so this step alone can never grant or deny the lock - it only
   guarantees a real (non-tempid) entity exists for the next step to CAS
   against.
2. Read the entity's current `:lock/acquired-at` (`nil` if free). If it's
   present and not older than a fixed TTL (10 minutes,
   `terraform/lock-ttl-ms`), fail immediately - a live lock is held.
   Otherwise, attempt `[:db/cas [:lock/resource-address address]
   :lock/acquired-at <the value just read> <now>]` in its own
   transaction.

Two callers racing from the same free-or-stale read both attempt step 2's
CAS with the same old-value; Datomic serializes the two transactions, the
first to commit changes the live value out from under the second, so the
second's CAS old-value comparison fails and that transaction is rejected
outright - exactly one caller acquires, with no window in which both
observe "free" and both proceed. `apply!`/`import!`/`destroy!` don't call
`try-acquire-lock!` directly; they call `acquire-lock!`, which retries it
on a short interval until it succeeds, giving the terraform-execution
spec's "the second invocation does not begin running terraform ... until
the first has released its lock" its actual blocking behavior. Release
(`release-lock!`) retracts the Lock entity entirely; a subsequent acquire
recreates it via `ensure-lock-entity!`.

Staleness (`lock-ttl-ms`, 10 minutes) means a lock whose holder crashed
mid-invocation - never released, would otherwise block that address
forever - stops blocking on its own once the threshold passes, no human
intervention or heartbeat-renewal machinery required. Chosen as a fixed,
generous constant rather than a heartbeat lease because a single
resource-address-targeted `apply`/`import`/`destroy` should always
complete well within 10 minutes.

## Why not a single same-transaction upsert+CAS

The obviously-simpler-looking alternative - one transaction, using a
tempid: `[:db/add "t" :lock/resource-address address] [:db/cas "t"
:lock/acquired-at nil now]`, relying on Datomic's unique-identity upsert
to resolve `"t"` to an already-existing entity when one exists - is
unsound. Confirmed empirically against `com.datomic/local` while
implementing this: Datomic's tempid-upsert resolution and the `:db/cas`
old-value comparison do not reliably order against each other within one
transaction, so the CAS can silently succeed (overwriting
`:lock/acquired-at` with a fresh timestamp) even when a live,
non-stale lock already exists for that address under exactly this
pattern - i.e. it fails to serialize concurrent acquirers at all, the one
property this mechanism exists to guarantee. `:db/cas` against a lookup
ref for an entity that's already resolved *before* the CAS runs (this
ADR's two-transaction shape) doesn't have this problem - confirmed by the
same experiment, and by `terraform_test.clj`'s
`concurrent-invocations-on-the-same-address-serialize` test racing two
real threads through `with-lock-and-invocation`.

## Considered and rejected: an in-process mutex

A Clojure `ref`/lock map keyed by address, held entirely in the State
Backend process's own memory. Rejected: doesn't survive a process
restart (a crashed-and-restarted process forgets every lock it held,
silently allowing a second invocation to start against a resource the
first was mid-invocation on right up until the crash), and doesn't extend
to the State Backend eventually running as more than one process - both
explicitly called out as constraints this mechanism needs to hold up
under (design.md's Non-Goals note the lock design must *support* HA
deployment even though this change doesn't stand one up).

## Considered and rejected: whole-state (single global) locking

One lock covering every invocation, not one per resource address.
Rejected: it would serialize every unattended Terraform invocation across
the entire deployment against every other one, even for two invocations
targeting completely disjoint resources - unnecessary contention with no
correctness benefit, since nothing about `apply!`/`import!`/`destroy!`'s
own correctness requires that. Per-address locking (this ADR) only
serializes two invocations that actually target the same address;
`terraform_test.clj`'s `concurrent-invocations-on-different-addresses-
proceed-independently` test asserts this directly.

## Trade-offs accepted

- `acquire-lock!`'s blocking retry loop polls on a short fixed interval
  rather than using a smarter wake-on-release mechanism (e.g. a Datomic
  query watch or condition variable) - accepted as the simplest thing
  that satisfies the spec's blocking requirement; the poll interval is
  short enough that added latency is negligible relative to a real
  `terraform` invocation's own runtime.
- A lock stolen from a stale (presumed-crashed) holder is invisible to
  that holder - if the original process wasn't actually dead, just slow
  or partitioned, and eventually finishes its own `terraform` invocation
  after its lock was stolen and reacquired by someone else, both
  invocations could have run concurrently against the same address for
  part of their overlap. Accepted per design.md's Risks: the TTL is
  chosen to be comfortably longer than any single-resource-targeted
  invocation should take, making this a deliberately unlikely edge case,
  not a scenario the mechanism is designed to make impossible.
