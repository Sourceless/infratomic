# policy-check Specification

## Purpose

Lets a Terraform plan be checked against policy rules before anything is created, by speculatively evaluating the plan's resources against the same Datomic-backed rule functions used to query already-deployed infrastructure — without ever persisting the plan or affecting real state.

## Requirements

### Requirement: Policy Check endpoint evaluates a plan against registered rules
The State Backend SHALL expose an HTTP endpoint that accepts a Terraform plan JSON document (the output of `terraform show -json` on a plan file) and responds with the set of Violations produced by evaluating every registered Rule against that plan's resources, without requiring any prior or subsequent call to the `/state` endpoints.

#### Scenario: A plan with no rule violations
- **WHEN** a plan JSON document containing no resources that violate any registered Rule is submitted to the Policy Check endpoint
- **THEN** the response indicates zero Violations

#### Scenario: A plan with a rule violation
- **WHEN** a plan JSON document containing a resource that violates a registered Rule is submitted to the Policy Check endpoint
- **THEN** the response includes a Violation identifying which Rule was violated and which resource (at minimum its type and name/address) violated it

### Requirement: Policy Check evaluation is speculative and non-persistent
Evaluating a plan via the Policy Check endpoint SHALL NOT create, modify, or retract any resource entity or state version visible via `GET /state`, regardless of whether the plan contains violations.

#### Scenario: Checking a plan does not affect real state
- **WHEN** a plan JSON document is submitted to the Policy Check endpoint, whether or not it contains violations
- **THEN** a subsequent `GET /state` returns the same result it would have returned had the Policy Check endpoint never been called

### Requirement: Plan resources are evaluated using the same modeled/generic attribute treatment as posted state
A plan resource's attributes SHALL be evaluated by Rules using the same distinction between modeled (typed, schema-mapped) and generic (key/value) attributes that applies when state is posted via `POST /state`, so a Rule written against deployed resources (e.g. one matching a modeled numeric port) behaves identically when evaluating a planned resource with the same attribute shape.

#### Scenario: A modeled attribute on a planned resource is evaluated the same way as on a posted resource
- **WHEN** a plan resource of a schema-mapped type (e.g. `aws_security_group_rule`) has a modeled attribute (e.g. `to_port`) with the same value as an equivalent already-deployed resource would have
- **THEN** a Rule that matches the deployed resource on that attribute also matches the planned resource

### Requirement: An identifying attribute unknown at plan time resolves to the resource's own address
When a resource's modeled identifying attribute (e.g. `aws_security_group.id`) is not yet known at plan time (absent or `null` in the plan's planned values, because the resource has not yet been created), the Policy Check SHALL treat that attribute as holding the resource's own Terraform address (e.g. `aws_security_group.ssh_open`) instead of being absent, so identity-based Rule joins can still match a not-yet-created resource.

#### Scenario: A new resource's own unknown identifying attribute is stood in for
- **WHEN** a plan resource of a modeled type has its identifying attribute reported as unknown/null because the resource is being newly created
- **THEN** the Policy Check evaluates that resource's identifying attribute as its own Terraform address, rather than treating the attribute as absent

### Requirement: A direct single-reference symbolic dependency resolves to the referenced resource's address
When one resource's attribute value is not yet known at plan time but the plan's configuration block records it as a direct, single-reference expression referencing another resource (e.g. `security_group_id = aws_security_group.foo.id`), the Policy Check SHALL treat that attribute as holding the referenced resource's Terraform address — the same address substituted for that referenced resource's own unknown identifying attribute — so the two sides of an identity-based Rule join match. Conditional or interpolated reference expressions are not resolved this way and remain absent.

#### Scenario: A rule's join between two new resources still matches
- **WHEN** a plan contains two new resources where one's attribute directly references the other's identifying attribute, and both attributes are unknown at plan time
- **THEN** the Policy Check evaluates both attributes as the same Terraform address, allowing an identity-based Rule join between them to match

#### Scenario: A conditional or interpolated reference is not resolved
- **WHEN** a plan resource's attribute expression is conditional or interpolated rather than a direct single reference to another resource
- **THEN** the Policy Check does not substitute an address for that attribute, leaving it absent as reported by the plan

### Requirement: The port-22-open-to-the-internet rule is registered
The Policy Check's registered Rules SHALL include a rule equivalent to the existing security-groups-with-port-22-open query: it flags any `aws_security_group` resource with an associated `aws_security_group_rule` permitting ingress on port 22 from `0.0.0.0/0`, including for a security group and rule that are both being newly created in the plan (not only already-deployed ones).

#### Scenario: A newly planned insecure security group is flagged
- **WHEN** a plan creates an `aws_security_group` together with an `aws_security_group_rule` permitting ingress on port 22 from `0.0.0.0/0`, neither of which exists yet
- **THEN** the Policy Check response includes a Violation for that security group

#### Scenario: A newly planned secure security group is not flagged
- **WHEN** a plan creates an `aws_security_group` with no rule permitting port 22 ingress from `0.0.0.0/0`
- **THEN** the Policy Check response does not include a Violation for that security group

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
