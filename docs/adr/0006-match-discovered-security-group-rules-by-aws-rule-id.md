# Match Discovered security group rules by AWS `SecurityGroupRuleId`, not an attribute tuple

`aws_security_group_rule` has no modeled `"id"` attribute today — Terraform itself
doesn't expose a stable per-rule id, so Sync's discovery of security group rules
had no schema home for "the AWS id" the issue's acceptance criteria require
matching on. The real alternative was matching on the tuple of
`security_group_id` + `from_port`/`to_port` + `protocol` + `cidr_blocks`/
`source_security_group_id` — fuzzier, and prone to misfiring on two distinct
rules that happen to share the same shape.

We instead call EC2's `DescribeSecurityGroupRules` (distinct from the embedded
`IpPermissions` on `DescribeSecurityGroups`) and add a modeled
`:aws-security-group-rule/id` attribute holding AWS's own `SecurityGroupRuleId`
(not Terraform's synthetic `aws_security_group_rule` resource id — a different
identifier space). It's captured once, on first discovery, and reused as the
match key on every later Sync, so re-running Sync updates the existing
Discovered Resource rather than duplicating it.

Trade-off accepted: LocalStack's `DescribeSecurityGroupRules` has known
field-completeness gaps on other fields (e.g. missing `Description`), but the
id field itself is the thing Sync depends on being present and stable.
