# Sync covers every modeled resource type, not just security groups and rules

Issue #26's acceptance criteria list "security groups, rules, instances, VPCs,
subnets, etc." without pinning down whether that's the full modeled surface
or just enough to make the port-22 verification steps pass. We decided Sync
must ingest all resource types present in `resource-schema` (db.clj) — all
9 types with a modeled `"id"` (`aws_security_group`, `aws_vpc`, `aws_subnet`,
`aws_route_table`, `aws_route`, `aws_route_table_association`,
`aws_internet_gateway`, `aws_vpc_peering_connection`, `aws_instance`), plus
`aws_security_group_rule` per ADR 0006 — not only the two types exercised by
the acceptance criteria's "how to verify" script.

Trade-off accepted: Sync needs an AWS-API-shape -> Terraform-attribute-shape
translation for every one of `resource-schema`'s types, not just the two used
in the verification walkthrough — more upfront translation work, but avoids
shipping a sync command that silently only covers a subset of what future
Rules might need to see live.
