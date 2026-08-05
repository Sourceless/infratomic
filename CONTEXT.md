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
