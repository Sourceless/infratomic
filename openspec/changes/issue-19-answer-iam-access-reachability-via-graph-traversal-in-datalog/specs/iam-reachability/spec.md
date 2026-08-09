## Purpose

Answers "can this IAM principal perform this action on this resource?" as a real, queryable invariant derived by recursive Datalog traversal of the deployed IAM policy graph (identity-based policies, resource-based policies, and role-assumption chains), instead of by manually reading policy JSON.

## ADDED Requirements

### Requirement: Query IAM access reachability
The system SHALL provide an `iam-reachable?`-style function that, given a principal resource identifier (an `aws_iam_role`), a target resource identifier, and an action string (e.g. `"s3:GetObject"`), answers whether the principal can perform that action on that resource, evaluated by parsing the deployed IAM policy documents (identity-based policies attached to the principal, directly or via a managed policy attachment; resource-based policies attached to the target) at query time and traversing the resulting grants as recursive Datalog rules — not as an application-level walk of parsed policy data in Clojure. Matching of actions, resources, and principals against policy statements SHALL support IAM's glob wildcard semantics (`*` and `?`), not exact-match only.

#### Scenario: Access granted by a direct identity-based policy
- **WHEN** `iam-reachable?` is called with a principal that has an identity-based policy (attached inline or via a managed policy) granting the given action on the given resource, and no applicable deny exists
- **THEN** it returns a truthy/reachable result

#### Scenario: Access granted by a resource-based policy
- **WHEN** `iam-reachable?` is called with a principal and a target resource whose resource-based policy grants that principal the given action, and no applicable deny exists
- **THEN** it returns a truthy/reachable result

#### Scenario: No granting policy anywhere
- **WHEN** `iam-reachable?` is called with a principal, target resource, and action for which no identity-based policy, resource-based policy, or role-assumption chain grants access
- **THEN** it returns a falsy/not-reachable result

### Requirement: Role-assumption chains are traversed
The system SHALL traverse role-assumption chains when answering IAM access reachability: a source role can reach a resource through a target role's grant when the target role's trust policy grants `sts:AssumeRole` to the source role (Effect: Allow, Principal naming the source role), the source role's own identity-based policy grants it permission to assume the target role (`sts:AssumeRole` on the target role's resource), and the target role itself is granted the requested action on the resource (directly or, recursively, through a further assumption). This traversal SHALL be expressed as recursive Datalog rules capable of following a chain of more than one assumed role, not a fixed number of hand-unrolled hops.

#### Scenario: Access granted via a multi-hop role-assumption chain
- **WHEN** `iam-reachable?` is called with a source role that has no direct grant on the target resource or action, but can assume a chain of one or more other roles, the last of which is granted the requested action on the resource, and every assumption edge in the chain has both the assuming role's identity-based permission and the assumed role's trust-policy grant, with no applicable deny anywhere in the chain
- **THEN** it returns a truthy/reachable result

#### Scenario: A broken link in the assumption chain blocks access
- **WHEN** `iam-reachable?` is called with a source role whose only path to the requested access is a role-assumption chain, but one edge in that chain is missing either the assuming role's identity-based `sts:AssumeRole` permission or the assumed role's trust-policy grant
- **THEN** it returns a falsy/not-reachable result

### Requirement: Explicit deny overrides an allow, scoped to what it matches
The system SHALL treat an explicit `Deny` statement in any policy along the evaluated path (the principal's identity-based policies, the target's resource-based policy, or any role-assumption edge's trust or identity policy) as overriding an otherwise-applicable `Allow` only when the `Deny` statement itself glob-matches the same action and resource (and, where the statement specifies one, principal) that the `Allow` applies to. A `Deny` that does not match the action/resource in question SHALL NOT block unrelated access.

#### Scenario: A matching explicit deny blocks otherwise-granted access
- **WHEN** `iam-reachable?` is called with a principal, resource, and action where an applicable policy grants an `Allow` for that action/resource, but another policy on the same evaluated path has a `Deny` statement that glob-matches that same action and resource
- **THEN** it returns a falsy/not-reachable result

#### Scenario: A non-matching explicit deny does not block unrelated access
- **WHEN** `iam-reachable?` is called with a principal, resource, and action where an applicable policy grants an `Allow`, and a `Deny` statement exists elsewhere on the evaluated path but for a different action or a different resource
- **THEN** it returns a truthy/reachable result
