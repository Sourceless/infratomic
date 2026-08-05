# local-datomic-database Specification

## Purpose

Provides a local, file-backed Datomic database that developers can connect
to, create, transact against, and query, so app code can be built and
tested without any shared or remote Datomic infrastructure.

## Requirements

### Requirement: Datomic local dependency is declared
The repo SHALL contain a `deps.edn` at its root declaring `com.datomic/local` version `1.0.301` as a dependency, resolvable from Maven Central with no credentials.

#### Scenario: Dependency resolves without credentials
- **WHEN** a contributor runs `clj -P` (or equivalent dependency resolution) from the repo root with no Datomic-related credentials or extra Maven repositories configured
- **THEN** `com.datomic/local` version `1.0.301` resolves successfully

### Requirement: Storage location is repo-local and gitignored
The dev-local storage directory and system name SHALL be configured as a repo-local path, passed explicitly in code rather than relying on `~/.datomic/local.edn`, and the storage directory SHALL be excluded from version control.

#### Scenario: Storage directory is git-ignored
- **WHEN** the verification test creates a database, causing the storage directory to be populated on disk
- **THEN** `git status` reports no untracked or modified files under that storage directory

#### Scenario: No out-of-repo config required
- **WHEN** a contributor clones the repo fresh, with no pre-existing `~/.datomic/local.edn` and no prior Datomic Local setup on the machine
- **THEN** the storage directory and system name used to create and connect to the database are still fully determined by files in the repo

### Requirement: Code creates a database and connects to it
The repo SHALL contain code that, given the configured storage directory and system name, creates a local Datomic database (if it does not already exist) and returns a connection to it.

#### Scenario: Connection is obtained
- **WHEN** the connection-setup code is invoked against a fresh (or existing) repo-local storage directory
- **THEN** it returns a usable Datomic client connection to a database in that storage directory, creating the database first if needed

### Requirement: Automated verification exercises the full round-trip
The repo SHALL contain an automated test that, using a minimal inline fixture schema, transacts a sample fact, queries it back, and asserts the expected result, without requiring any manual setup beyond cloning the repo and running the test command.

#### Scenario: Round-trip test passes on a fresh clone
- **WHEN** a contributor clones the repo and runs `clj -M:test` (or the documented equivalent) with no manual setup beyond that
- **THEN** the test connects to a local database, transacts a minimal inline fixture schema attribute, transacts a fact using it, queries the fact back, and the assertion passes

#### Scenario: Fixture schema is inline and minimal
- **WHEN** a contributor inspects the verification test
- **THEN** the fixture schema it transacts is defined inline in the test itself (not in a separate schema namespace or file) and consists of the minimal attribute(s) needed to exercise the round-trip
