# Infratomic

Infrastructure managed by Terraform, with state and derived infrastructure data queryable through Datomic instead of parsed from raw state files.

## Language

**State Backend**:
The Clojure service that implements Terraform's `http` backend protocol (GET/POST/DELETE) and persists state in Datomic. Lives at `state-backend/`.
_Avoid_: backend service, state service, the service

**State Version**:
A Datomic entity created on each `POST` to the state backend, holding the exact raw state JSON Terraform sent (`:state-version/raw`), plus its `serial` and `lineage` as reported by Terraform itself. Represents one point in the history of applies.
_Avoid_: state blob, run, apply record

**Resource** (entity):
A Datomic entity representing one Terraform-managed resource (e.g. the S3 bucket, the IAM role), identified uniquely by its `(type, name)` pair. Holds the resource's raw attribute map and a reference to the State Version it was last seen in. Upserted on each apply — one entity persists per resource across its lifetime, not one per apply.
_Avoid_: resource record, resource instance

**Raw State**:
The verbatim JSON document Terraform POSTs to the State Backend on each apply. Stored unmodified (never reconstructed from Resource entities) so that `GET` returns exactly what was last written, preserving Terraform's own state-file fidelity.
_Avoid_: state file, decomposed state
