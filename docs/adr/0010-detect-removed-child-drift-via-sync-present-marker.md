# Detect removed-child drift via an orthogonal `:resource/sync-present?` marker, never entity retraction

Issue #32: generalizing drift detection to FK-bearing child resources
(`aws_security_group_rule`, `aws_route`, `aws_route_table_association`,
`aws_iam_role_policy_attachment`) surfaces a symmetric case to "a new
out-of-band child appears": a previously Terraform-managed child
*disappears* out-of-band (deleted directly against the environment, not
through `terraform destroy`). Sync's fetch (`describe-all`) simply stops
seeing it; nothing in `sync.clj` today reacts to a resource's *absence*
from a live fetch at all - `resource-tx` only ever processes resources it
found. Left undetected, a managed resource's Datomic entity, and hence
`GET /state`'s report of it to Terraform, silently diverges from reality
forever, with no signal anywhere.

The hard constraint (per the issue's alignment): detecting this must never
change what `GET /state` reports. `managed-resources`/`GET /state`
reconstruction reads current datoms only (via `resource-pull-pattern`), so
any write that touches a modeled attribute, or a full `:db/retractEntity`
on the resource, is a real side effect - it would make Terraform's next
`plan` believe the resource needs recreating (or its attributes changed),
which is remediation-adjacent, not mere detection, and contradicts Drift's
existing "detection only" principle (`drift-detection/spec.md`,
CONTEXT.md's Drift entry).

We add `:resource/sync-present?` (`:db.type/boolean`,
`:db.cardinality/one`), a plain entity attribute deliberately absent from
`resource-pull-pattern` (so no `GET`/`GET /state` code path ever reads it),
set only by `sync!`'s new once-per-pass step (not `resource-tx`'s
per-resource decision): for each of the four covered child types, every
Terraform-managed entity whose AWS id Sync observed this run gets
`:resource/sync-present? true`; every Terraform-managed entity of that type
Sync previously knew about but did *not* observe this run gets
`:resource/sync-present? false`. A brand new removed-child query-time Rule
(`query.clj`, using the same generalized `child-parent-joins` join table as
the new-child Rule) finds every managed child with `:resource/sync-present?
false`, joined back to its parent exactly as the new-child Rule joins a
Discovered child - reusing `security-groups-with-port-22-open`'s join
precedent for both directions.

Because the child's own entity, `:resource/id`, `:resource/type`, and every
modeled/generic attribute datom are never touched by this mechanism, the
removed-child Rule recovers `{type, id}` for its response via an ordinary
live pull of the (fully intact, never-retracted) child entity - the same
`resource-summary-pattern` pull every other Rule in this codebase already
uses. `d/history`/`d/as-of` remain available against
`:resource/sync-present?` specifically, mirroring ADR-0009's
`last-terraform-write-tx` idiom, for anyone who needs to recover *when* a
child went missing; the Rule's current-value read for the minimal
`{type, id}` shape the response requires today just doesn't need that
lookup.

One existing branch needed a small, purely additive fix:
`resource-tx`'s existing-managed-match branch skips writing entirely
(`:skipped-already-managed`, no tx-data) when observed attributes exactly
equal stored attributes. If a child previously marked
`:resource/sync-present? false` reappears with unchanged attributes, that
branch must still write, at minimum reasserting `:resource/sync-present?
true`, or the marker would never clear once set. The branch now writes
whenever attributes differ *or* the match's current
`:resource/sync-present?` is `false` - strictly additive, every existing
`resource-tx`/`sync_integration_test.clj` scenario keeps its current
outcome.

Considered and rejected: retracting the child's own foreign-key attribute
(e.g. `:aws-security-group-rule/security-group-id`) as the removed signal,
recovering it via history the same way ADR-0009 recovers Terraform's
last-asserted attribute values. Rejected - the FK is a modeled attribute
inside `resource-pull-pattern`; retracting it changes
`reconstruct-attributes`' output for that resource, i.e. changes what `GET
/state` reports (silently dropping an attribute Terraform itself asserted)
- exactly the live-state mutation the alignment's hard constraint
forbids.

Considered and rejected: a full `:db/retractEntity` on the child. Rejected
explicitly by the hard constraint itself -
`managed-resource-eids`/`GET /state` would stop reporting a resource
Terraform still believes it owns, making the next `plan` think it needs
recreating.

Considered and rejected: overloading `:resource/last-write-source` with a
third enum value (e.g. `:sync-missing`) instead of a new attribute. Would
technically work without conflict - existing consumers
(`sync-sourced-managed-resources`) match the literal `:sync` value, so a
`:sync-missing` value would never be accidentally picked up by the
attribute-drift Rule. Rejected for clarity: `:resource/last-write-source`
answers "who wrote this attribute set last," a different question from
"did Sync's most recent pass observe this resource at all." Conflating the
two into one attribute's value space makes both harder to reason about and
to extend independently later.

Considered and rejected: a dedicated `:resource/removed-at-tx` (ref-to-tx)
or a timestamp attribute alongside the boolean, instead of a plain boolean.
Rejected as unneeded extra structure, mirroring ADR-0009's own "considered
and rejected: tx-metadata" reasoning - `d/history` already recovers the
transaction of any datom change for free, so a dedicated tx-ref/timestamp
attribute would only duplicate what history already answers.

Trade-offs accepted:

- `:resource/sync-present?`'s absence is a valid, meaningful state ("no
  Sync run covering this type has happened yet since this resource was
  created or last matched") distinct from both `true` and `false` - unlike
  `:resource/managed?`/`:resource/last-write-source` (ADR-0005/0009), this
  needs no backfill migration for pre-existing data, since "not yet
  checked" is a correct description of every pre-existing resource's real
  state, not an ambiguity to paper over.
- `sync!` gains a once-per-pass step distinct from `resource-tx`'s
  per-resource decision (computing an observed-vs-stored id-set diff per
  covered type), a new shape of logic in `sync.clj` alongside the existing
  per-resource `resource-tx` loop - accepted, since the question this step
  answers ("what disappeared this run") is structurally a set-difference
  over a whole pass, not a fact about any single freshly-observed resource.
- A route table association's two foreign keys (`route_table_id`,
  `subnet_id`) mean a single removed association surfaces under two
  different parents' `removed_children` independently, evaluated by two
  separate `child-parent-joins` entries against the same underlying
  `:resource/sync-present? false` fact - accepted as correct semantics
  (the drift is genuinely relevant to both relationships), not a
  deduplication bug to fix.

## Update (issue #32 PR #36 round-2 review): the `aws_security_group_rule`/`aws_route` id-matching gap this ADR shipped with is now fixed

This ADR's "every Terraform-managed entity whose AWS id Sync observed this
run" wording glossed over a real gap for two of the four covered types:
`aws_security_group_rule`/`aws_route`'s modeled `"id"` is written from two
different id spaces depending on write path (Terraform's own
synthetic/opaque id via `POST /state`; AWS's real observed id via Sync),
so `existing-match`'s id-based lookup could never actually match a
Terraform-managed instance of either type to itself - `sync!`'s
observed-vs-stored diff step (this ADR's design) ended up marking every
Terraform-managed instance of both types `:resource/sync-present? false`
on every single pass, correctly removed or not, a permanent false-positive
source this mechanism shipped with rather than caused. A parallel gap
existed on the new-child side (`query.clj`'s `new-children-by-parent`),
worked around in a first pass by excluding both types from new-child
detection entirely.

Both gaps are now fixed at the root: `sync.clj`'s `existing-match`
composite-key-matches a Terraform-managed instance of either type
(`db/resource-composite-key`, ADR-0006's update) instead of relying on the
mismatched id, so `missing-child-tx`'s observed-vs-stored diff (and
`new-children-by-parent`, no longer needing any exclusion) both now see an
unmodified instance as present, as this ADR always intended. See
ADR-0006's update and `db/id-space-mismatched-types` for the fix and its
own follow-on findings (the composite key must never replace the stored
`"id"` value itself, and `comparable-attributes` needed a matching fix for
an `aws_route`-specific empty-string-vs-absent representation mismatch
this fix exposed, both discovered fixing this gap).

## Update (issue #32 PR #36 round-4 review): new-child drift now also self-clears, via the same marker

Round-3's fix made new-child/removed-child detection correct for an
*unmodified* resource. It missed a distinct case, caught in round-4
review and reproduced live against LocalStack: once a genuinely new
out-of-band child was discovered and flagged as new-child drift, removing
that child out-of-band and running Sync again never cleared the flag -
`new-children-by-parent` was a pure `:resource/managed? false` join with
no freshness dimension, and this ADR's presence marker was, as originally
scoped, written only for Terraform-managed matches (`resource-tx`'s
`:else` branch) - a Discovered child the new-child mechanism itself
creates never had its own presence tracked at all, so nothing could ever
tell `new-children-by-parent` it had genuinely disappeared.

Fixed by extending this ADR's own mechanism to the Discovered side, not by
adding a new one: `resource-tx`'s `nil? match` (fresh discovery) and
`false? managed?` (re-observed, already-Discovered) branches now also
assert `:resource/sync-present? true` for a `sync-present-types` type, on
every pass that (re-)observes the child - exactly the assertion the
Terraform-managed branch already made. `db.clj`'s `managed-resource-ids-by-type`
(renamed `resource-ids-by-type`) is no longer managed-only, so
`missing-child-tx`'s observed-vs-stored diff now also marks a
stored-but-unobserved Discovered entity of a covered type
`:resource/sync-present? false`. `new-children-by-parent` filters out any
child whose marker is `false`, so a new-child-flagged Discovered entity
that a later full Sync pass no longer observes drops out of the map -
self-clearing, symmetric to how the removed-child Rule already self-clears
on reappearance. Nothing about the hard constraint changes: a Discovered
child's own entity and attributes are still never retracted or altered by
this - `:resource/sync-present?` remains the only thing ever written, now
just written for both kinds of match, not one.
