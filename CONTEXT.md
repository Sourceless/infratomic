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
A Datomic entity representing one Terraform-*managed* resource (e.g. the S3 bucket, the IAM role) — data-source entries from Terraform state are not persisted as Resource entities, since Terraform always re-reads them fresh on every plan/apply. Identified uniquely by its `(type, name)` pair. Holds the resource's raw attribute map, an opaque instance-metadata blob (schema version, provider, sensitive attributes, private data, dependencies — needed to reconstruct a Terraform-acceptable state document), and a reference to the State Version it was last seen in. Upserted on each apply — one entity persists per resource across its lifetime, not one per apply.
_Avoid_: resource record, resource instance

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
