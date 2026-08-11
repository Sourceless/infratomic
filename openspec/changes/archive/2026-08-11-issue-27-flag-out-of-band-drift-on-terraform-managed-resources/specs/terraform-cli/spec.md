## ADDED Requirements

### Requirement: CLI drift-check subcommand reports out-of-band drift
The CLI SHALL provide a `drift-check` subcommand that, when invoked, requests the State Backend's `GET /drift` endpoint and prints a human-readable summary of any managed resources flagged as drifted (or reports an error if the request could not be completed). The subcommand SHALL exit non-zero both when the request fails or returns a malformed response, and when at least one resource is flagged as drifted. It SHALL exit `0` only when the request succeeds and no resource is flagged as drifted.

#### Scenario: Running drift-check with no drift present
- **WHEN** the CLI is invoked with `drift-check` while the State Backend is running and no managed resource currently has out-of-band drift
- **THEN** the CLI prints a summary indicating no drift and exits `0`

#### Scenario: Running drift-check with drift present
- **WHEN** the CLI is invoked with `drift-check` while the State Backend is running and at least one managed resource currently has out-of-band drift
- **THEN** the CLI prints a summary listing the drifted resource(s) and exits non-zero

#### Scenario: The drift-check request fails
- **WHEN** the CLI is invoked with `drift-check` and the request to the State Backend's `GET /drift` endpoint fails or returns an unexpected response shape
- **THEN** the CLI reports the failure and exits with a non-zero status
