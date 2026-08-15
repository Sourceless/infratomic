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

## Update (issue #32 PR #36 round-2 review): the rejected tuple, added back as a fallback for a narrower purpose

Generalizing drift detection to `aws_security_group_rule` (issue #32)
surfaced a distinct problem this ADR's `SecurityGroupRuleId` decision
doesn't solve: matching a rule *Terraform itself declares and manages*.
`resource-schema`'s `"id"` entry is written by two different write paths
sharing one modeled ident - `POST /state` stores Terraform's own synthetic
rule id (e.g. `"sgrule-<n>"`, a client-side provider hash), Sync stores
AWS's real `SecurityGroupRuleId` (this ADR's decision) - so `existing-match`
could never find a Terraform-managed rule's own stored entity by id at all,
producing permanent duplicate-and-misdetected drift (`aws_route` had the
same problem, for the same "no shared id space" reason, `route->attrs`'s
own synthesized id).

We add the tuple `(security_group_id, type, protocol, from_port, to_port,
cidr_blocks/source_security_group_id)` back (`db/resource-composite-key`)
as a *fallback*, used only when `id-based-match` (this ADR's decision,
tried first) finds nothing - i.e. only reachable when the observed rule
turns out to be a Terraform-managed instance's real live value, never as
the primary path for identifying a freshly-Discovered rule. This is
deliberately narrower than what this ADR originally rejected the tuple
for: the collision risk named above is about *two distinct Discovered
rules* sharing a shape being merged into one - a real risk when the tuple
is the *only* signal available for telling any two rules apart. Used here,
`id-based-match` already keeps two distinct Discovered rules separate (this
ADR's decision, unchanged); the composite key only ever has to
distinguish *whether a specific already-Terraform-declared rule's live
value is unchanged or has drifted*, a categorically narrower question with
no equivalent collision case (AWS itself refuses to create two rules with
an identical shape on the same security group - confirmed empirically
against LocalStack, `AuthorizeSecurityGroupIngress`/`Egress` reject an
exact-shape duplicate with `InvalidPermission.Duplicate` - so the tuple is
already unique per security group in practice for this narrower use).

`sync.clj`'s `existing-match` encodes this precedence explicitly for both
`aws_security_group_rule` and `aws_route`: `id-based-match` first
(preserving this ADR's decision undisturbed for the Discovered-rule case),
falling back to `composite-match` only if that finds nothing.
