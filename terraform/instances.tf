# Workload instances placed across network.tf's subnets/VPCs and
# security_groups.tf's security groups, as `reachable?`'s endpoints. See
# network.tf's topology diagram for the VPC/subnet layout this places
# instances into.
#
# LocalStack Community's EC2 support is metadata-only (no real
# boot/networking behavior), so these only need to be state-consistent, not
# bootable - see design.md's "LocalStack Community aws_instance fidelity"
# risk.

# Same-subnet positive: both in subnet_a1, both reachability_open.
resource "aws_instance" "workload_1" {
  ami                    = "ami-0c55b159cbfafe1f0"
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.subnet_a1.id
  vpc_security_group_ids = [aws_security_group.reachability_open.id, aws_security_group.ssh_open.id]
  tags                   = { Name = "infratomic-test-app-workload-1" }
}

resource "aws_instance" "workload_2" {
  ami                    = "ami-0c55b159cbfafe1f0"
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.subnet_a1.id
  vpc_security_group_ids = [aws_security_group.reachability_open.id]
  tags                   = { Name = "infratomic-test-app-workload-2" }
}

# Same-subnet negative: both in subnet_a1, both reachability_restricted -
# neither's rules permit reaching the other.
resource "aws_instance" "workload_3" {
  ami                    = "ami-0c55b159cbfafe1f0"
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.subnet_a1.id
  vpc_security_group_ids = [aws_security_group.reachability_restricted.id]
  tags                   = { Name = "infratomic-test-app-workload-3" }
}

resource "aws_instance" "workload_4" {
  ami                    = "ami-0c55b159cbfafe1f0"
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.subnet_a1.id
  vpc_security_group_ids = [aws_security_group.reachability_restricted.id, aws_security_group.https_only.id]
  tags                   = { Name = "infratomic-test-app-workload-4" }
}

# Cross-VPC positive: subnet_a2 (vpc_a) -> subnet_b1 (vpc_b), via the
# peering connection and IGW-adjacent route table rt_a.
resource "aws_instance" "workload_5" {
  ami                    = "ami-0c55b159cbfafe1f0"
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.subnet_a2.id
  vpc_security_group_ids = [aws_security_group.reachability_open.id]
  tags                   = { Name = "infratomic-test-app-workload-5" }
}

# Cross-VPC positive target, also reused as the internet-bound and
# route-missing negatives' target/source-side comparisons.
resource "aws_instance" "workload_6" {
  ami                    = "ami-0c55b159cbfafe1f0"
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.subnet_b1.id
  vpc_security_group_ids = [aws_security_group.reachability_open.id]
  tags                   = { Name = "infratomic-test-app-workload-6" }
}

# Cross-VPC negative (no peering connection at all): subnet_a1 (vpc_a) ->
# subnet_c1 (vpc_c) - vpc_c has no peering connection to vpc_a/vpc_b.
resource "aws_instance" "workload_7" {
  ami                    = "ami-0c55b159cbfafe1f0"
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.subnet_a1.id
  vpc_security_group_ids = [aws_security_group.reachability_open.id]
  tags                   = { Name = "infratomic-test-app-workload-7" }
}

resource "aws_instance" "workload_8" {
  ami                    = "ami-0c55b159cbfafe1f0"
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.subnet_c1.id
  vpc_security_group_ids = [aws_security_group.reachability_open.id]
  tags                   = { Name = "infratomic-test-app-workload-8" }
}

# Cross-VPC negative (peering exists, querying side's route missing) and
# internet-bound negative (no route to an internet gateway): subnet_a3's
# route table (rt_a_isolated) has neither the peering route nor the IGW
# route that rt_a has.
resource "aws_instance" "workload_9" {
  ami                    = "ami-0c55b159cbfafe1f0"
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.subnet_a3.id
  vpc_security_group_ids = [aws_security_group.reachability_open.id]
  tags                   = { Name = "infratomic-test-app-workload-9" }
}
