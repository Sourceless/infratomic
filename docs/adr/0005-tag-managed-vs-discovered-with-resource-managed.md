# Tag managed vs. Discovered resources with `:resource/managed?`

Issue #26 introduces Discovered Resources — entities ingested from LocalStack's
EC2 API rather than a Terraform `apply`. Two existing behaviors assumed every
`:resource/*` entity in the db was Terraform-managed: `stale-resource-retractions`
retracts any resource not present in the just-`POST`ed managed state (which
would delete every Discovered Resource on the very next real `apply`), and
`GET /state` reconstruction pulls every resource entity unfiltered into the
Terraform state document (which would tell Terraform it owns resources it
never created).

We add a boolean `:resource/managed?` attribute to every Resource entity —
`true` for Terraform-managed, `false` for Discovered — set on every write.
`resource->tx` (the `POST /state` decomposition path, already shared with
`policy.clj`'s speculative-plan evaluation) sets it `true`; the sync path sets
it `false`. `stale-resource-retractions` and `GET /state` reconstruction both
filter on it, excluding Discovered Resources from the stale-sweep and from
what Terraform is told it owns.

Considered and rejected: deriving "managed" implicitly (e.g. presence/absence
of a Terraform address shape in `:resource/id`) rather than an explicit
attribute. Rejected as too fragile — it would couple retraction/reconstruction
correctness to the *shape* of an id string rather than to the actual write
source, and the Rule added by issue #27 needs an explicit, queryable source
signal anyway.

Trade-offs accepted:

- Touches the hot `POST /state` path (`resource->tx`) — every Terraform apply
  now sets one more attribute per resource, on every write, forever.
- Requires a one-time backfill: every Resource entity already in `.datomic/`
  predates this attribute and has no `:resource/managed?` value. Since sync
  (issue #26) doesn't exist until this change ships, every pre-existing
  resource is by definition Terraform-managed — the backfill sets
  `:resource/managed?` `true` on all of them.
