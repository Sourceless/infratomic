## ADDED Requirements

### Requirement: CLI sync subcommand triggers the State Backend's Sync
The CLI SHALL provide a `sync` subcommand that, when invoked, sends a request to the State Backend's Sync capability and prints a human-readable summary of the resources it discovered and ingested (or reports an error if the request could not be completed).

#### Scenario: Running the sync subcommand
- **WHEN** the CLI is invoked with `sync` while the State Backend is running
- **THEN** the CLI triggers a Sync against the State Backend and prints a summary of the resources discovered and ingested

#### Scenario: The Sync request fails
- **WHEN** the CLI is invoked with `sync` and the request to the State Backend's Sync capability fails or returns an error
- **THEN** the CLI reports the failure and exits with a non-zero status, rather than silently reporting success
