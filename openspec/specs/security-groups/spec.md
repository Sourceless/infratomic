# security-groups Specification

## Purpose

Provisions security group resources in the sample Terraform app — one insecure and at least one that isn't — so the State Backend's query namespace has a real, security-relevant question it can answer against actually-deployed infrastructure.

## Requirements

### Requirement: Ingress/egress rules are separate resources
The sample app's security group ingress/egress rules SHALL be declared as separate `aws_security_group_rule` resources referencing their security group via `security_group_id`, rather than as inline `ingress`/`egress` blocks on `aws_security_group`.

#### Scenario: Rules are independently tracked resources
- **WHEN** the sample app is applied
- **THEN** `terraform state list` includes `aws_security_group_rule` entries distinct from their owning `aws_security_group` entries, each referencing its security group's id

### Requirement: An insecure security group is provisioned
The sample app SHALL provision an `aws_security_group` with an associated `aws_security_group_rule` permitting ingress on port 22 from `0.0.0.0/0`.

#### Scenario: Insecure security group exists after apply
- **WHEN** `terraform apply` completes successfully
- **THEN** a security group exists whose rules permit SSH (port 22) ingress from `0.0.0.0/0`

### Requirement: A secure security group is provisioned
The sample app SHALL provision at least one additional `aws_security_group` with no rule permitting ingress on port 22 from `0.0.0.0/0`.

#### Scenario: Secure security group exists after apply
- **WHEN** `terraform apply` completes successfully
- **THEN** at least one security group exists, distinct from the insecure one, whose rules do not permit port 22 ingress from `0.0.0.0/0`
