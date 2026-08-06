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
Lambda function, Lambda function URL — keyed by `(type, name)`, so
infrastructure is queryable without parsing state JSON. `GET` reconstructs
a Terraform-acceptable state JSON document from these entities on demand.
Storage is Datomic dev-local, embedded in the service process (no separate
transactor), persisted to a gitignored `.datomic/` directory at the repo
root so state survives restarts. See
`docs/adr/0002-reconstruct-state-instead-of-raw-storage.md` for why state
is reconstructed rather than stored raw.

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

## License

infratomic is licensed under the [Apache License 2.0](LICENSE).
