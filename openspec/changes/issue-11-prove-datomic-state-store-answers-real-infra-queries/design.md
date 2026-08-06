## Context

`state-backend/src/infratomic/state_backend/db.clj` and `handler.clj` currently treat `:resource/attributes` as one opaque JSON string per resource (ADR-0002), because Datomic dev-local hard-enforces a 4096-byte limit per `:db.type/string` datom and the sample app's real state document is well over that. This change decomposes attributes into real datoms while staying inside that same byte limit per individual datom. See `proposal.md` - Why, and `openspec/specs/state-backend/spec.md` (delta in this change) for the resulting behavior contract.

## Goals / Non-Goals

**Goals:**
- Make `:resource/type`-equality-and-beyond Datalog queries possible: exact-match on any attribute (modeled or not), range queries on modeled numeric attributes, and a join from `aws_security_group_rule` back to its owning `aws_security_group`.
- Keep `GET /state` reconstruction lossless enough that `terraform plan` still reports no drift after a restart, matching ADR-0002's already-validated round-trip property.
- Keep the schema extensible by data (new entries in a map), not by adding new `defmethod`s or touching decomposition/reconstruction code, per the alignment decision.

**Non-Goals:**
- Modeling every AWS resource type's every attribute structurally. Only what's needed for this issue's query functions (`aws_security_group`, `aws_security_group_rule`) gets schema-map entries; everything else rides the generic escape hatch.
- General schema migration tooling. There's no production data; existing `.datomic/` contents are dev-only and incompatible with the new schema (see Migration Plan).
- An HTTP/API surface for queries (explicitly out of scope per the issue).

## Decisions

### Schema map shape
A single Clojure map, `resource-schema`, keyed by Terraform resource type string, valued by a map of Terraform attribute key -> `{:ident <keyword> :value-type <db.type> :cardinality <db.cardinality, default :one>}`. Example:

```clojure
{"aws_security_group"
 {"id" {:ident :aws-security-group/id :value-type :db.type/string}}

 "aws_security_group_rule"
 {"from_port"          {:ident :aws-security-group-rule/from-port :value-type :db.type/long}
  "to_port"             {:ident :aws-security-group-rule/to-port :value-type :db.type/long}
  "protocol"            {:ident :aws-security-group-rule/protocol :value-type :db.type/string}
  "security_group_id"   {:ident :aws-security-group-rule/security-group-id :value-type :db.type/string}
  "cidr_blocks"          {:ident :aws-security-group-rule/cidr-block :value-type :db.type/string :cardinality :db.cardinality/many}}}
```

This map is the single source of truth for both (a) generating the extra `:db/ident` schema entries to transact alongside the existing fixed schema, and (b) deciding, per attribute, whether `resource->tx` emits a typed datom or falls through to the generic path. Chosen over `defmulti`/`defmethod` dispatch per the alignment decision: adding a new modeled resource type/attribute is a data-only change (add a map entry), not a new code branch, and the whole modeled surface is inspectable as data (e.g. for generating the transacted schema).

Both `aws_security_group`'s AWS-assigned `id` and `aws_security_group_rule`'s `security_group_id` are modeled (not left generic) specifically so the port-22 query's join from rule back to owning security group is a real Datalog value-equality join on typed attributes, not a scan through generic key/value entities.

### Generic key/value escape hatch
A component, cardinality-many ref attribute `:resource/attribute` points from each resource entity at generic key/value sub-entities: `:resource.attribute/key` (`:db.type/string`) and `:resource.attribute/value` (`:db.type/string`). `:db/isComponent true` so retracting a resource entity retracts its generic attributes too (mirroring how `stale-resource-retractions` already fully retracts resources). Every attribute not covered by `resource-schema` for its resource's type - including every attribute of a type with no `resource-schema` entry at all - becomes one `:resource.attribute/key`+`:resource.attribute/value` pair. This is real, indexed, exact-match-searchable Datomic data, not a JSON string.

