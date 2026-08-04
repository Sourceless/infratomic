## Context

See proposal.md - Why. This is the first infrastructure code in the repository: no existing `terraform/`, `docker-compose.yml`, `.gitignore`, or CLAUDE.md conventions to reconcile with. Issue #1's `flake.nix` branch is unmerged and out of scope for this change (see alignment decision) — this change documents raw host commands (`docker`, `terraform`, `aws`) rather than relying on a Nix devShell. `terraform`, `docker`/`docker compose`, and the LocalStack CLI are confirmed present in the dev sandbox; the `aws` CLI and `awslocal` are not, which is why verification is documented with a CLI-free fallback.

## Goals / Non-Goals

**Goals:**
- A one-command local AWS simulator (`docker compose up -d`) that Terraform can target with zero real AWS credentials.
- A genuinely running, demoable app (Lambda + Function URL) rather than a static Terraform-only resource, per the alignment decision.
- Verification that works whether or not the developer has `awscli` installed.

**Non-Goals:**
- Real AWS deployment, credentials, or environments.
- CI integration.
- Any AWS service beyond S3 and the minimal Lambda + companion IAM role (no DynamoDB, ECS/EKS/Batch, or API Gateway).
- General-purpose or reusable Terraform modules — this is a single-purpose test app, not a module library.
- Wiring `terraform`/`docker`/`awscli` into `flake.nix` — left as a future follow-up.

## Decisions

- **Compute primitive: Lambda via LocalStack Community edition.** LocalStack Community ships `lambda_`, `iam`, and `apigateway` providers; `ecs`/`eks`/`batch`/`elasticbeanstalk` are Pro-only and unavailable. A Terraform `docker` provider "fake ECS" was considered and rejected — it wouldn't exercise real AWS-shaped compute or resource wiring the way a genuine LocalStack-emulated Lambda does.
- **Exposure: Lambda Function URL, not API Gateway.** Function URL routing is natively implemented in LocalStack Community and gives a single HTTP endpoint with no second AWS service to provision, matching the issue's IAM/API-Gateway scoping.
- **S3 provider endpoint: `http://localhost:4566` + `s3_use_path_style = true`**, not `s3.localhost.localstack.cloud:4566`. The DNS-dependent hostname relies on public wildcard DNS resolution, which is a plausible "works on my machine" failure mode; the path-style + plain-host combination is LocalStack's documented, DNS-independent fallback.
- **IAM role is boilerplate, not a design decision.** LocalStack Community does not enforce IAM policy (that's a Pro feature), so the `aws_iam_role` + assume-role policy exists purely to satisfy the Lambda `create-function` API's requirement for a role ARN — a single bare role, no policy attachments beyond what's needed to let Lambda assume it.
- **Directory layout: `terraform/` subdirectory, `docker-compose.yml` at root.** Keeps the repo root uncluttered as more infra (e.g. issue #4's Datomic setup) lands later; matches the issue's explicit requirement that `docker-compose.yml` live at root.
- **Lambda handler language/runtime:** implemented as a small Python (or Node.js) handler using the AWS SDK to `PutObject` the uploaded payload into the S3 bucket, packaged as a Terraform-zipped deployment artifact (`archive_file` data source) built from source checked into `terraform/`. Chosen for minimal runtime dependencies and because both Python and Node.js Lambda runtimes are supported by LocalStack Community without extra setup; the specific choice is an implementation detail deferred to tasks.md / implementation, not a spec-level concern.
- **Dual verification path is documented, not automated.** No test harness or CI script is added (out of scope); both paths are plain shell commands in the README so a developer can copy-paste them.

## Risks / Trade-offs

- [Wildcard DNS or LocalStack version drift changes default endpoint behavior] → Pin the plain `localhost:4566` + path-style endpoint config, which is the documented stable fallback, and note the LocalStack image tag used in `docker-compose.yml`.
- [Developer lacks `awscli`] → Mitigated by documenting the `terraform output`/`state list` + `curl` fallback as an equally first-class verification path, not an afterthought.
- [Lambda packaging (zip build) adds a step between `terraform apply` runs if handler source changes] → Use Terraform's `archive_file` data source keyed on source hash so `terraform apply` automatically rebuilds and redeploys the zip when the handler changes, with no separate build step for the developer to remember.
- [First infra code in the repo — no prior `.gitignore`] → Add Terraform-specific ignores (`.terraform/`, `*.tfstate*`, `crash.log*`, built Lambda zip artifacts) as part of this change so local state/build output doesn't get committed accidentally.
