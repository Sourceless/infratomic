# Security groups exercising the State Backend's query namespace against
# real, decomposed `aws_security_group`/`aws_security_group_rule` entities
# (see openspec/specs/resource-query and openspec/specs/security-groups).
# Ingress/egress rules are declared as separate `aws_security_group_rule`
# resources (not inline `ingress`/`egress` blocks) so each rule is its own
# independently-queryable resource, referencing its security group only via
# `security_group_id`.

# Insecure: permits SSH (port 22) ingress from anywhere. Placed in vpc_a
# (see network.tf) and reused by the network reachability workload fixtures
# (see instances.tf), per the network-topology capability's requirement
# that these pre-existing security groups belong to a provisioned VPC.
resource "aws_security_group" "ssh_open" {
  name        = "infratomic-test-app-ssh-open"
  description = "Insecure example: allows SSH from the whole internet"
  vpc_id      = aws_vpc.vpc_a.id
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
# Placed in vpc_a and reused by the network reachability workload fixtures,
# same as ssh_open above.
resource "aws_security_group" "https_only" {
  name        = "infratomic-test-app-https-only"
  description = "Secure example: allows HTTPS only, no SSH access"
  vpc_id      = aws_vpc.vpc_a.id
}

resource "aws_security_group_rule" "https_only_ingress" {
  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.https_only.id
}

# Open: permits all traffic in both directions, from/to anywhere. Used by
# the network reachability workload fixtures (instances.tf) as the "SG
# permits" side of the same-subnet, cross-VPC, and internet-bound positive
# scenarios.
resource "aws_security_group" "reachability_open" {
  name        = "infratomic-test-app-reachability-open"
  description = "Reachability fixture: allows all traffic, both directions"
  vpc_id      = aws_vpc.vpc_a.id
}

resource "aws_security_group_rule" "reachability_open_egress" {
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.reachability_open.id
}

resource "aws_security_group_rule" "reachability_open_ingress" {
  type              = "ingress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.reachability_open.id
}

# Restricted: only permits traffic to/from an unrelated CIDR range, so it
# neither reaches nor is reachable by the other workload fixtures. Used by
# the network reachability workload fixtures as the "SG blocks" negative
# case for same-subnet reachability.
resource "aws_security_group" "reachability_restricted" {
  name        = "infratomic-test-app-reachability-restricted"
  description = "Reachability fixture: allows traffic only to/from an unrelated CIDR"
  vpc_id      = aws_vpc.vpc_a.id
}

resource "aws_security_group_rule" "reachability_restricted_egress" {
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["192.168.99.0/24"]
  security_group_id = aws_security_group.reachability_restricted.id
}
