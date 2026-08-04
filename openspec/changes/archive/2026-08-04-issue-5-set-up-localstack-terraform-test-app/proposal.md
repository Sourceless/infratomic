## Why

Developers working on the terraform/Datomic backend have no way to provision and exercise infrastructure locally — every change to S3-backed resources currently requires real AWS credentials and a real account. This blocks fast, safe iteration and testing of infra changes.

## What Changes

- Add a `docker-compose.yml` at the repo root that starts LocalStack Community edition (single-port gateway, port 4566).
- Add a `terraform/` subdirectory containing Terraform config that:
  - Configures the AWS provider to target the LocalStack endpoint (`http://localhost:4566`, path-style S3, dummy `test`/`test` credentials, `us-east-1`, validation/metadata checks skipped).
  - Provisions an S3 bucket.
  - Provisions a minimal `aws_iam_role` (bare assume-role policy) required for Lambda's `create-function` API — a scoped, boilerplate exception to "no IAM."
  - Provisions a Lambda function (with accompanying handler source) that accepts an upload and writes it to the S3 bucket, exposed via a Lambda Function URL.
- Document, in a README (or inline comments), the exact commands to bring the stack up and verify it, via two paths: the AWS CLI (`aws --endpoint-url=... s3 ls`, noted as requiring `awscli` as a prerequisite) and a CLI-free fallback (`terraform output`/`terraform state list` plus `curl` against the Function URL).

## Capabilities

### New Capabilities
- `local-aws-environment`: LocalStack-backed local AWS simulator, started via `docker-compose.yml`, targeted by a Terraform AWS provider configuration.
- `s3-upload-test-app`: S3 bucket plus a Lambda-backed web app (Function URL, minimal IAM role) that uploads files into that bucket, provisioned via Terraform, with documented dual-path verification.

### Modified Capabilities
(none — first infra code in the repo)

## Impact

- New files only: `docker-compose.yml` at repo root; `terraform/` subdirectory containing provider/backend config, S3 bucket resource, IAM role resource, Lambda function + Function URL resources, Lambda handler source, and setup/verification documentation.
- No existing application code, CI, or the unmerged `flake.nix` branch (issue #1) is touched or depended on.
- Out of scope: real AWS deployment or credentials, CI integration, and AWS services beyond S3 and the minimal Lambda + companion IAM role (no DynamoDB, no ECS/EKS/Batch, no API Gateway).
