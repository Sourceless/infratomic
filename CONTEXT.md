# Infratomic

Infrastructure managed by Terraform, with state and derived infrastructure data queryable through Datomic instead of parsed from raw state files.

## Language

**State Backend**:
The Clojure service that implements Terraform's `http` backend protocol (GET/POST/DELETE) and persists state in Datomic. Lives at `state-backend/`.
_Avoid_: backend service, state service, the service

**State Version**:
A Datomic entity created on each `POST` to the state backend, holding the state document's top-level metadata as Terraform reported it — format `version`, `terraform_version`, `serial`, `lineage`, and `outputs` — but never the raw JSON body itself (Datomic dev-local's 4096-byte-per-string limit makes storing the sample app's real state document, ~12.4KB, impossible). Represents one point in the history of applies.
_Avoid_: state blob, run, apply record

**Resource** (entity):
A Datomic entity representing one Terraform-*managed* resource (e.g. the S3 bucket, the IAM role), or one Discovered Resource — data-source entries from Terraform state are not persisted as Resource entities, since Terraform always re-reads them fresh on every plan/apply. Identified uniquely by `:resource/id`: `"<type>.<name>"` (its Terraform address) for Terraform-managed resources, `"<type>.discovered-<aws_id>"` for Discovered Resources. Carries `:resource/managed?` — `true` for Terraform-managed resources, `false` for Discovered Resources — set on every write, including by `resource->tx` on the `POST /state` path, and read by both `GET /state` reconstruction and `stale-resource-retractions` to exclude Discovered Resources from what Terraform is told it owns and from the stale-sweep on each apply. Holds the resource's raw attribute map, an opaque instance-metadata blob (schema version, provider, sensitive attributes, private data, dependencies — needed to reconstruct a Terraform-acceptable state document), and a reference to the State Version it was last seen in. Upserted on each apply (or Sync) — one entity persists per resource across its lifetime, not one per apply.
_Avoid_: resource record, resource instance

**Discovered Resource**:
A Resource entity ingested directly from LocalStack's EC2 API rather than from a Terraform `apply`/`plan` — has no Terraform address, so its `:resource/id` is synthesized as `"<type>.discovered-<aws_id>"` instead of `"<type>.<name>"`, reusing the same identity-upsert path as Terraform-managed Resources. Matched against existing entities by AWS resource id, never `(type, name)` — for `aws_security_group_rule` specifically, "AWS resource id" means AWS's own `SecurityGroupRuleId` (distinct from Terraform's synthetic rule id), captured on first discovery and reused as the match key on every later Sync so re-running Sync updates rather than duplicates a previously-discovered rule. Always carries `:resource/managed?` `false`.
_Avoid_: unmanaged resource, out-of-band resource, drifted resource

**Sync**:
The State Backend operation, triggered by the CLI's `sync` subcommand over HTTP (mirroring how `apply` triggers Policy Check), that queries LocalStack's EC2 API directly and ingests any resources not already known as Discovered Resources — runs inside the State Backend process itself so it shares the one dev-local connection the process already holds, rather than opening a second one.
_Avoid_: discovery, ingestion, drift sync

**Write Source**:
`:resource/last-write-source` (`:terraform` or `:sync`) — which write path most recently wrote a Resource entity's attributes: `:terraform` for a `POST /state` write (`resource->tx`); `:sync` for any Sync write (`resource-tx`) — a newly Discovered Resource, an update to a previously-discovered one, or a diff-gated update to a previously Terraform-managed resource whose observed live value had Drifted. Set on every write, by both write paths, mirroring `:resource/managed?`'s "set on every write" discipline (see ADR-0009). The drift Rule reads it, plus Datomic history, to find Terraform's last-asserted values to compare a managed resource's current values against.
_Avoid_: source tag, write tag, origin

**Drift**:
An out-of-band change to a Terraform-managed resource: a live value in LocalStack diverging from what Terraform itself last asserted for it. Detected by the drift Rule (`query.clj`), a query-time-only function — deliberately never registered in `policy.clj`'s Rule registry — that finds every managed resource whose most recent Write Source is `:sync` and compares its current attributes against its stored values as of the most recent `:terraform`-sourced write (found via Datomic history, `d/history`/`d/as-of` — see ADR-0009). Surfaced via `GET /drift` and the CLI's `drift-check` subcommand. Distinct from a Discovered Resource: a Discovered Resource was never Terraform-managed in the first place, whereas Drift is a Terraform-managed resource whose live value has since diverged. Detection only — nothing auto-remediates Drift (reverting the live value or re-applying Terraform to overwrite it), and it is never evaluated as part of the pre-apply Policy Check.
_Avoid_: unmanaged resource, out-of-band resource (see Discovered Resource), skew

**Reconstructed State**:
The Terraform-state-JSON document the State Backend builds on `GET`, on the fly, from the latest State Version entity plus the current set of Resource entities. Not byte-identical to what Terraform last `POST`ed — Terraform parses it structurally rather than diffing it, so this is semantically equivalent, not a stored artifact. No raw state JSON is ever stored anywhere.
_Avoid_: raw state, state blob, state file

**CLI**:
The `cli/` executable that stands in for the `terraform` binary in an operator's workflow — passes every subcommand straight through unchanged except `apply`, which it intercepts to run a Policy Check before allowing the real `apply` through.
_Avoid_: wrapper, the wrapper CLI, tf wrapper

**Policy Check**:
The State Backend operation, triggered by the CLI over HTTP, that decomposes a `terraform plan`'s JSON into a same-shaped speculative Datomic db (via `d/with`, never committed) and evaluates it against every registered Rule, returning any Violations found. Runs inside the State Backend process itself so it shares the one dev-local connection the process already holds, rather than opening a second one.
_Avoid_: policy endpoint, speculative check, plan check

**Rule**:
A `(fn [db] -> seq-of-maps)` value registered with the Policy Check — given a db (live or speculative), returns the Resources that fail it; non-empty means violated. `security-groups-with-port-22-open` is the first Rule, reused unmodified.
_Avoid_: policy, check function, validator

**Violation**:
One Resource's failure of one Rule during a Policy Check — structured data (at minimum which Rule flagged it, and the Resource's id/type), never printed by the Policy Check itself. Printing/formatting a Violation is the CLI's job.
_Avoid_: error, failure, violation message

