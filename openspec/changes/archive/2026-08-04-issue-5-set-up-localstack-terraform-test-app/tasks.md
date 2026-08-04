## 1. Local AWS environment

- [x] 1.1 Create `docker-compose.yml` at the repo root running `localstack/localstack` (pin an image tag), exposing gateway port 4566 (and 4510-4559 if needed).
- [x] 1.2 Add a repo-root `.gitignore` covering Terraform state/build artifacts (`.terraform/`, `*.tfstate*`, `crash.log*`, Lambda build zip output).
- [x] 1.3 Create `terraform/` directory with a `provider.tf` (or equivalent) configuring the AWS provider: `test`/`test` credentials, `us-east-1` region, `skip_credentials_validation`/`skip_metadata_api_check` true, S3 endpoint `http://localhost:4566` with `s3_use_path_style = true`.
- [x] 1.4 Verify `docker compose up -d` starts LocalStack and `terraform init` succeeds against the configured provider.

## 2. S3 bucket and IAM role

- [x] 2.1 Add an `aws_s3_bucket` resource in `terraform/` for the test app's uploads.
- [x] 2.2 Add a minimal `aws_iam_role` with a bare Lambda assume-role policy (no extra policy attachments beyond what Lambda execution needs).
- [x] 2.3 Run `terraform apply` and confirm the bucket and role appear in `terraform state list`.

## 3. Lambda upload app

- [x] 3.1 Write a small Lambda handler (under `terraform/`) that accepts an upload request and writes the payload as an object into the S3 bucket.
- [x] 3.2 Add an `archive_file` data source to zip the handler source, keyed on source hash so changes trigger redeployment.
- [x] 3.3 Add the `aws_lambda_function` resource referencing the IAM role and zipped source, targeting the bucket via env var or equivalent config.
- [x] 3.4 Add an `aws_lambda_function_url` resource exposing the function over HTTP (no API Gateway).
- [x] 3.5 Run `terraform apply` and confirm the function and Function URL appear in `terraform state list` / `terraform output`.

## 4. Verification and documentation

- [x] 4.1 Add a Terraform `output` block exposing the Function URL and bucket name.
- [x] 4.2 Manually verify the primary path: `docker compose up -d`, `terraform apply`, `aws --endpoint-url=http://localhost:4566 s3 ls` shows the bucket (note `awscli` as a prerequisite).
- [x] 4.3 Manually verify the fallback path: `terraform output` / `terraform state list` show the resources, and `curl` against the Function URL performs an upload that lands in the bucket (confirm via `aws s3 ls`/`cp` if available, otherwise via `terraform`-driven inspection).
- [x] 4.4 Write setup/verification documentation (README or comments in `terraform/`) covering exact commands for both paths, and the `awscli`-not-installed caveat.
