## 1. `:resource/managed?` tagging and read-path filters

- [x] 1.1 Add `:resource/managed?` (`:db.type/boolean`, `:db.cardinality/one`) to `db.clj`'s `schema`.
- [x] 1.2 Update `handler.clj`'s `resource->tx` to set `:resource/managed? true` on every `POST /state` managed resource's tx-map.
- [x] 1.3 Update `db.clj`'s `all-resource-eids`/`all-resources` (or add a filtered variant used by `GET /state`) so `reconstruct-state` only includes `:resource/managed? true` entities.
- [x] 1.4 Update `handler.clj`'s `stale-resource-retractions` so its "existing" set (candidates for retraction) is limited to `:resource/managed? true` entities.
- [x] 1.5 Add a backfill step to `db.clj`'s `ensure-db!`: after transacting schema, find every resource entity with no `:resource/managed?` value and transact `:resource/managed? true` on it. Confirm it's a no-op on a second call.
- [x] 1.6 Add/update tests: a `POST /state` resource is tagged managed; a manually-transacted `:resource/managed? false` entity is excluded from `GET /state`; such an entity survives a `POST /state` that doesn't mention it; the backfill tags pre-existing untagged entities and is idempotent.

## 2. `aws_security_group_rule` id and AWS-shape translation

- [x] 2.1 Add a modeled `"id"` entry to `resource-schema`'s `"aws_security_group_rule"` map (`:aws-security-group-rule/id`, `:db.type/string`).
- [x] 2.2 Add `com.cognitect.aws/api`, `com.cognitect.aws/endpoints`, and `com.cognitect.aws/ec2` to `state-backend/deps.edn`.
- [x] 2.3 Add a function building an EC2 client pointed at LocalStack (`:endpoint-override {:protocol :http :hostname "localhost" :port 4566}`, static test credentials).
- [x] 2.4 Implement the AWS-response → Terraform-attribute-map translation function for `aws_security_group` (`DescribeSecurityGroups`) and `aws_security_group_rule` (`DescribeSecurityGroupRules`), per design.md's translation table.
- [x] 2.5 Implement the same for the remaining modeled types: `aws_vpc`, `aws_subnet`, `aws_route_table`, `aws_route` (including the synthesized `"<route_table_id>-<destination_cidr_block>"` id), `aws_route_table_association`, `aws_internet_gateway`, `aws_vpc_peering_connection`, `aws_instance`.
- [x] 2.6 Unit-test each translation function against a representative sample of that type's real EC2 API response shape.

## 3. Sync matching and ingestion logic

- [x] 3.1 Add a new `infratomic.state-backend.sync` namespace.
- [x] 3.2 Implement a lookup function: given a resource type and its AWS resource id, find an existing Resource entity (if any) via a Datalog query on that type's modeled id ident (e.g. `:aws-security-group/id`), returning its `:resource/id` if found.
- [x] 3.3 Implement the per-resource ingestion decision: no existing match → build a tx-map with a synthesized `:resource/id` (`"<type>.discovered-<aws_id>"`), `:resource/type`, `:resource/managed? false`, and `(db/resource-attr-tx type attributes)`; existing match on a Discovered Resource → build the same tx-map but keyed by the existing `:resource/id` (update in place); existing match on a Terraform-managed resource → no tx-data (skip).
- [x] 3.4 Implement the full Sync pass: call every `Describe*` API from section 2, translate each result, run it through 3.2/3.3, and transact all resulting tx-data in one transaction.
- [x] 3.5 Implement a summary structure (discovered/updated/skipped-already-managed counts or lists) returned by the Sync pass, for the endpoint to serialize.
- [x] 3.6 Unit/integration-test: a brand-new LocalStack resource is ingested as discovered; a resource matching an existing Terraform-managed entity is skipped (not duplicated, `:resource/managed?` unchanged); running the full pass twice with no LocalStack changes produces no new entities on the second run and updates the existing Discovered Resource's attributes.

## 4. `POST /sync` HTTP endpoint

- [x] 4.1 Add a `POST /sync` route to `main.clj`'s `app-handler`, alongside `/policy-check`, closing over the shared `conn`; respond `405` for other methods on `/sync`.
- [x] 4.2 Wire the route to the Sync pass (section 3), responding `200` with the JSON-encoded summary.
- [x] 4.3 Add a handler-level test (per `handler_test.clj`'s pattern) covering: `POST /sync` with LocalStack resources present returns a summary reflecting them; a non-`POST` method on `/sync` returns `405`.

## 5. CLI `sync` subcommand

- [x] 5.1 Add a `--sync-url`/`INFRATOMIC_SYNC_URL` config point to `cli/main.clj`, defaulting to the sample app's local State Backend address, mirroring the existing `--policy-check-url`/`INFRATOMIC_POLICY_CHECK_URL` pattern.
- [x] 5.2 Add `sync` subcommand handling in `-main`: `POST` an empty body to the Sync URL and parse the JSON response.
- [x] 5.3 Print a human-readable summary of discovered/updated resources on success; on a non-`200`/malformed/failed response, print an error and exit non-zero (fail closed, matching `apply-gated!`'s error handling style).

## 6. End-to-end verification against the sample app

- [x] 6.1 Bring up LocalStack and the State Backend; apply the sample app via Terraform as usual.
- [x] 6.2 Directly create a security group with an ingress rule opening port 22 to `0.0.0.0/0` via `aws`/`awslocal` against LocalStack, bypassing Terraform entirely.
- [x] 6.3 Confirm `query/security-groups-with-port-22-open` (invoked directly, per `query_integration_test.clj`'s pattern) does not yet include that security group.
- [x] 6.4 Run the CLI's `sync` subcommand; confirm it reports the new security group (and its rule) as discovered.
- [x] 6.5 Re-run the same query; confirm the directly-created security group now appears in the results.
- [x] 6.6 Confirm `terraform plan`/`apply` in the sample app afterward shows no attempt to adopt/destroy the discovered security group, and `GET /state` (`terraform state list`) does not list it.
- [x] 6.7 Run `sync` a second time with no LocalStack changes; confirm no duplicate entities are created (e.g. via a direct Datomic query counting entities matching that AWS id).