**Address Stand-in**:
When a Policy Check evaluates a not-yet-applied Resource, its AWS-assigned identifying attributes (e.g. `aws_security_group.id`) don't exist yet. The plan-decomposition glue code substitutes the Resource's own Terraform address instead (and resolves a direct single-reference symbolic dependency to the referenced Resource's address too), so an identity-based Rule join still matches. Only ever appears in a speculative db — never leaks into a real, applied Resource entity.
_Avoid_: synthetic id, placeholder id, fake id

**Workload**:
An `aws_instance` resource placed in the sample app's network graph (VPC, subnet, security groups) — the endpoint kind `reachable?` traverses between when answering network reachability questions. Distinct from "the service", which refers to the State Backend itself.
_Avoid_: service, node, host

**Hop**:
One `aws_vpc_peering_connection` traversed during a Peering Chain traversal — the unit `reachable-within-hops?`'s `max-hops` counts and its recursive rule decrements on each step. Distinct from a VPC boundary crossing: two instances in the same VPC cross no Hop at all.
_Avoid_: step, edge, jump

**Peering Chain**:
A path of zero or more Hops connecting two VPCs, walked by `reachable-within-hops?`'s bounded-recursive traversal — as distinct from `reachable?`'s single fixed peering hop, which only ever sees one `aws_vpc_peering_connection` directly.
_Avoid_: transit path, peering path, multi-hop route

**Principal**:
The IAM identity (always an `aws_iam_role` in this system's scope — no `aws_iam_user`) that `iam-reachable?` asks "can this act?" about. Identified by its `:resource/id` (e.g. `"aws_iam_role.source"`), resolved to its ARN only when a Policy Statement needs to match it against a `Principal` field. Distinct from a Workload, which is the network graph's own endpoint concept.
_Avoid_: identity, actor, subject

**Policy Statement**:
One `Effect`/`Action`/`Resource`/`Principal` entry from a parsed IAM policy document (`assume_role_policy`, an inline `aws_iam_role_policy`, an `aws_s3_bucket_policy`, or an `aws_iam_policy` reached via `aws_iam_role_policy_attachment`), decomposed at query time (never persisted) into a scratch `:iam-statement/*` Datomic entity so the `grants` rule can traverse real datoms instead of parsed JSON. Tagged `:identity`, `:resource`, or `:trust` depending which side of a grant it evaluates. See `docs/adr/0005-derive-iam-policy-facts-at-query-time-via-speculative-db.md`.
_Avoid_: policy rule, grant record, ACL entry

**Trust Policy**:
An `aws_iam_role`'s `assume_role_policy` — the Policy Statement(s) naming which principals may `sts:AssumeRole` it. Modeled as an ordinary resource-based Policy Statement (kind `:trust`, sourced from the assumed role's own entity) rather than a separate mechanism, so the same allow/deny-override logic that evaluates every other resource-based statement evaluates role assumption too.
_Avoid_: assume-role policy (as a distinct mechanism), trust relationship

**IAM-reachable**:
The result of `iam-reachable?`: whether a Principal can perform a given IAM action on a given resource, per the deployed IAM policy graph — identity-based Policy Statements, resource-based Policy Statements, and role-assumption chains of Trust Policies, with explicit-deny-overrides-allow semantics — evaluated as recursive Datalog (`grants`), not an application-level walk of parsed policy JSON. Distinct from Reachable, which answers network reachability, not IAM access.
_Avoid_: has access, is authorized, can assume

**Dev-Local Gateway**:
A new network-separated process that sits in front of the State Backend's existing dev-local-embedded Datomic, exposing a wire protocol shaped to mirror Datomic Pro/Cloud's own `datomic.client.api` semantics (opaque db/connection handles, same conceptual request/response shape) rather than an ad-hoc bespoke RPC design. Satisfies issue #35's "connects to Datomic Pro, not dev-local" intent via genuine network separation instead of a literal Datomic Pro/Peer Server connection — chosen because `com.datomic/client-pro` requires a my.datomic.com-gated Maven repo with no clear build/CI credential path, and no official Datomic Pro Docker image exists. The mirrored protocol shape means a real my.datomic.com/client-pro-backed environment could later replace the Gateway with minimal change to the State Backend side.
_Avoid_: the proxy, the bridge, Datomic Pro connection
