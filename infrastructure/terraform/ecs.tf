# ── ECS Fargate: cluster, roles, task definition, service (Phase 10, slice 10) ────────
# Runs the app as ONE Fargate task holding BOTH containers (backend + frontend nginx) that talk over
# localhost — the cheapest shape (one task = one compute charge) and it reuses the images unchanged,
# setting only env vars. ARM64 is mandatory: the images were built linux/arm64 (a mismatch won't start).
#
# Ports: nginx is hard-wired to 8080, so the backend runs on 8081 (SERVER_PORT) and nginx proxies
# /api + /actuator to http://localhost:8081. The ALB sends traffic to the frontend on 8080.
#
# The RDS master password is INJECTED from Secrets Manager by the ECS agent (see `secrets` below) — it
# never appears in this task definition, in code, or in Terraform state.

resource "aws_ecs_cluster" "main" {
  name = "${local.name_prefix}-cluster"
}

# Container logs → CloudWatch. Short retention keeps this ~free for the synthetic demo.
resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${local.name_prefix}"
  retention_in_days = 7
}

# Both ECS roles are assumed by the ECS tasks service.
data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Execution role: used by the ECS AGENT (not the app) to pull the image from ECR, write logs, and
# fetch the secret it injects. The AWS-managed policy covers ECR + logs; the inline policy adds the
# one secret read.
resource "aws_iam_role" "execution" {
  name               = "${local.name_prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-app-secrets"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue"]
      Resource = [
        aws_db_instance.main.master_user_secret[0].secret_arn, # RDS master password
        aws_secretsmanager_secret.cognito_client.arn,          # Cognito app-client secret (slice 14)
      ]
    }]
  })
}

# Task role: the app's OWN runtime identity. Empty for now — the app reaches the DB via injected env,
# not the AWS SDK. It exists so later slices (S3 documents, Cognito) can attach permissions here.
resource "aws_iam_role" "task" {
  name               = "${local.name_prefix}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_ecs_task_definition" "app" {
  family                   = "${local.name_prefix}-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "1024" # 1 vCPU
  memory                   = "2048" # 2 GB
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64" # the images are linux/arm64 (Graviton)
  }

  container_definitions = jsonencode([
    {
      name      = "backend"
      image     = "${aws_ecr_repository.app["backend"].repository_url}:latest"
      essential = true

      environment = [
        # nginx owns 8080, so the backend listens on 8081 in the shared task namespace.
        { name = "SERVER_PORT", value = "8081" },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${aws_db_instance.main.db_name}" },
        # `local,cognito`: dev-login + synthetic seed (so a Cognito login maps to a real app user) PLUS the
        # real Cognito OIDC login. The production frontend build hides dev-login; Cognito is the visible path.
        { name = "SPRING_PROFILES_ACTIVE", value = "local,cognito" },
        # Cognito OIDC client (Phase 10 slice 14) — non-secret config; the secret is injected below.
        { name = "COGNITO_CLIENT_ID", value = aws_cognito_user_pool_client.app.id },
        { name = "COGNITO_ISSUER_URI", value = "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.main.id}" },
        # Honor X-Forwarded-* from the ALB/CloudFront so generated URLs use the external scheme/host.
        { name = "SERVER_FORWARD_HEADERS_STRATEGY", value = "framework" },
        # No Kafka on AWS (no MSK) — keep the relay + consumers off so nothing tries to reach a broker.
        { name = "HEALTHCLOUD_OUTBOX_RELAY_ENABLED", value = "false" },
        { name = "HEALTHCLOUD_KAFKA_CONSUMERS_ENABLED", value = "false" },
      ]

      # Injected from Secrets Manager (never plaintext in the task def / state):
      #   RDS master creds (JSON keys username/password) + the Cognito app-client secret (whole value).
      secrets = [
        { name = "HEALTHCLOUD_DB_USER", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:username::" },
        { name = "HEALTHCLOUD_DB_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
        { name = "COGNITO_CLIENT_SECRET", valueFrom = aws_secretsmanager_secret.cognito_client.arn },
      ]

      portMappings = [{ containerPort = 8081, protocol = "tcp" }]

      healthCheck = {
        command     = ["CMD-SHELL", "curl -fsS http://localhost:8081/actuator/health || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 5
        startPeriod = 90 # Flyway migrates on first boot against a fresh RDS
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "backend"
        }
      }
    },
    {
      name      = "frontend"
      image     = "${aws_ecr_repository.app["frontend"].repository_url}:latest"
      essential = true

      # nginx reverse-proxies /api + /actuator to the backend over localhost (same-origin cookies).
      environment = [
        { name = "BACKEND_UPSTREAM", value = "http://localhost:8081" },
      ]

      # Ordering only (nginx would start regardless); keeps the console tidy.
      dependsOn = [{ containerName = "backend", condition = "START" }]

      portMappings = [{ containerPort = 8080, protocol = "tcp" }]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "frontend"
        }
      }
    },
  ])
}

resource "aws_ecs_service" "app" {
  name                              = "${local.name_prefix}-app"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.app.arn
  desired_count                     = 1
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 180 # give first-boot Flyway + seed time before ALB health counts

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true # public subnets, no NAT → the task needs a public IP for egress
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.frontend.arn
    container_name   = "frontend"
    container_port   = 8080
  }

  # The listener (and thus the ALB) must exist before the service can register targets.
  depends_on = [aws_lb_listener.http]
}
