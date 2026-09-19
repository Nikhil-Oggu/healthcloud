# ── RDS PostgreSQL (Phase 10, slice 8) ───────────────────────────────────────────────
# Managed Postgres 17 in the PRIVATE subnets — not publicly accessible, encrypted at rest,
# master password held in Secrets Manager (never in code or Terraform state). Cheapest
# single-AZ setup, backups off + no final snapshot so it destroys/recreates cleanly on demand.
# Synthetic data only.

# Subnet group across the two private subnets (RDS requires >= 2 AZs).
resource "aws_db_subnet_group" "main" {
  name       = "${local.name_prefix}-db"
  subnet_ids = aws_subnet.private[*].id

  tags = { Name = "${local.name_prefix}-db-subnet-group" }
}

# Security group: Postgres 5432 inbound from the Fargate app's security group only.
# (Tightened from VPC-wide to the app SG in the ECS slice — least privilege; the DB also lives in
# private subnets with no public access.)
resource "aws_security_group" "rds" {
  name = "${local.name_prefix}-rds-sg"
  # NB: the SG description is immutable in AWS — changing it forces a replacement — so it keeps its
  # original wording. The actual (tightened) rule is the app-SG ingress below.
  description = "PostgreSQL access from within the VPC"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL from the Fargate app tasks"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name_prefix}-rds-sg" }
}

resource "aws_db_instance" "main" {
  identifier     = "${local.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = "17" # prefix-matches the latest 17.x (no version drift)
  instance_class = "db.t4g.micro"

  db_name                     = "healthcloud"
  username                    = "healthcloud"
  manage_master_user_password = true # master credentials stored + managed in Secrets Manager

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_subnet_group_name    = aws_db_subnet_group.main.name
  vpc_security_group_ids  = [aws_security_group.rds.id]
  publicly_accessible     = false
  multi_az                = false
  backup_retention_period = 0 # backups off for the synthetic demo (fast, clean teardown)
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true

  tags = { Name = "${local.name_prefix}-postgres" }
}
