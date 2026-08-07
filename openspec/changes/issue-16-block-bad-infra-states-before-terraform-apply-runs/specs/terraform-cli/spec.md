## Purpose

Gives an infra operator a drop-in replacement for the `terraform` command that blocks `apply` when the plan it's about to apply violates a policy rule, so a non-compliant resource never reaches AWS.

## ADDED Requirements

### Requirement: Non-apply subcommands pass through unchanged
For every Terraform subcommand other than `apply`, the CLI SHALL invoke the real `terraform` binary with the same arguments it was given, and SHALL pass through that invocation's stdout, stderr, and exit code unchanged.

#### Scenario: Running a passthrough subcommand
- **WHEN** the CLI is invoked with a subcommand other than `apply` (e.g. `terraform plan`, `terraform init`, `terraform state list`)
- **THEN** the real `terraform` binary runs with the same arguments, and its output and exit code are what the CLI produces, unmodified

### Requirement: apply is gated on a Policy Check
When the CLI is invoked with the `apply` subcommand, it SHALL first run `terraform plan -out=tfplan`, then `terraform show -json` on the resulting plan file, then submit that plan JSON to the State Backend's Policy Check endpoint, before ever invoking real `terraform apply`.

#### Scenario: Invoking apply triggers a plan and a policy check
- **WHEN** the CLI is invoked with `apply`
- **THEN** it produces a plan file, converts it to JSON via `terraform show -json`, and submits that JSON to the Policy Check endpoint before any real apply runs

### Requirement: A policy violation blocks apply
If the Policy Check reports one or more Violations for the plan, the CLI SHALL NOT invoke real `terraform apply`, SHALL print each Violation naming its violating resource, and SHALL exit with a non-zero status.

#### Scenario: A violating plan is blocked
- **WHEN** the CLI runs `apply` against a plan that the Policy Check reports one or more Violations for (e.g. a security group open to `0.0.0.0/0` on port 22)
- **THEN** the CLI exits non-zero, prints the violating resource(s), and never invokes real `terraform apply`, so nothing is created

### Requirement: A clean plan proceeds to real apply
If the Policy Check reports zero Violations for the plan, the CLI SHALL invoke real `terraform apply tfplan` and pass through its exit code and output unchanged.

#### Scenario: A clean plan applies normally
- **WHEN** the CLI runs `apply` against a plan the Policy Check reports zero Violations for
- **THEN** the CLI invokes real `terraform apply tfplan`, and its exit code and output are what the CLI produces, unmodified
