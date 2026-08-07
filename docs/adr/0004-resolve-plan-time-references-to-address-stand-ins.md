# Resolve plan-time symbolic references to address stand-ins so policy rules still match unapplied resources

`security-groups-with-port-22-open` (query.clj) joins `:aws-security-group/id`
against `:aws-security-group-rule/security-group-id` by Datalog value
equality — both AWS-assigned strings. For a security group being *created*
(issue #16's actual verification scenario), neither value exists yet at
`terraform plan` time: `planned_values` reports both as `null`, and
`decompose-attributes` already skips `nil` values, so no datom for either
side would exist in the Policy Check's speculative db. Reusing the rule
unmodified would silently fail to catch the exact scenario the issue asks to
verify.

We considered weakening the verification scenario to only cover edits to an
already-applied security group (whose `id` is already known), or leaving
newly-created resources unpoliced entirely. We rejected both: they give up
policy-checking a brand-new bad resource, which is the more valuable and
more common case a `terraform apply` gate exists to catch.

Instead, the plan-decomposition glue code resolves plan-time identity via
Terraform's own resource address rather than AWS-assigned ids: when a
modeled identifying attribute is unknown, it substitutes the resource's own
address (e.g. `"aws_security_group.ssh_open"`); when another resource's
attribute is unknown but the plan JSON's `configuration.root_module
.resources[].expressions.<key>.references` names a direct, single-reference
dependency on it, it substitutes that referenced resource's address too.
Both sides of the join then hold the same address string, so the existing
rule matches with zero changes to `query.clj` — only the plan-side glue code
is new. This only resolves direct single-reference expressions (e.g.
`security_group_id = aws_security_group.foo.id`); conditional or
interpolated expressions are out of scope.
