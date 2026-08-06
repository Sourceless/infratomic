# infratomic

The logic-powered infrastructure database

## Local Datomic database

`deps.edn` and `src/infratomic/datomic.clj` set up a local, file-backed
Datomic database (via `com.datomic/local`) so app code can be built and
tested without any shared or remote Datomic infrastructure (no Cloud,
transactor, or license setup).

### Bring up the database

Enter the dev shell (provides a JDK 17+ runtime and the Clojure CLI) and
run the verification test:

```sh
nix develop
clj -M:test
```

There's no separate "bring up" step — `infratomic.datomic/connect`
creates the local database on demand (idempotently) the first time it's
called.

Storage is repo-local rather than relying on `~/.datomic/local.edn`:
data is persisted under `.datomic/storage` (an absolute path resolved
from the process's working directory, gitignored), using the fixed
dev-local system name `"dev"`. Both are defined in
`src/infratomic/datomic.clj`. Delete `.datomic/storage` at any time to
reset local state.

### Verifying it worked

```sh
clj -M:test
```

This runs `test/infratomic/datomic_test.clj`, which connects to a local
database, transacts a minimal inline fixture schema (a single
`:sample/name` string attribute — throwaway test plumbing, not app
schema design), transacts a sample fact, queries it back, and asserts
the result matches. A clean `0 failures, 0 errors` (or equivalent) run
from a fresh clone, with no manual setup beyond `nix develop` + `clj
-M:test`, confirms the round-trip works.

### Notes

- No shared/remote Datomic storage (Cloud, transactor, license setup)
  is in scope — this is purely a local, file-backed database for
  development.
- No app schema design is in scope; the fixture schema transacted by
  the test exists only to exercise the connect/transact/query
  round-trip.

## Local AWS test app (LocalStack + Terraform)

`terraform/` provisions a small, real Lambda-backed upload app — an S3
bucket, a minimal IAM execution role, and a Lambda function exposed via a
Lambda Function URL — against a local AWS simulator, so infra changes can
be exercised without any real AWS account or credentials.

Terraform state for this stack is stored in Datomic via the **State
Backend** (`state-backend/`) rather than a local `terraform.tfstate` file —
see [State Backend](#state-backend-terraform-state-in-datomic) below.

### Bring up the stack

```sh
docker compose up -d          # start LocalStack (gateway on localhost:4566)

# in a separate terminal, inside nix develop:
cd state-backend
clojure -M -m infratomic.state-backend.main   # State Backend on localhost:8080

cd terraform
terraform init
terraform apply
```

`terraform apply` provisions the S3 bucket, IAM role, Lambda function, and
Lambda Function URL against the LocalStack endpoint. On success it prints
`bucket_name` and `function_url` outputs.

### Verifying it worked

There are two verification paths, depending on whether you have the AWS
CLI installed.

**Primary path (requires `awscli`):**

```sh
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
aws --endpoint-url=http://localhost:4566 s3 ls
```

This should list the provisioned bucket (`infratomic-test-app-uploads`).

**Fallback path (no `awscli` required):**

```sh
terraform state list       # shows the bucket, role, Lambda function, and function URL resources
terraform output           # shows bucket_name and function_url

FUNCTION_URL=$(terraform output -raw function_url)
curl -X POST "${FUNCTION_URL}?key=hello.txt" \
  -H "Content-Type: text/plain" \
  --data-binary "hello from curl"
```

A `200` response with a JSON body echoing the bucket and key means the
Lambda wrote the payload into S3. If `awscli` happens to be available you
can then confirm the object landed with
`aws --endpoint-url=http://localhost:4566 s3 cp s3://infratomic-test-app-uploads/hello.txt -`;
otherwise, `terraform state list` / `terraform output` plus the `200`
response from `curl` are sufficient confirmation that the end-to-end path
works.

Note: use `--data-binary` with an explicit `Content-Type` (as above) rather
than curl's `-d`/`--data`, which defaults to
`application/x-www-form-urlencoded` and gets re-encoded (spaces become
`+`) before it reaches the Lambda.

### Notes

- This targets LocalStack Community edition only — no real AWS credentials,
  accounts, or endpoints are involved (see `terraform/provider.tf`).
- The IAM role in `terraform/iam.tf` is a bare Lambda assume-role
  boilerplate needed to satisfy the Lambda `create-function` API; LocalStack
  Community doesn't enforce IAM policy, so no broader IAM design is in
  scope.
- Out of scope for this stack: real AWS deployment, CI integration, and AWS
  services beyond S3 and the minimal Lambda + IAM role (no DynamoDB,
  ECS/EKS/Batch, or API Gateway).

## State Backend (Terraform state in Datomic)

`state-backend/` is a Clojure service implementing Terraform's `http` state
backend protocol (`GET`/`POST`/`DELETE` on `/state`; `LOCK`/`UNLOCK` are out
of scope). No raw state JSON is ever stored — Datomic dev-local hard-limits
`:db.type/string` datoms to 4096 bytes, well under the sample app's real
state size — so each `terraform apply`'s `POST` is decomposed instead: a
small `state-version` entity holds the state's top-level metadata (format
version, Terraform version, serial, lineage, outputs), and one Datomic
entity is upserted per Terraform-*managed* resource — bucket, IAM role,
Lambda function, Lambda function URL, security groups and rules — keyed by
`(type, name)`. Each resource's *attributes* are themselves decomposed into
real Datomic datoms rather than one opaque JSON string: attributes declared
in a data-driven schema map (`aws_security_group`/`aws_security_group_rule`
today) are typed, structural attributes directly queryable via Datalog;
every other attribute is a generic, still-real, exact-match-searchable
key/value datom. `GET` reconstructs a Terraform-acceptable state JSON
document from these entities on demand. Storage is Datomic dev-local,
embedded in the service process (no separate transactor), persisted to a
gitignored `.datomic/` directory at the repo root so state survives
restarts. See `docs/adr/0003-decompose-resource-attributes-into-datoms.md`
for why attributes are decomposed into datoms (superseding
`docs/adr/0002-reconstruct-state-instead-of-raw-storage.md`).

If you have an existing local `.datomic/` directory from before this
change, delete it (or `DELETE /state` against a running service, then
re-`apply`) before first running against this change — the new schema
changes what's written and expected on read, and there's no migration
tooling for old dev-local data (see the ADR's Migration Plan).

### Running it

From inside `nix develop` (provides the `clojure` CLI and a JDK):

```sh
cd state-backend
clojure -M -m infratomic.state-backend.main
```

This starts the service on `http://localhost:8080`, creating the Datomic
database and schema on first run if they don't already exist.

### Switching `terraform/` onto the State Backend

`terraform/provider.tf` already points the `http` backend at
`http://localhost:8080/state`. With the State Backend running:

- **Fresh checkout / disposable local state (the common case for this
  sample app):** delete any existing `terraform/terraform.tfstate` and run
  `terraform init` as normal — Terraform initializes straight against the
  `http` backend with no local state to migrate.
- **If you have existing local state you want to keep:** run
  `terraform init -migrate-state` instead. Terraform will detect the
  backend change and prompt to copy the existing local state into the
  State Backend on `POST /state`.

This is a one-time step per checkout; subsequent `terraform apply` runs
read and write state exclusively through the service.

### Querying deployed infrastructure

`state-backend/src/infratomic/state_backend/query.clj` provides 4 functions
proving that decomposed attributes answer real infrastructure questions as
structural Datalog queries rather than JSON-blob scans: all deployed
resources, resources by type, resources by attribute value (unified across
generic and modeled/typed storage), and security groups with port 22 open
to the internet (a join from `aws_security_group_rule`'s typed port/CIDR
attributes back to its owning `aws_security_group`). These are functions
only — called from tests, with no HTTP surface — see
`state-backend/test/infratomic/state_backend/query_test.clj` for usage
examples against an in-memory db.

### Running the tests

From inside `nix develop`, in `state-backend/`:

```sh
clojure -X:test
```

Runs the hermetic test suite (`handler_test.clj`, `query_test.clj`)
against an in-memory Datomic dev-local database — no LocalStack or running
service required. A clean `0 failures, 0 errors` confirms the `POST`/`GET`
round-trip (including attribute decomposition/reconstruction) and all 4
query functions behave correctly.

There's also an integration test that exercises the real path end to end —
`terraform apply` against real LocalStack, real Datomic dev-local storage,
and the query functions run against that live db:

```sh
docker compose up -d   # from the repo root; ec2 must be enabled
cd state-backend
clojure -X:integration-test
```

This starts its own State Backend HTTP server for the test's duration
(Datomic dev-local only allows one process to hold an open connection to a
database at a time, so make sure no other `clojure -M -m
infratomic.state-backend.main` process is already running against the
same `.datomic/` storage before running this). It applies the sample app,
asserts on all 4 query functions' results, then destroys the sample app's
resources again — safe to run repeatedly without polluting shared local
dev state.
