## 1. LocalStack + sample app: security groups

- [x] 1.1 Add `ec2` to `docker-compose.yml`'s `SERVICES=` list
- [x] 1.2 Add an `ec2` endpoint override to `terraform/provider.tf`'s `endpoints {}` block
- [x] 1.3 Add `terraform/security_groups.tf` with an `aws_security_group` + `aws_security_group_rule` permitting ingress on port 22 from `0.0.0.0/0`
- [x] 1.4 Add at least one more `aws_security_group` (+ rule(s) as needed) in the same file with no rule opening port 22 to `0.0.0.0/0`
- [x] 1.5 Manually verify: `docker compose up -d`, `terraform apply` succeeds and provisions both security groups against LocalStack

## 2. ADR

- [x] 2.1 Write `docs/adr/0003-decompose-resource-attributes-into-datoms.md` superseding ADR-0002, per design.md's ADR decision
- [x] 2.2 Add a "Superseded by ADR-0003" banner to the top of `docs/adr/0002-reconstruct-state-instead-of-raw-storage.md`, matching how ADR-0001 was superseded

## 3. State Backend: schema map and decomposition

- [x] 3.1 Add `resource-schema` data map to `db.clj` (or a new `schema.clj`) covering `aws_security_group` (`id`) and `aws_security_group_rule` (`from_port`, `to_port`, `protocol`, `security_group_id`, `cidr_blocks`), per design.md's Schema map shape decision
- [x] 3.2 Generate the extra `:db/ident` schema entries from `resource-schema` and include them in the transacted `schema` alongside the existing fixed attributes
- [x] 3.3 Add the generic key/value schema (`:resource/attribute` component ref, `:resource.attribute/key`, `:resource.attribute/value`)
- [x] 3.4 Implement the oversized-value fallback storage path (schema + write-time check), scoped to a single attribute value at a time
- [x] 3.5 Implement `flatten-attribute-value` (nested map/vector -> dotted/indexed key + string-value pairs, `nil` leaves skipped)
- [x] 3.6 Implement the decomposition function: given a resource type and its raw attribute map, produce a tx-map fragment (typed keys for schema-mapped attributes, `:resource/attribute` entries for everything else via flattening, oversized fallback applied per-value)
- [x] 3.7 Update `handler.clj`'s `resource->tx` to use the decomposition function instead of `(json/generate-string attributes)` on `:resource/attributes`
- [x] 3.8 Implement the reconstruction function: given a pulled resource entity (modeled attrs + pulled `:resource/attribute` entries), rebuild the original nested attribute map (un-flattening dotted/indexed generic keys, reading modeled attrs by their schema-map keys, resolving oversized-fallback values)
- [x] 3.9 Update `handler.clj`'s `resource-entry`/`reconstruct-state` to use the reconstruction function instead of `(json/parse-string attributes)`
- [x] 3.10 Update/extend `handler_test.clj` as needed so existing hermetic tests pass against the new decomposition/reconstruction (round-trip, upsert-in-place, retraction scenarios)

## 4. State Backend: query namespace

- [x] 4.1 Create `state-backend/src/infratomic/state_backend/query.clj`
- [x] 4.2 Implement "all deployed resources" query
- [x] 4.3 Implement "resources by type" query
- [x] 4.4 Implement "resources by attribute value" query, searching both generic key/value and modeled/typed attributes (coercing/normalizing the query value against both representations per design.md's flattening risk note)
- [x] 4.5 Implement "security groups with port 22 open to the internet" query: Datalog match on `aws_security_group_rule`'s typed `from-port`/`to-port`/`cidr-block`, resolved back to the owning `aws_security_group` via `security-group-id` = `id` value equality
- [x] 4.6 Add unit tests for all 4 query functions against an in-memory db seeded via `db/client :mem` (mirroring `handler_test.clj`'s fixture pattern), covering match and no-match cases from the spec scenarios

## 5. Integration test

- [x] 5.1 Add `state-backend/test/infratomic/state_backend/query_integration_test.clj`: setup (destroy any pre-existing `terraform/` state), `terraform apply -auto-approve`, run all 4 query functions against the live state-backend db, assert per the issue's acceptance criteria, teardown (`terraform destroy -auto-approve`) in a `finally`
- [x] 5.2 Add the new namespace to `test_runner.clj`'s namespace list (or a parallel integration-test runner, per design.md)
- [x] 5.3 Add a `:integration-test` `:exec-fn`-style alias to `state-backend/deps.edn` that runs only the integration test namespace, kept separate from the default `:test` alias
- [x] 5.4 Update README/issue verify instructions to reference the correct alias (`clojure -X:integration-test` or equivalent) instead of the issue's `clojure -M:test`, per the alignment decision

## 6. Documentation and cleanup

- [x] 6.1 Update `db.clj`/`handler.clj` namespace docstrings that describe attributes as "one opaque JSON-encoded string" (now stale) to reflect decomposed storage
- [x] 6.2 Note in README (or a local dev note) that developers should delete `.datomic/` (or `DELETE /state`) before first running the app against this change, per design.md's Migration Plan
- [x] 6.3 Run `openspec validate issue-11-prove-datomic-state-store-answers-real-infra-queries --strict` and fix any issues
