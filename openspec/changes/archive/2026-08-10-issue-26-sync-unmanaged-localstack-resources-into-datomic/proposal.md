## Why

The State Backend only ever learns about resources Terraform tells it about via `POST /state`. A resource created directly against LocalStack's EC2 API (e.g. `aws`/`awslocal` CLI, out-of-band drift) is invisible to it, so an existing Rule like `security-groups-with-port-22-open` can never catch it — policy checks only see what Terraform happens to know about, not what's actually running. Sync closes that gap by pulling resources directly from LocalStack's EC2 API and ingesting any not already known, tagged as Discovered (unmanaged) so Terraform is never told it owns them.

## What Changes

- Add a new `:resource/managed?` boolean schema attribute to every Resource entity: `true` for Terraform-managed resources (set by the existing `POST /state` path), `false` for Discovered Resources (set by Sync). A one-time idempotent backfill (run alongside schema transaction on startup) sets it `true` on every Resource entity that predates this change.
- **Modify** `GET /state` reconstruction to only include `:resource/managed? true` entities, so Terraform is never told it owns a Discovered Resource.
- **Modify** `POST /state`'s stale-resource retraction sweep to only consider `:resource/managed? true` entities as "existing", so a real `terraform apply` never retracts a Discovered Resource.
- Add a new `:aws-security-group-rule/id` modeled attribute (`resource-schema`, `aws_security_group_rule` → Terraform attribute key `"id"`) holding AWS's own `SecurityGroupRuleId` (from `DescribeSecurityGroupRules`, distinct from Terraform's synthetic rule id and from `IpPermissions` embedded on `DescribeSecurityGroups`), giving security-group-rule discovery a stable, schema-modeled AWS id to match on.
- Add a new `POST /sync` HTTP endpoint on the existing State Backend process (alongside `/state` and `/policy-check`, same `conn`, same routing style): calls LocalStack's EC2 API (via `com.cognitect.aws/api`+`ec2`, `:endpoint-override` pointed at `localhost:4566`) for every resource type modeled in `resource-schema`, translates each result into the existing Terraform-attribute-key vocabulary, and upserts each as a Resource entity via the existing `db/resource-attr-tx` decomposition path with `:resource/managed?` `false`. Matching against an already-discovered resource is by a synthesized `:resource/id` of `"<type>.discovered-<aws_id>"`, reusing the existing `:db.unique/identity` upsert — so re-running Sync updates rather than duplicates a previously-discovered resource. Responds with a JSON summary of what was discovered/ingested.
- Add a new `sync` subcommand to the `cli/` wrapper that `POST`s to the Sync endpoint (same base-URL config pattern as the existing Policy Check flag/env var) and prints a human-readable summary of the result.

## Capabilities

### New Capabilities
- `resource-sync`: the State Backend's new `POST /sync` endpoint and supporting AWS-API-to-tx-data translation logic that discovers LocalStack resources not already known and ingests them as unmanaged Resource entities, plus the CLI's `sync` subcommand that triggers it.

### Modified Capabilities
- `state-backend`: Resource entities now carry a `:resource/managed?` flag; `GET /state` reconstruction and the `POST /state` stale-resource retraction sweep both now only operate over `:resource/managed? true` entities, so Discovered Resources are excluded from what Terraform is told it owns and from the stale-sweep.
- `terraform-cli`: the CLI gains a new `sync` subcommand (existing subcommand passthrough and `apply` gating behavior are unchanged).

## Impact

- New code: a new `infratomic.state-backend.sync` namespace (state-backend), a new route wired into `main.clj`'s `app-handler`, a new `:aws-security-group-rule/id` schema entry and a `:resource/managed?` schema attribute + startup backfill in `db.clj`, small changes to `handler.clj` (`resource->tx` sets `:resource/managed? true`; `stale-resource-retractions` and `all-resources`/`reconstruct-state` filter on it), and a new `sync` subcommand in `cli/main.clj`.
- New dependency: `com.cognitect.aws/api` + `com.cognitect.aws/endpoints` + `com.cognitect.aws/ec2` added to `state-backend/deps.edn` — the repo's first AWS SDK dependency.
- No changes to `query.clj`'s Rule logic (`security-groups-with-port-22-open` is reused completely unmodified) or to the `/policy-check` endpoint's contract.
- Sample-app/LocalStack verification exercises creating a security group directly via `aws`/`awslocal` (bypassing Terraform), confirming it's initially invisible to the port-22 query, running Sync, and confirming it then appears as a Violation.
