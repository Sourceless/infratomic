## Purpose

Lets the State Backend run `terraform apply`/`import`/`destroy` unattended against a caller-supplied Terraform working directory, capturing success/failure and recording every invocation, so a future reconciliation engine can execute remediation without a human at the CLI.

## ADDED Requirements

### Requirement: Unattended apply against a caller-supplied working directory
The State Backend SHALL provide a capability that runs `terraform apply` non-interactively against a caller-supplied working directory (not a fixed, hardcoded path) and returns whether it succeeded, without requiring any human interaction with the `terraform` process.

#### Scenario: Applying a valid configuration succeeds
- **WHEN** the capability is invoked to apply a working directory containing a valid Terraform configuration and reachable backend/provider (e.g. LocalStack)
- **THEN** the real `terraform apply` runs to completion non-interactively, and the capability reports success

#### Scenario: Applying a failing configuration reports failure
- **WHEN** the capability is invoked to apply a working directory whose Terraform run fails (e.g. a provider error)
- **THEN** the capability reports failure rather than raising an unhandled exception or reporting success

### Requirement: Unattended import for a resource address and AWS id
The State Backend SHALL provide a capability that runs `terraform import <address> <id>` non-interactively against a caller-supplied working directory, for a resource address that already has a corresponding resource block in that directory's configuration. It SHALL NOT write or modify Terraform configuration to create a resource block for the target address — synthesizing configuration for a previously-unmanaged resource is a separate concern.

#### Scenario: Importing a resource with a pre-existing config block succeeds
- **WHEN** the capability is invoked with a resource address that already has a resource block declared in the working directory's configuration, and a matching AWS resource id
- **THEN** the real `terraform import` runs non-interactively, binding that AWS resource to the declared address, and the capability reports success

#### Scenario: Importing an address with no config block fails without side effects
- **WHEN** the capability is invoked with a resource address that has no corresponding resource block in the working directory's configuration
- **THEN** the capability reports failure (surfacing Terraform's own error) rather than writing any configuration to create the missing block

### Requirement: Unattended targeted destroy for a resource address
The State Backend SHALL provide a capability that runs `terraform destroy -target=<address>` non-interactively against a caller-supplied working directory for a single resource address, without prompting for approval and without destroying resources other than the targeted address (and any resources Terraform's own dependency graph requires to be destroyed alongside it).

#### Scenario: Destroying a targeted resource succeeds
- **WHEN** the capability is invoked to destroy a specific resource address that exists in the working directory's state
- **THEN** the real `terraform destroy -target` runs non-interactively against only that address, and the capability reports success

#### Scenario: Destroying a nonexistent address reports failure
- **WHEN** the capability is invoked to destroy a resource address not present in the working directory's state
- **THEN** the capability reports failure rather than silently succeeding

### Requirement: Every invocation is recorded with command, resource, and outcome
Every call to the apply, import, or destroy capability SHALL be recorded as a persisted entity capturing which command was run (apply/import/destroy), the resource address it targeted, and its outcome (success or failure) — written unconditionally by the capability itself, regardless of whether the caller does anything with the returned result.

#### Scenario: A successful invocation is recorded
- **WHEN** the apply, import, or destroy capability is invoked and completes successfully
- **THEN** a persisted record exists identifying the command that ran, the resource address it targeted, and that it succeeded

#### Scenario: A failed invocation is recorded
- **WHEN** the apply, import, or destroy capability is invoked and fails
- **THEN** a persisted record exists identifying the command that ran, the resource address it targeted, and that it failed

### Requirement: Invocation outcome is returned to the caller
The apply, import, and destroy capabilities SHALL each return whether the invocation succeeded, distinct from any captured process output, so a caller can branch on outcome without interpreting Terraform's own exit-code conventions.

#### Scenario: Caller receives a success/failure result
- **WHEN** any of the apply, import, or destroy capabilities completes (successfully or not)
- **THEN** the caller receives a result that unambiguously indicates success or failure, independent of parsing captured stdout/stderr

### Requirement: Concurrent invocations on the same resource address are serialized
Before running apply, import, or destroy against a resource address, the State Backend SHALL acquire a lock scoped to that address, and SHALL NOT begin a second invocation targeting the same address while a lock for it is held. Acquiring a lock SHALL be atomic (no window in which two concurrent invocations can both observe no lock and both proceed). The lock SHALL be released when the invocation completes, and SHALL survive a State Backend process restart (i.e. it is not merely an in-process mutex).

#### Scenario: A second invocation on the same address waits or is rejected
- **WHEN** an invocation is already running against a given resource address and a second invocation targeting the same address is requested before the first completes
- **THEN** the second invocation does not begin running `terraform` against that address until the first has released its lock

#### Scenario: Invocations on different resource addresses proceed independently
- **WHEN** two invocations target different resource addresses at the same time
- **THEN** both may run concurrently, neither blocked by the other's lock

### Requirement: A stale lock expires without manual intervention
A lock held longer than a fixed, generous threshold SHALL be treated as stale (its holder presumed crashed) and become reacquirable by a subsequent invocation, without requiring a human to clear it.

#### Scenario: A lock outlives its holder
- **WHEN** a lock on a resource address has been held longer than the staleness threshold
- **THEN** a new invocation targeting that address can acquire the lock and proceed, rather than being blocked indefinitely

### Requirement: The deployed container image can run terraform unattended
The State Backend's published container image SHALL include a `terraform` binary, so the apply/import/destroy capabilities function when the State Backend runs from that image, not only in a local development or test environment.

#### Scenario: The container image has terraform available
- **WHEN** the State Backend's published container image is run
- **THEN** a `terraform` binary is present and invocable within it, without requiring an operator to install it separately
