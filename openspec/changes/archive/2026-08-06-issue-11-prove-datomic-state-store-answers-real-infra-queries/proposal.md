## Why

The State Backend currently stores each resource's attributes as one opaque JSON-encoded string per resource (`:resource/attributes`), so nothing beyond `:resource/type`/`:resource/id` can be queried via real Datalog `:where` clauses — every other question requires pulling every resource and filtering in application code. This makes it impossible to prove the core premise of storing Terraform state in Datomic: that it can answer real infrastructure questions (e.g. "which security groups expose port 22 to the internet?") as structural, indexed queries rather than JSON-blob scans.

## What Changes

- **BREAKING**: Decompose `:resource/attributes` from a single opaque JSON string into real Datomic datoms, replacing the storage model set out in ADR-0002:
  - A data-driven schema map (plain EDN data, not `defmulti`/`defmethod`) declares, per resource type, which attributes are modeled with typed, structural Datomic attributes (e.g. `aws_security_group_rule`'s `from_port`/`to_port` as `:db.type/long`).
  - Any attribute not covered by the schema map — including attributes of entirely unmodeled/custom resource types — falls back to generic key/value sub-entities attached to the resource: real, exact-match-searchable datoms, untyped/string-valued.
  - Nested/compound unmodeled values (lists/maps) are flattened into multiple dotted/indexed generic key/value pairs (e.g. `environment.variables.FOO` -> `"bar"`) rather than stored as a single JSON blob per top-level key.
  - Any single value (modeled or generic) that would exceed dev-local's 4096-byte-per-string limit falls back to opaque storage for just that value, rather than failing the transaction.
- Write a new ADR superseding ADR-0002 (matching the existing ADR-0002-supersedes-ADR-0001 convention), documenting the decomposition decision and its rationale.
- Add a `aws_security_group` + `aws_security_group_rule` (separate resources, not inline ingress/egress blocks) to the sample Terraform app with port 22 open to `0.0.0.0/0`, and at least one other security group with no rule opening port 22 to the internet.
- Enable the `ec2` LocalStack service (`docker-compose.yml`) and the AWS provider's `ec2` endpoint override (`terraform/provider.tf`) so the sample app's security group resources can be provisioned.
- Add a query namespace to `state-backend` with 4 functions: all deployed resources, resources by type, resources by attribute value (searching both generic and modeled/typed attributes), and security groups with port 22 open to the internet.
- Add an integration test that applies the sample Terraform app against already-running LocalStack + state-backend services, runs all 4 query functions, and asserts on their results, with careful setup/teardown (apply/destroy) so it doesn't pollute shared local dev state.
- Reconcile the `state-backend` test-running command: either add a working `clojure -M:test` alias or correct verification instructions to match the actual `-X:test`-style alias, whichever is idiomatic for this Clojure ecosystem.

## Capabilities

### New Capabilities
- `resource-query`: Query functions over the State Backend's Datomic database — all resources, resources by type, resources by attribute value (unified generic + modeled search), and security groups with port 22 open to the internet.
- `security-groups`: The sample Terraform app's `aws_security_group`/`aws_security_group_rule` resources, provisioned to exercise the query namespace (one insecure, port-22-open-to-the-world group; at least one that isn't).

### Modified Capabilities
- `state-backend`: `:resource/attributes` is no longer stored as a single opaque JSON string. Resource attributes are decomposed into Datomic datoms per a data-driven schema map (typed/structural for modeled resource types and attributes, generic flattened key/value sub-entities as the escape hatch for everything else), with an oversized-value fallback to opaque storage.
- `local-aws-environment`: The LocalStack `SERVICES` list and the Terraform AWS provider's `endpoints {}` block gain `ec2`, so `aws_security_group`/`aws_security_group_rule` resources can be provisioned against the local simulator.

## Impact

- `state-backend/src/infratomic/state_backend/db.clj` — schema definition, attribute decomposition/reconstruction, new query namespace.
- `state-backend/src/infratomic/state_backend/handler.clj` — `resource->tx` (write path) and `resource-entry`/`reconstruct-state` (read path) both currently assume `:resource/attributes` is one opaque string; both need to work against decomposed datoms instead.
- `state-backend/test/infratomic/state_backend/` — new integration test namespace; `test_runner.clj`'s hardcoded namespace list.
- `state-backend/deps.edn` — possible `:test` alias change.
- `terraform/` — new `security_groups.tf` (or similar), `provider.tf`, sample app resource count.
- `docker-compose.yml` — `SERVICES=` list.
- `docs/adr/` — new ADR superseding ADR-0002.
- `openspec/specs/state-backend/spec.md`, `openspec/specs/local-aws-environment/spec.md` — requirement deltas.
