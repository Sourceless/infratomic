## ADDED Requirements

### Requirement: An environment variable selects the Datomic connection mode
The State Backend SHALL read an `INFRATOMIC_DATOMIC_MODE` environment variable at startup to select how it connects to Datomic: `embedded` (an in-process dev-local database, today's behavior) or `gateway` (a network connection to a separately-running Dev-Local Gateway process). When the variable is unset, the State Backend SHALL behave exactly as it does today (`embedded` mode).

#### Scenario: Default startup behavior is unchanged
- **WHEN** the State Backend starts with `INFRATOMIC_DATOMIC_MODE` unset
- **THEN** it connects to an in-process embedded dev-local database, identical to its behavior before this environment variable existed

#### Scenario: Gateway mode connects over the network
- **WHEN** the State Backend starts with `INFRATOMIC_DATOMIC_MODE=gateway` and the host/port of a running Dev-Local Gateway configured
- **THEN** it connects to Datomic via that Dev-Local Gateway over the network rather than opening an in-process embedded database

#### Scenario: Existing hermetic tests are unaffected
- **WHEN** the existing test suite runs, which builds its own isolated in-memory dev-local db directly rather than going through `INFRATOMIC_DATOMIC_MODE`
- **THEN** it passes unchanged

### Requirement: Gateway mode is configured via environment variables
When `INFRATOMIC_DATOMIC_MODE=gateway`, the State Backend SHALL read the Dev-Local Gateway's host and port from environment variables, rather than requiring a code change or hardcoded value.

#### Scenario: Gateway host and port are read from the environment
- **WHEN** the State Backend starts in `gateway` mode with the Dev-Local Gateway's host and port set via environment variables
- **THEN** it connects to the Dev-Local Gateway at that host and port

### Requirement: An explicit bootstrap entrypoint installs schema into a fresh database and exits
The State Backend SHALL support an explicit `bootstrap` command/entrypoint argument that installs schema into the connected database (via the same idempotent database-creation-and-schema logic the normal startup path already runs implicitly on every process start) and then exits, without starting the HTTP server. The implicit bootstrap performed by normal startup SHALL remain unchanged.

#### Scenario: Running bootstrap against a fresh database
- **WHEN** the State Backend is invoked with the `bootstrap` command/argument against a fresh database with no schema installed
- **THEN** the database is created and schema is installed, the process exits without starting the HTTP server, and a subsequent normal startup against that same database finds the schema already present

#### Scenario: Running bootstrap against an already-bootstrapped database is safe
- **WHEN** the State Backend is invoked with the `bootstrap` command/argument against a database that already has schema installed
- **THEN** the command completes successfully with no error, and the database's existing data is unaffected

#### Scenario: Normal startup still bootstraps implicitly
- **WHEN** the State Backend is started normally (no `bootstrap` argument) against a fresh database
- **THEN** schema is installed automatically before the server starts serving requests, exactly as before the `bootstrap` command existed
