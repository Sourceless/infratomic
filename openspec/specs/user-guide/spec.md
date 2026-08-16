# user-guide Specification

## Purpose

A single operator-facing document that walks through every existing user-facing capability, so someone can run and exercise infratomic end-to-end from a clean checkout without piecing it together from README sections, ADRs, and specs.

## Requirements

### Requirement: A user guide document exists covering every user-facing capability
The repository SHALL contain a single operator-facing user guide document covering: the State Backend (including running it as a container against a Dev-Local Gateway and LocalStack), Policy Check, the CLI, Sync, Drift detection, network reachability, and IAM reachability.

#### Scenario: Each capability is covered
- **WHEN** the user guide is read section by section
- **THEN** it includes a section on the State Backend, Policy Check, the CLI, Sync, Drift detection, network reachability, and IAM reachability, each with enough detail to exercise that capability

### Requirement: The user guide documents running the container against a Dev-Local Gateway
The user guide SHALL document how to run the published State Backend image alongside a Dev-Local Gateway process and LocalStack, including the `bootstrap` command and the ad-hoc query and Rule-registration HTTP endpoints.

#### Scenario: Following the guide to run the container end-to-end
- **WHEN** an operator follows the user guide's container section from a clean checkout
- **THEN** they can bring up the Dev-Local Gateway, LocalStack, and the State Backend container, run `bootstrap` against a fresh database, and successfully call the ad-hoc query endpoint and register a Rule via the Rule-registration endpoint
