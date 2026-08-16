# ad-hoc-query Specification

## Purpose

An HTTP endpoint that runs a caller-supplied Datalog query against the live db, so an operator can inspect state without a purpose-built endpoint existing for every question — bounded by the same shared validator that keeps runtime-registered Policy Check Rules safe.

## Requirements

### Requirement: An HTTP endpoint runs a caller-supplied Datalog query against the live db
The State Backend SHALL expose an HTTP endpoint that accepts a Datalog query (a `:find`/`:in`/`:where` map, optionally with a `%` rule-set argument and accompanying rule-defs) in the request body, runs it against the current db, and returns the raw query result set.

#### Scenario: A valid ad-hoc query returns results
- **WHEN** a caller submits a well-formed Datalog query with no disallowed function-invocation clauses to the ad-hoc query endpoint
- **THEN** the response contains the query's result set, run against the live db

#### Scenario: A query with a recursive rule set is accepted
- **WHEN** a caller submits a query whose `:in` includes `%` alongside a rule-set definition containing a self-referential rule
- **THEN** the query is evaluated using that rule set and the response contains the result set

### Requirement: Ad-hoc queries are validated through the shared query/rule validator
Every query submitted to the ad-hoc query endpoint SHALL be checked by the same shared validator used for Rule registration before it is run. A query whose `:where` clause or rule-defs contains a function-invocation clause outside the validator's allowlist SHALL be rejected without being run.

#### Scenario: A query with a disallowed function-invocation clause is rejected
- **WHEN** a caller submits a query whose `:where` clause contains a function-invocation clause not in the validator's allowlist
- **THEN** the endpoint responds with an error and the query is not run against the db

#### Scenario: A query using only allowlisted predicates is run
- **WHEN** a caller submits a query whose `:where` clause contains only function-invocation clauses using allowlisted built-in predicates (e.g. `<`, `>=`, `=`)
- **THEN** the query is run and its results are returned
