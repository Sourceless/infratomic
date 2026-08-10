## Context

Alignment on this issue is already resolved and recorded in `docs/adr/0005`–`0008` and in `CONTEXT.md` ("Resource", "Discovered Resource", "Sync"). This document records how those decisions fit together as one implementation, and fills in the mechanical details (data shapes, function boundaries, request/response contracts) the ADRs don't spell out. See `proposal.md` for motivation; see the ADRs for why each individual decision was made the way it was.

Existing precedent this follows directly:
- `policy.clj` + `main.clj`'s `app-handler`: the pattern for adding a new State Backend HTTP endpoint that shares the process's one dev-local `conn`.
- `db/resource-attr-tx` / `decompose-attributes` (db.clj): the existing, unmodified seam from a Terraform-attribute-key-shaped map to Datomic tx-data, already reused by both `POST /state` and `POST /policy-check`.
- `cli/main.clj`'s `apply-gated!`/`post-json`/`flag-value`: the pattern for a CLI subcommand that calls a State Backend HTTP endpoint and prints a result.

## Goals / Non-Goals

**Goals:**
- Ingest every resource type in `resource-schema` from LocalStack's EC2 API into Resource entities tagged discovered/unmanaged, matched by AWS resource id.
- Ensure `GET /state` and `POST /state`'s stale-retraction sweep never expose or delete Discovered Resources.
- Reuse `db/resource-attr-tx` unmodified for Discovered Resources, by translating each AWS API response into the same Terraform-attribute-key vocabulary `resource-schema` already expects.

**Non-Goals:**
- Running real `terraform import` (per the issue's "Out of scope").
- Automatic/scheduled Sync — `POST /sync` is the only trigger.
- Reconciling *attribute-level* drift on a resource that's already Terraform-managed (e.g. someone hand-edits a Terraform-created SG's rules directly in LocalStack) — Sync's matching (below) treats "already has a Resource entity for this AWS id" as fully handled and skips it, regardless of origin. Detecting and surfacing that kind of drift is future work, not this change.
- A new live-state-evaluating HTTP endpoint (e.g. "run this Rule against current state now"). The issue's "run the port-22 policy check/query" verification step is satisfied by invoking `query/security-groups-with-port-22-open` directly against the live db, matching the existing integration-test pattern (`query_integration_test.clj`) — `/policy-check` remains plan-only, unchanged.

## Decisions

### `:resource/managed?` and the two read-path filters (ADR-0005)
A new `:resource/managed?` boolean schema attribute (db.clj `schema`) is set on every resource write path:
- `handler.clj`'s `resource->tx` now includes `:resource/managed? true` in the tx-map it already builds for every `POST /state` managed entry.
- Sync's own per-resource tx-maps include `:resource/managed? false`.

Two existing read paths gain a filter on it:
- `handler.clj`'s `reconstruct-state`/`db/all-resources` (`GET /state`): only pull entities where `:resource/managed?` is `true`.
- `handler.clj`'s `stale-resource-retractions`: only consider entities where `:resource/managed?` is `true` as "existing" candidates for retraction — a Discovered Resource is never in this set, so it's never retracted regardless of whether it appears in a posted body.

**Backfill**: `db/ensure-db!` (already the idempotent "create db, transact schema" entry point run on every startup) gains one more step after transacting schema: find every resource entity with no `:resource/managed?` value and transact `:resource/managed? true` on it. This runs on every startup but only ever touches entities lacking the attribute, so after the first run post-deploy it's a no-op query returning nothing. No separate migration script.

### Resource matching and `:resource/id` synthesis (ADR-0005/0006, CONTEXT.md)
Sync reuses the *existing* `:resource/id` `:db.unique/identity` upsert path rather than a separate pre-insert lookup — the same upsert mechanism `POST /state` already relies on. For each resource Sync finds in LocalStack:
1. Read the AWS resource id from the API response (`GroupId` for `aws_security_group`, `SecurityGroupRuleId` for `aws_security_group_rule` via `DescribeSecurityGroupRules`, `VpcId` for `aws_vpc`, etc. — one field per modeled type, see the translation table below).
2. Look up whether any existing Resource entity already has that value as its modeled id attribute (e.g. `:aws-security-group/id "sg-123"`) — via a Datalog query on the modeled id ident, not a `:resource/id` guess, since a Terraform-managed match has a `:resource/id` of `"<type>.<name>"`, not `"<type>.discovered-<aws_id>"`.
3. If a match exists (Terraform-managed or previously discovered), transact using *that* entity's actual `:resource/id` as the upsert key — updating a previously-discovered entity's attributes in place, or, for a Terraform-managed match, transacting nothing further for that resource (its identity and `:resource/managed?` are left untouched).
4. If no match exists, transact using the synthesized `:resource/id` of `"<type>.discovered-<aws_id>"`, creating a new entity.

This is the concrete mechanism behind the issue's "matched against existing Resource entities by AWS resource id, not (type, name)" and "running sync twice does not create duplicate entities" acceptance criteria.

### AWS API response → Terraform attribute-key translation
Sync introduces one small pure function per modeled resource type, each mapping that type's `Describe*` response shape into the `attributes` map shape `resource-schema` already expects (the same shape Terraform's own state JSON uses), e.g.:

