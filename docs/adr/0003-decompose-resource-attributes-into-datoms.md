# Decompose resource attributes into real Datomic datoms

Supersedes [ADR-0002](0002-reconstruct-state-instead-of-raw-storage.md).

ADR-0002 stopped storing raw state JSON but still stored each resource's
*attributes* as a single opaque JSON-encoded string on `:resource/attributes`.
That kept `POST`/`GET` working within the 4096-byte-per-string limit, but it
meant nothing beyond `:resource/type`/`:resource/id` could be queried via real
Datalog `:where` clauses — answering a question like "which security groups
expose port 22 to the internet?" required pulling every resource and parsing
its JSON blob in application code. That defeats the core premise of storing
Terraform state in Datomic in the first place: that infrastructure questions
should be answerable as structural, indexed queries, not JSON-blob scans.

We now decompose each resource's attribute map into real datoms at `POST`
time, via a single data-driven schema map (`resource-schema` in `db.clj`):

- **Modeled attributes.** For a resource type with an entry in
  `resource-schema` (currently `aws_security_group` and
  `aws_security_group_rule`, the minimum needed to answer this issue's query),
  each declared attribute is stored as its own typed, structural Datomic
  attribute (e.g. `aws_security_group_rule`'s `from_port`/`to_port` as
  `:db.type/long`), enabling exact-match and range Datalog queries without
  parsing anything.
- **Generic key/value escape hatch.** Every other attribute — including every
  attribute of a resource type with no `resource-schema` entry at all — is
  stored as a `:resource/attribute` component sub-entity
  (`:resource.attribute/key`/`:resource.attribute/value`, both strings). This
  is real, indexed, exact-match-searchable Datomic data, not a JSON string.
  Nested unmodeled values (maps/lists) are flattened into multiple
  dotted/indexed key-value pairs (e.g. `environment.variables.FOO` ->
  `"bar"`) rather than stored as one JSON blob per top-level key, so every
  leaf value is independently searchable.
- **Oversized-value fallback.** Any single value (modeled or generic) whose
  transacted string representation would exceed the 4096-byte limit falls
  back to opaque JSON storage for just that one value, rather than failing
  the whole transaction. Every other attribute on the same resource is
  unaffected. This trades away exact-match queryability for that one
  oversized value — no regression, since it was equally unqueryable as part
  of ADR-0002's opaque blob.

Chosen over `defmulti`/`defmethod` dispatch: `resource-schema` is a plain
Clojure data map, so adding a new modeled resource type or attribute is a
data-only change (one map entry), not a new code branch, and the whole
modeled surface is inspectable as data — including for generating the
`:db/ident` schema entries that get transacted alongside the fixed schema.

This was validated against the sample app's real security-group resources,
not just asserted: `aws_security_group`'s AWS-assigned `id` and
`aws_security_group_rule`'s `security_group_id` are both modeled specifically
so the "security groups with port 22 open to the internet" query's join from
rule back to owning security group is a real Datalog value-equality join on
typed attributes — resolving `security_group_id` = `id` — not a scan through
generic key/value entities. `GET /state` reconstruction was re-verified
end-to-end against the expanded sample app (now including the two security
groups) after this change: `terraform plan` still reports no drift after a
service restart, preserving ADR-0002's round-trip property.

Trade-offs accepted, both already named in ADR-0002 and not made worse by
this change: oversized values remain opaque and non-queryable (scoped to
values that were already opaque before), and reconstruction is not
guaranteed byte-identical for arbitrarily complex configurations — only
verified against the sample app's actual resource shapes.

There is no production data to migrate: `.datomic/` is local dev-only
storage, and its existing contents (written under the old
opaque-`:resource/attributes` shape) won't reconstruct correctly against
this change's code. Developers pick this change up by deleting their local
`.datomic/` directory (or `DELETE /state` then re-`apply`) and starting
fresh.
