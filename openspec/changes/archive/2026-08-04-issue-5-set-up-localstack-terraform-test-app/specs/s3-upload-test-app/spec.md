## Purpose

Provisions a minimal, demoable Lambda-backed web app — an S3 bucket, its supporting IAM role, and a Lambda function reachable over HTTP via a Function URL — so developers can exercise a real upload-to-S3 flow entirely against the local AWS simulator.

## ADDED Requirements

### Requirement: S3 bucket is provisioned
Terraform SHALL provision an S3 bucket against the LocalStack endpoint for the test app to store uploaded files in.

#### Scenario: Bucket exists after apply
- **WHEN** `terraform apply` completes successfully
- **THEN** an S3 bucket exists in the LocalStack instance and is listed by `terraform state list`

### Requirement: Minimal IAM role for Lambda execution
Terraform SHALL provision a minimal `aws_iam_role` with a bare Lambda assume-role policy, used solely to satisfy the Lambda `create-function` API's requirement for a role ARN. No broader IAM policy design (fine-grained permissions, multiple roles, policy attachments beyond what the Lambda runtime needs) is in scope.

#### Scenario: Lambda function has a valid execution role
- **WHEN** the Lambda function resource is created by `terraform apply`
- **THEN** it references the provisioned IAM role's ARN and LocalStack accepts the `create-function` call

### Requirement: Lambda-backed app uploads files to S3
Terraform SHALL provision a Lambda function, with accompanying handler source code, that accepts an upload request and writes the uploaded content as an object into the provisioned S3 bucket.

#### Scenario: Uploading a file through the app
- **WHEN** a client sends an upload request (e.g. an HTTP POST with a file payload) to the deployed Lambda
- **THEN** the Lambda handler writes the file's content as a new object in the provisioned S3 bucket

### Requirement: App is reachable via a Lambda Function URL
The Lambda function SHALL be exposed over HTTP using a Lambda Function URL (not API Gateway or any other routing layer), so it can be invoked directly with a plain HTTP client.

#### Scenario: Invoking the app over HTTP
- **WHEN** a developer sends an HTTP request to the Lambda's Function URL (obtained via `terraform output`)
- **THEN** LocalStack routes the request to the Lambda function and returns its response, with no API Gateway resource involved

### Requirement: Setup and verification are documented with a dual-path fallback
The repository SHALL document the exact commands to bring up the stack (`docker compose up -d`, `terraform apply`) and to verify it, covering two verification paths: the `aws` CLI (`aws --endpoint-url=http://localhost:4566 s3 ls`, noted as requiring `awscli` to be installed) and a CLI-free fallback (`terraform output` / `terraform state list` plus `curl` against the Function URL).

#### Scenario: Verifying with the AWS CLI installed
- **WHEN** a developer with `awscli` installed follows the documented steps
- **THEN** running `aws --endpoint-url=http://localhost:4566 s3 ls` shows the provisioned bucket

#### Scenario: Verifying without the AWS CLI
- **WHEN** a developer without `awscli` installed follows the documented fallback steps
- **THEN** `terraform output` / `terraform state list` shows the provisioned resources, and a `curl` request against the Function URL demonstrates the upload path working end to end
