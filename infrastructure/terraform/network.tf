# ── VPC & networking (Phase 10, slice 7) ─────────────────────────────────────────────
# The network the app runs in. Cost-conscious topology:
#   • PUBLIC subnets host the ALB + Fargate tasks — tasks get a public IP and reach the
#     internet directly, so there is NO NAT Gateway (~$32/mo saved).
#   • PRIVATE subnets host RDS — the DB needs no outbound internet, and is reachable only
#     from inside the VPC.
# Every resource in this file is FREE. Billable pieces (ALB, public IPv4, Fargate) arrive
# in later slices and draw from the account credits.

# Pick the first two Availability Zones in the region. RDS requires subnets in 2 AZs.
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  vpc_cidr        = "10.0.0.0/16"
  az_count        = 2
  azs             = slice(data.aws_availability_zones.available.names, 0, local.az_count)
  public_subnets  = ["10.0.0.0/24", "10.0.1.0/24"]
  private_subnets = ["10.0.10.0/24", "10.0.11.0/24"]
}

resource "aws_vpc" "main" {
  cidr_block           = local.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # required for RDS endpoints + private/interface DNS

  tags = { Name = "${local.name_prefix}-vpc" }
}

# The VPC's single door to the internet (used only by the public route table).
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name_prefix}-igw" }
}

# ── Public subnets (ALB + Fargate) ──────────────────────────────────────────────────
resource "aws_subnet" "public" {
  count                   = local.az_count
  vpc_id                  = aws_vpc.main.id
  cidr_block              = local.public_subnets[count.index]
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true # tasks here get a public IP → no NAT needed

  tags = { Name = "${local.name_prefix}-public-${local.azs[count.index]}" }
}

# ── Private subnets (RDS) ───────────────────────────────────────────────────────────
resource "aws_subnet" "private" {
  count             = local.az_count
  vpc_id            = aws_vpc.main.id
  cidr_block        = local.private_subnets[count.index]
  availability_zone = local.azs[count.index]

  tags = { Name = "${local.name_prefix}-private-${local.azs[count.index]}" }
}

# ── Public route table: 0.0.0.0/0 → Internet Gateway ────────────────────────────────
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name_prefix}-public-rt" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route_table_association" "public" {
  count          = local.az_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ── Private route table: LOCAL-ONLY (no 0.0.0.0/0 route → confirms no NAT / no egress) ──
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name_prefix}-private-rt" }
}

resource "aws_route_table_association" "private" {
  count          = local.az_count
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}
