# Network topology exercising the State Backend's `reachable?` graph
# traversal (see openspec/specs/network-topology and resource-query). Three
# VPCs, subnets spread across them, a route table wiring local/IGW/peering
# routes to subnets, an internet gateway, and a VPC peering connection - the
# minimal real graph needed to demonstrate same-subnet, cross-VPC (via
# peering), and internet-bound reachability, each with a paired negative
# fixture:
#
# vpc_a (10.0.0.0/16)                   vpc_b (10.1.0.0/16)   vpc_c (10.2.0.0/16)
#   subnet_a1 (rt_a: IGW + peering)       subnet_b1 (rt_b)      subnet_c1 (rt_c)
#   subnet_a2 (rt_a: IGW + peering)
#   subnet_a3 (rt_a_isolated: no IGW, no peering route)
#
# pcx_ab peers vpc_a <-> vpc_b, so vpc_c (with no peering connection at all)
# is the "cross-VPC, no peering" negative fixture, and rt_a_isolated (which
# has neither the IGW route nor the peering route that rt_a has) is the
# "route missing despite peering/IGW existing" negative fixture for both the
# cross-VPC and internet-bound scenarios.

resource "aws_vpc" "vpc_a" {
  cidr_block = "10.0.0.0/16"
  tags       = { Name = "infratomic-test-app-vpc-a" }
}

resource "aws_vpc" "vpc_b" {
  cidr_block = "10.1.0.0/16"
  tags       = { Name = "infratomic-test-app-vpc-b" }
}

resource "aws_vpc" "vpc_c" {
  cidr_block = "10.2.0.0/16"
  tags       = { Name = "infratomic-test-app-vpc-c" }
}

resource "aws_subnet" "subnet_a1" {
  vpc_id     = aws_vpc.vpc_a.id
  cidr_block = "10.0.1.0/24"
  tags       = { Name = "infratomic-test-app-subnet-a1" }
}

resource "aws_subnet" "subnet_a2" {
  vpc_id     = aws_vpc.vpc_a.id
  cidr_block = "10.0.2.0/24"
  tags       = { Name = "infratomic-test-app-subnet-a2" }
}

# No route to the internet gateway or the peering connection - the
# route-missing-despite-peering/IGW-existing negative fixture.
resource "aws_subnet" "subnet_a3" {
  vpc_id     = aws_vpc.vpc_a.id
  cidr_block = "10.0.3.0/24"
  tags       = { Name = "infratomic-test-app-subnet-a3" }
}

resource "aws_subnet" "subnet_b1" {
  vpc_id     = aws_vpc.vpc_b.id
  cidr_block = "10.1.1.0/24"
  tags       = { Name = "infratomic-test-app-subnet-b1" }
}

# In vpc_c, which has no peering connection to vpc_a/vpc_b at all - the
# "no peering connection exists" negative fixture.
resource "aws_subnet" "subnet_c1" {
  vpc_id     = aws_vpc.vpc_c.id
  cidr_block = "10.2.1.0/24"
  tags       = { Name = "infratomic-test-app-subnet-c1" }
}

resource "aws_internet_gateway" "igw_a" {
  vpc_id = aws_vpc.vpc_a.id
  tags   = { Name = "infratomic-test-app-igw-a" }
}

# Same-account peering: auto_accept avoids needing a separate
# aws_vpc_peering_connection_accepter resource.
resource "aws_vpc_peering_connection" "pcx_ab" {
  vpc_id      = aws_vpc.vpc_a.id
  peer_vpc_id = aws_vpc.vpc_b.id
  auto_accept = true
  tags        = { Name = "infratomic-test-app-pcx-ab" }
}

resource "aws_route_table" "rt_a" {
  vpc_id = aws_vpc.vpc_a.id
  tags   = { Name = "infratomic-test-app-rt-a" }
}

resource "aws_route" "rt_a_igw" {
  route_table_id         = aws_route_table.rt_a.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.igw_a.id
}

resource "aws_route" "rt_a_pcx" {
  route_table_id            = aws_route_table.rt_a.id
  destination_cidr_block    = aws_vpc.vpc_b.cidr_block
  vpc_peering_connection_id = aws_vpc_peering_connection.pcx_ab.id
}

resource "aws_route_table_association" "assoc_a1" {
  subnet_id      = aws_subnet.subnet_a1.id
  route_table_id = aws_route_table.rt_a.id
}

resource "aws_route_table_association" "assoc_a2" {
  subnet_id      = aws_subnet.subnet_a2.id
  route_table_id = aws_route_table.rt_a.id
}

# No routes beyond the implicit local one.
resource "aws_route_table" "rt_a_isolated" {
  vpc_id = aws_vpc.vpc_a.id
  tags   = { Name = "infratomic-test-app-rt-a-isolated" }
}

resource "aws_route_table_association" "assoc_a3" {
  subnet_id      = aws_subnet.subnet_a3.id
  route_table_id = aws_route_table.rt_a_isolated.id
}

resource "aws_route_table" "rt_b" {
  vpc_id = aws_vpc.vpc_b.id
  tags   = { Name = "infratomic-test-app-rt-b" }
}

resource "aws_route" "rt_b_pcx" {
  route_table_id            = aws_route_table.rt_b.id
  destination_cidr_block    = aws_vpc.vpc_a.cidr_block
  vpc_peering_connection_id = aws_vpc_peering_connection.pcx_ab.id
}

resource "aws_route_table_association" "assoc_b1" {
  subnet_id      = aws_subnet.subnet_b1.id
  route_table_id = aws_route_table.rt_b.id
}

resource "aws_route_table" "rt_c" {
  vpc_id = aws_vpc.vpc_c.id
  tags   = { Name = "infratomic-test-app-rt-c" }
}

resource "aws_route_table_association" "assoc_c1" {
  subnet_id      = aws_subnet.subnet_c1.id
  route_table_id = aws_route_table.rt_c.id
}