### Flattening
A small recursive walk (`flatten-attribute-value`) turns a nested unmodeled value into a seq of `[dotted-or-indexed-key string-value]` pairs before generic-attribute transaction: maps recurse with `.`-joined keys, vectors recurse with `.`-joined numeric indices, scalars stringify (numbers/booleans via `str`, `nil` skipped - Terraform's attribute maps use `nil` to mean "no value", and Datomic has no null datom to transact). This keeps the generic-attribute schema to exactly two idents regardless of nesting depth, and keeps every leaf independently matchable by the attribute-value query.

### Oversized-value fallback
Before transacting any single attribute value (modeled or generic), check its transacted string representation's byte length. If it's within 4096 bytes, transact normally. If not, transact it instead as an opaque JSON string on a dedicated `:resource.attribute/overflow` (or reuse of `:resource.attribute/value` with a sentinel + separate `:resource.attribute/value-json` for the rare oversized case) - the exact shape is an implementation-stage decision, not fixed here, but the contract (spec'd) is: the transaction never fails because of one oversized value, and every other attribute on the same resource is stored via its normal path. Oversized values are not required to be exact-match Datalog-queryable (this is the accepted trade-off already named in the alignment decision and mirrors ADR-0002's own "not guaranteed identical for arbitrarily complex configurations" caveat) - reconstruction on `GET` still recovers the value for round-trip purposes, but it need not participate in the by-attribute-value query in the same way a normal generic/modeled datom does.

### ADR
A new ADR, `docs/adr/0003-decompose-resource-attributes-into-datoms.md`, supersedes ADR-0002 (adding the "Superseded by" banner to ADR-0002 itself, matching how ADR-0001 was superseded). It records: why opaque-blob storage blocked real querying, the hybrid schema-map + generic-escape-hatch design, the flattening and oversized-value trade-offs, and that this was validated against the sample app's real security-group resources specifically (not just asserted).

### Query namespace
A new `infratomic.state-backend.query` namespace, sibling to `db`/`handler`, holding the 4 functions from the proposal. It depends on `db` (for `client`/schema helpers) but not on `handler`. Each function takes a `db` value (a `datomic.client.api` db, consistent with existing `db.clj` helpers like `all-resources`) so it composes with both a live connection's `(d/db conn)` and, in tests, an isolated in-memory db - no HTTP layer involved, matching the issue's "functions only, called from tests" scope.

### Test architecture
A new integration-test namespace, `infratomic.state-backend.query-integration-test`, is added to `test_runner.clj`'s namespace list but is **not** wired into the existing `clojure -X:test` alias's default run - it shells out to real `terraform`/`docker` and depends on LocalStack + a running state-backend dev server, unlike every existing test (`handler_test.clj`) which is fully hermetic and in-memory. A new `deps.edn` alias (`:integration-test`, `:exec-fn`-style like the existing `:test` alias - matching this repo's established idiom rather than introducing a `-M`-style alias for consistency) runs just this namespace. The issue's "How to verify" text (`clojure -M:test`) will be corrected in the implement stage to reference the actual alias, per the alignment decision to let implementation pick whichever is idiomatic.

Setup/teardown: the test runs against `terraform/` (the sample app itself - there's no separate fixture app), assuming LocalStack and the state-backend dev server are already running (per the alignment decision, matching this repo's existing manual-bring-up test pattern, not an ephemeral self-contained spin-up). To avoid polluting shared local dev state:
- **Setup**: before applying, the test first tears down any pre-existing state (`terraform destroy` if a prior manual `terraform apply` left resources/state behind), so it starts from a known-empty baseline.
- **Body**: `terraform apply -auto-approve`, then run all 4 query functions against the state-backend's live db and assert.
- **Teardown**: `terraform destroy -auto-approve` in a `finally`/`try`-`finally` block, so a test failure mid-assertion still cleans up LocalStack resources and state-backend entities rather than leaving them for the next run (or a developer's next manual session) to trip over.

### Attribute decomposition site
`resource->tx` (in `handler.clj`) currently builds one flat tx-map per resource including `:resource/attributes (json/generate-string attributes)`. It now instead expands into a tx-map (typed keys directly from `resource-schema`, plus a `:resource/attribute` vector of tempid'd generic sub-entity maps built by `db`'s flattening/schema-lookup helpers) - the decomposition logic itself lives in `db.clj` (schema-map lookup is naturally colocated with the schema), invoked from `handler.clj`'s `resource->tx`. Symmetrically, `resource-entry`/`reconstruct-state` in `handler.clj` call a `db.clj` reconstruction helper that walks a pulled resource's modeled + generic + flattened attributes back into a plain attribute map for JSON encoding.

## Risks / Trade-offs

- **[Risk]** Oversized values lose exact-match queryability by design (opaque fallback) → **Mitigation**: this is scoped to values that were already unqueryable as an opaque blob before this change; nothing regresses, and it's explicitly named in the spec and alignment decision rather than silently accepted.
- **[Risk]** Flattening scalar-vs-string ambiguity: a modeled numeric attribute stored generically elsewhere as a stringified number could subtly mismatch an attribute-value query's input type → **Mitigation**: the by-attribute-value query normalizes/coerces its input against both the modeled (typed) and generic (string) representations, per the "unified search" requirement; covered by a spec scenario.
- **[Risk]** The integration test's `terraform destroy` teardown running in `finally` could itself fail (e.g. LocalStack down mid-test), leaving orphaned resources → **Mitigation**: accepted as a known limitation consistent with "assume already-running services"; not solvable without ephemeral spin-up, which the alignment decision explicitly rejected.

## Migration Plan

There is no production data - `.datomic/` is local dev-only storage (per `db.clj`'s `storage-dir` and the repo's local-first scope). The new schema is additive at the `:db/ident` level (new idents alongside existing ones) but changes what `resource->tx` writes and what reconstruction expects, so existing dev-local resource entities written under the old opaque-`:resource/attributes` shape won't reconstruct correctly under the new code. Developers pick up this change by deleting their local `.datomic/` directory (or `DELETE /state` then re-`apply`) and starting fresh - documented in the implement stage's task list, not requiring a data migration script.
