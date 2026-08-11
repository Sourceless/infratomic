# Flag out-of-band drift via a `:resource/last-write-source` tag plus Datomic history, not a shadow copy

Issue #27: once Sync (#26) can ingest resources Terraform never created, the
next gap is a resource Terraform *does* manage being changed directly against
the environment afterward - that out-of-band change silently persists until
the next `terraform plan`/`apply` happens to notice it (or never gets
noticed, if the plan doesn't touch that attribute). We needed a way to (a)
know a given write to a Resource entity came from Terraform or from Sync, and
(b) recover what Terraform itself last asserted for a resource, even after a
later Sync write has overwritten the live value.

We add `:resource/last-write-source` (`:terraform`/`:sync`), a plain
cardinality-one entity attribute set on every Resource-entity write by both
write paths (`handler.clj`'s `resource->tx` sets `:terraform`; `sync.clj`'s
`resource-tx` sets `:sync` on every branch that writes) - the same shape and
"set on every write" discipline `:resource/managed?` already established
(ADR-0005). A new drift Rule (`query.clj`, deliberately *not* registered in
`policy.clj`'s Rule registry, so it never runs as part of the pre-apply
Policy Check) finds every managed resource whose current write source is
`:sync`, uses `d/history` scoped to that one attribute to find the most
recent transaction where it held `:terraform`, reads the resource's
attributes as of that transaction via `d/as-of` (reusing
`db/reconstruct-attributes`, the same round-trip `GET /state` already uses),
and diffs that against the resource's current live attributes.

Considered and rejected: a separate `[:db/add "datomic.tx" :tx/source ...]`
transaction-metadata attribute, queried via a history db keyed by
transaction, instead of a plain entity attribute. Rejected as unneeded extra
granularity - the acceptance criteria only need "what was the source of the
most recent write to this resource," which a plain entity attribute plus
`d/history` on that one attribute already answers, without introducing a
wholly new querying idiom (tx-metadata) into a codebase that had never used
one.

Considered and rejected: maintaining a separate shadow copy of "Terraform's
last-known values" written alongside `:resource/last-write-source`, avoiding
`d/history` entirely. Rejected - it would duplicate the attribute-
decomposition machinery (typed + generic + overflow storage) a second time
for shadow values, whereas Datomic's transaction log already retains this for
free (no excision is used anywhere in this codebase, so an ordinary
retraction never physically deletes a prior datom); the history-based read is
more code in the query path but zero extra code in the write path.

Sync's matching/ingestion decision (`resource-tx`) changes accordingly: a
match that's Terraform-managed no longer means "skip, leave untouched." It
now means "reconstruct the resource's currently stored attributes
(`db/reconstruct-attributes`) and diff them against the freshly observed live
attributes; write (tagging `:sync`) only if they differ." This reverses
issue #26's original "Terraform-managed resource is left untouched" behavior
and the corresponding `sync_test.clj` assertion - confirmed intentional in
alignment, since leaving a drifted Terraform-managed resource's stored
attributes stale is exactly the gap this issue closes.

Both the diff-gated update and the drift Rule's comparison are scoped to
`type`'s modeled keys (`db/comparable-attributes`, restricting to
`resource-schema`'s keys and dropping `nil`-valued ones), not a full
stored-vs-observed attribute-map equality. This was discovered necessary
during implementation, against the real sample app: a Terraform-managed
resource's state, as originally posted, carries many attributes beyond what
`resource-schema` models for that type (tags, descriptions, ARNs, etc.,
stored generically - see `db.clj`'s `decompose-attributes`), none of which
any `sync.clj` `*->attrs` translation function ever produces. Comparing the
*full* reconstructed attribute set against Sync's necessarily-narrower
observed map made every Terraform-managed resource with any such unmodeled
attribute look permanently "drifted," even with zero real change - Sync was
never capable of observing those attributes in the first place, so they were
never eligible to drift as far as Sync is concerned. Scoping the comparison
to modeled keys only fixes this without touching the write path's existing
behavior of retracting-then-reasserting a resource's decomposed attributes
on any upsert (`db/resource-upsert-retractions`, reused unchanged, matching
the existing Discovered-Resource-upsert branch per design.md).

Trade-offs accepted:

- Touches the hot `POST /state` path a second time (after `:resource/managed?`
  in #26) - every Terraform apply now sets one more attribute per resource,
  on every write, forever.
- Requires a one-time backfill (`db/backfill-last-write-source!`, mirroring
  `backfill-managed-flag!` exactly): every Resource entity already in
  `.datomic/` predates this attribute. Every such pre-existing entity is
  backfilled to `:terraform` - by definition correct for anything written
  before Sync (#26) existed, and an accepted simplification (per design.md's
  Migration Plan) for anything written since, since there is no way to
  recover a pre-existing entity's true original write source after the fact.
- `d/history`/`d/as-of` are new query idioms for this codebase (previously
  zero usage) - scoped narrowly (one attribute, one entity per call) to keep
  the query shape simple and directly testable.
- Sync's per-resource cost grows for an already-managed match: a full
  reconstruct-and-compare on every match, not just an immediate skip -
  accepted, since Sync is an explicitly-triggered operation, not
  automatic/timer-driven (unchanged from #26's `resource-sync` spec).
