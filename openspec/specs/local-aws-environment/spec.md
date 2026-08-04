# local-aws-environment Specification

## Purpose

Provides a local, no-credentials-required simulation of AWS (via LocalStack Community edition) that Terraform can target, so infrastructure changes can be provisioned and tested without a real AWS account.

## Requirements

### Requirement: LocalStack starts via docker-compose
A `docker-compose.yml` file SHALL exist at the repository root that starts a LocalStack Community edition container exposing the single-port gateway on `localhost:4566`.

#### Scenario: Bringing up the local AWS simulator
- **WHEN** a developer runs `docker compose up -d` (or `docker-compose up -d`) from the repository root
- **THEN** a LocalStack container starts and its gateway becomes reachable at `http://localhost:4566`

### Requirement: Terraform provider targets the local simulator
The Terraform configuration under `terraform/` SHALL configure the AWS provider to send all requests to the LocalStack gateway at `http://localhost:4566`, using path-style S3 addressing and dummy credentials, without requiring real AWS credentials or contacting real AWS endpoints.

#### Scenario: Applying Terraform against LocalStack
- **WHEN** a developer runs `terraform apply` inside `terraform/` while LocalStack is running
- **THEN** Terraform provisions all declared resources against the LocalStack gateway (`http://localhost:4566`) using path-style S3 addressing and dummy `test`/`test` credentials, and no request is made to a real AWS endpoint

#### Scenario: LocalStack is not running
- **WHEN** a developer runs `terraform apply` inside `terraform/` while LocalStack is not running
- **THEN** Terraform fails to connect to `http://localhost:4566` and reports a connection error rather than silently succeeding or falling back to real AWS
