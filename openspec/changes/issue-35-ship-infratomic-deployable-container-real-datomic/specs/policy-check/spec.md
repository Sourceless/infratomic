## ADDED Requirements

### Requirement: Rules are stored as data in a single representation
Every Policy Check Rule — whether defined in code at startup or registered at runtime — SHALL be represented as one stored-Datalog-query data shape: a `:find` clause binding exactly one variable (the entity/id the Rule flags), an `:in` clause, a `:where` clause, and an optional rule-set (`%`) definition for recursive rules. No Rule SHALL be represented as an arbitrary application function.

#### Scenario: The existing code-defined Rule is expressed in the stored format
- **WHEN** the State Backend starts up
- **THEN** the port-22-open-to-the-internet Rule registered by default is represented in the same stored-Datalog-query data shape as any runtime-registered Rule, not as a Clojure function

### Requirement: Rules are held in a single, mutable registry
The State Backend SHALL hold all registered Rules in one registry that can be updated at runtime, rather than a fixed set determined only at process start.

#### Scenario: A Rule registered after startup is available to the next Policy Check
- **WHEN** a Rule is registered after the State Backend has already started, and a Policy Check is subsequently run
- **THEN** the Policy Check evaluates the plan against that Rule, alongside every other registered Rule, without requiring a process restart

### Requirement: New Rules can be registered at runtime via HTTP
The State Backend SHALL expose an HTTP endpoint that accepts a Rule in the stored-Datalog-query data shape and adds it to the registry, keyed by a unique Rule id. Registering a Rule under an id already present in the registry SHALL replace the existing Rule under that id.

#### Scenario: Registering a new Rule
- **WHEN** a valid Rule with an id not already present in the registry is submitted to the Rule-registration endpoint
- **THEN** the response indicates success and the Rule is added to the registry

#### Scenario: Registering a Rule under an existing id replaces it
- **WHEN** a valid Rule is submitted to the Rule-registration endpoint under an id that already exists in the registry
- **THEN** the previously registered Rule under that id is replaced, not duplicated

### Requirement: Registered Rules support recursive rule sets
A Rule's stored representation SHALL support an optional recursive Datomic rule set (`:in $ %` with an accompanying rule-set definition), so a Rule can express traversal-style logic, not only flat `:find`/`:where` queries.

#### Scenario: A Rule using a recursive rule set is registered and evaluated
- **WHEN** a Rule is registered whose `:in` includes `%` and whose rule-set definition contains a self-referential recursive rule
- **THEN** the Rule is accepted, and a subsequent Policy Check evaluates it correctly against the plan's speculative db

### Requirement: Rules have no arbitrary-function escape hatch
A Rule's `:where` clause and any rule-set definition it carries SHALL NOT be able to invoke an arbitrary application function. Registration SHALL be rejected if the submitted Rule fails the shared query/rule validator.

#### Scenario: A Rule containing a disallowed function-invocation clause is rejected
- **WHEN** a Rule whose `:where` clause (or rule-set definition) contains a function-invocation clause not in the validator's allowlist is submitted to the Rule-registration endpoint
- **THEN** the registration is rejected and the Rule is not added to the registry

#### Scenario: A Rule using only allowlisted predicates is accepted
- **WHEN** a Rule whose `:where` clause contains only function-invocation clauses using allowlisted built-in predicates (e.g. `<`, `>=`) is submitted to the Rule-registration endpoint
- **THEN** the registration succeeds
