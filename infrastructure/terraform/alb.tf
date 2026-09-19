# ── Application Load Balancer + security groups (Phase 10, slice 10) ──────────────────
# The public entry point for the app. Traffic flows:
#   Internet → ALB :80 → the Fargate task's frontend (nginx) container :8080
# HTTP only this slice; HTTPS (ACM cert + a domain) arrives with the CloudFront/Cognito slices.

# SG for the ALB: HTTP in from anywhere, all out.
resource "aws_security_group" "alb" {
  name        = "${local.name_prefix}-alb-sg"
  description = "Public HTTP to the load balancer"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP from the internet"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name_prefix}-alb-sg" }
}

# SG for the Fargate task: the app port is reachable ONLY from the ALB; all outbound (so the task can
# pull from ECR, read Secrets Manager, and reach RDS). This SG is also what the RDS SG now trusts on 5432.
resource "aws_security_group" "app" {
  name        = "${local.name_prefix}-app-sg"
  description = "Fargate task: app port from the ALB only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "App traffic from the ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name_prefix}-app-sg" }
}

# The load balancer itself, in the two public subnets.
resource "aws_lb" "main" {
  name               = "${local.name_prefix}-alb"
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  tags = { Name = "${local.name_prefix}-alb" }
}

# The target group the ECS service registers its task into. target_type = "ip" because awsvpc Fargate
# tasks register by IP, not by instance. The health check hits the frontend's SPA root (fast + served
# by nginx independently of the backend), so a task turns healthy quickly while the backend still warms up.
resource "aws_lb_target_group" "frontend" {
  name        = "${local.name_prefix}-fe-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"

  health_check {
    path                = "/"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 5
  }

  tags = { Name = "${local.name_prefix}-fe-tg" }
}

# HTTP :80 listener → forward everything to the frontend target group (nginx then reverse-proxies
# /api + /actuator to the backend container over localhost, same-origin).
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }
}