| Type | EC2 API call | AWS field → Terraform key |
|---|---|---|
| `aws_security_group` | `DescribeSecurityGroups` | `GroupId`→`id`, `VpcId`→`vpc_id` |
| `aws_security_group_rule` | `DescribeSecurityGroupRules` | `SecurityGroupRuleId`→`id`, `FromPort`→`from_port`, `ToPort`→`to_port`, `IpProtocol`→`protocol`, `GroupId`→`security_group_id`, `CidrIpv4`→`cidr_blocks` (as a single-element list), `ReferencedGroupInfo.GroupId`→`source_security_group_id` |
| `aws_vpc` | `DescribeVpcs` | `VpcId`→`id`, `CidrBlock`→`cidr_block` |
| `aws_subnet` | `DescribeSubnets` | `SubnetId`→`id`, `VpcId`→`vpc_id`, `CidrBlock`→`cidr_block` |
| `aws_route_table` | `DescribeRouteTables` | `RouteTableId`→`id`, `VpcId`→`vpc_id` |
| `aws_route` | `DescribeRouteTables` (`Routes[]`, per table) | synthesized id (route table has no per-route AWS id — see below), `DestinationCidrBlock`→`destination_cidr_block`, `GatewayId`→`gateway_id`, `VpcPeeringConnectionId`→`vpc_peering_connection_id` |
| `aws_route_table_association` | `DescribeRouteTables` (`Associations[]`) | `RouteTableAssociationId`→`id`, `SubnetId`→`subnet_id`, `RouteTableId`→`route_table_id` |
| `aws_internet_gateway` | `DescribeInternetGateways` | `InternetGatewayId`→`id`, `Attachments[0].VpcId`→`vpc_id` |
| `aws_vpc_peering_connection` | `DescribeVpcPeeringConnections` | `VpcPeeringConnectionId`→`id`, `RequesterVpcInfo.VpcId`→`vpc_id`, `AccepterVpcInfo.VpcId`→`peer_vpc_id` |
| `aws_instance` | `DescribeInstances` (`Reservations[].Instances[]`) | `InstanceId`→`id`, `SubnetId`→`subnet_id`, `SecurityGroups[].GroupId`→`vpc_security_group_ids` |

`aws_route` has no AWS-assigned per-route id (a route is identified by its route table + destination CIDR, not a standalone resource id in the EC2 API) — its synthesized "AWS id" for matching purposes is `"<route_table_id>-<destination_cidr_block>"`, deterministic and stable across Sync runs, consistent with treating "AWS resource id" as "the stable id this API surfaces", not necessarily a literal `*Id` field.

Every field not named in this table falls through to the existing generic `:resource/attribute` escape hatch automatically, exactly as it already does for `POST /state` and `POST /policy-check` — no new code path needed for that.

### `POST /sync` endpoint shape
Routed in `main.clj`'s `app-handler` exactly like `/policy-check`: closes over the shared `conn`, dispatches only on `POST`, `405`s any other method. Takes no request body (Sync's inputs are "whatever LocalStack currently has", not a client-supplied document). Responds `200` with a JSON summary, e.g. `{"discovered": [{"type": ..., "id": ...}, ...], "updated": [...], "skipped_already_managed": N}` — enough for the CLI to print a human-readable summary, without over-specifying the exact shape at the spec level (the spec only requires *a* summary of what was discovered/ingested).

### AWS SDK usage (ADR-0008)
`com.cognitect.aws/api`+`ec2`, built once per Sync invocation (or once at process start and reused — an implementation choice, not a correctness one) with:
```clojure
(aws/client {:api :ec2
             :endpoint-override {:protocol :http :hostname "localhost" :port 4566}
             :credentials-provider (credentials/basic-credentials-provider
                                     {:access-key-id "test" :secret-access-key "test"})})
```
LocalStack accepts any non-empty static credentials; no real AWS account or credentials are ever involved.

### CLI `sync` subcommand
Mirrors `apply-gated!`'s shape: a new `--sync-url`/`INFRATOMIC_SYNC_URL` config point (matching the existing `--policy-check-url`/`INFRATOMIC_POLICY_CHECK_URL` pattern) defaulting to the sample app's local State Backend address, a `post-json`-style call (this one has no request body — a `POST` with an empty body), and a summary printer analogous to `print-violation`. Fails closed with a non-zero exit and a printed error on any non-`200`/malformed response, consistent with the CLI's existing Policy Check error handling.

## Risks / Trade-offs

- **[Risk]** `com.cognitect.aws/ec2`'s LocalStack Community-edition coverage may have field-completeness gaps (ADR-0006 already notes this for `DescribeSecurityGroupRules`). → Mitigation: only the fields actually used for matching/modeled attributes need to be present and correct; anything missing/wrong on an unmodeled field lands in the generic escape hatch or is simply absent, it doesn't fail the Sync.
- **[Risk]** The one-time `:resource/managed?` backfill runs an extra Datalog query + transaction on every `ensure-db!` call (i.e. every process start), forever, even once it's a guaranteed no-op. → Accepted: it's a cheap query (all resources missing one attribute) against a dev-local database sized for a sample app, not a production-scale concern.
- **[Trade-off]** Sync makes one or more real (LocalStack) network calls per invocation, synchronously, inside the `POST /sync` handler — a large discovery could make the endpoint slow to respond. → Accepted: manual-trigger-only (a Non-Goal explicitly excludes scheduling), and the sample app's resource counts are small; not a concern at this scale.

## Migration Plan

No user-facing migration. The `:resource/managed?` backfill (see above) runs automatically and idempotently on the next State Backend startup after this change deploys — no manual step, no downtime, no schema change that blocks a running process (Datomic schema additions are additive/backwards-compatible).
