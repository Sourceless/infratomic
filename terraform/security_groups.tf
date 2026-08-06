# Security groups exercising the State Backend's query namespace against
# real, decomposed `aws_security_group`/`aws_security_group_rule` entities
# (see openspec/specs/resource-query and openspec/specs/security-groups).
# Ingress/egress rules are declared as separate `aws_security_group_rule`
# resources (not inline `ingress`/`egress` blocks) so each rule is its own
# independently-queryable resource, referencing its security group only via
# `security_group_id`.

# Insecure: permits SSH (port 22) ingress from anywhere.
resource "aws_security_group" "ssh_open" {
  name        = "infratomic-test-app-ssh-open"
  description = "Insecure example: allows SSH from the whole internet"
}

resource "aws_security_group_rule" "ssh_open_ingress" {
  type              = "ingress"
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.ssh_open.id
}

# Secure: permits HTTPS ingress from anywhere, but no port 22 access at all.
resource "aws_security_group" "https_only" {
  name        = "infratomic-test-app-https-only"
  description = "Secure example: allows HTTPS only, no SSH access"
}

resource "aws_security_group_rule" "https_only_ingress" {
  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.https_only.id
}
