## Purpose

A separately-run, network-reachable process that wraps a real dev-local Datomic database behind a wire protocol mirroring Datomic Pro/Cloud client-api's conceptual shape, so the State Backend can be genuinely network-separated from its database without depending on the licensed, credential-gated `com.datomic/client-pro`.

## ADDED Requirements

### Requirement: The Dev-Local Gateway is an independently runnable process
The Dev-Local Gateway SHALL run as its own process, separate from the State Backend, reachable over the network at a configurable host and port.

#### Scenario: Gateway runs independently of the State Backend
- **WHEN** the Dev-Local Gateway is started
- **THEN** it listens for connections on its configured host and port, independent of whether any State Backend process is running

### Requirement: The Dev-Local Gateway requires no licensed Datomic dependency or credentials
The Dev-Local Gateway SHALL depend only on `com.datomic/local`. No component of the Dev-Local Gateway — its build, its CI, or its runtime — SHALL require `com.datomic/client-pro` or any my.datomic.com credential.

#### Scenario: Gateway builds and runs with no Datomic Pro credentials present
- **WHEN** the Dev-Local Gateway is built and started in an environment with no my.datomic.com account or download key configured anywhere
- **THEN** the build and startup succeed

### Requirement: The Dev-Local Gateway exposes client-api-shaped operations over opaque handles
The Dev-Local Gateway SHALL expose the Datomic client-api operations needed to create a database, connect to it, and run queries, transactions, and speculative (`with`) evaluations, using opaque handles to represent client/connection/db values across the network boundary rather than requiring the caller to hold or reconstruct the underlying dev-local objects itself.

#### Scenario: A caller obtains and reuses an opaque connection handle
- **WHEN** a caller connects to a database via the Dev-Local Gateway
- **THEN** it receives an opaque handle usable in subsequent requests (queries, transactions, db-value derivation) without needing any other representation of the connection

#### Scenario: A query executes against a handle-referenced db value
- **WHEN** a caller submits a Datalog query referencing a previously obtained db-value handle
- **THEN** the Dev-Local Gateway resolves the handle to the corresponding real database value and returns the query's results

### Requirement: The Dev-Local Gateway persists to a real dev-local storage backend
The Dev-Local Gateway SHALL store all data in a real dev-local Datomic database backed by a configured storage directory, so data persists across State Backend restarts as long as the Dev-Local Gateway process and its storage remain.

#### Scenario: Data persists across a State Backend restart
- **WHEN** the State Backend writes data via a Dev-Local Gateway connection, then the State Backend process is restarted (the Dev-Local Gateway process is not)
- **THEN** the State Backend, once reconnected, can read the previously written data
