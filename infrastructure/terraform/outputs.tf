# Outputs (Phase 10, slice 5). For now just the conventions, so they're inspectable via
# `terraform output` and consumable by later modules. These need no AWS API calls.
output "name_prefix" {
  description = "Resource-name prefix for this environment (e.g. healthcloud-dev)."
  value       = local.name_prefix
}

output "common_tags" {
  description = "Tags applied to every resource via the provider default_tags."
  value       = local.common_tags
}

# ── Networking (Phase 10, slice 7) — consumed by the RDS / ECS / ALB slices ──
output "vpc_id" {
  description = "ID of the VPC the app runs in."
  value       = aws_vpc.main.id
}

output "public_subnet_ids" {
  description = "Public subnet IDs (ALB + Fargate)."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Private subnet IDs (RDS)."
  value       = aws_subnet.private[*].id
}

# ── RDS (Phase 10, slice 8) — consumed by the ECS Fargate slice ──
output "db_address" {
  description = "RDS Postgres hostname."
  value       = aws_db_instance.main.address
}

output "db_port" {
  description = "RDS Postgres port."
  value       = aws_db_instance.main.port
}

output "db_name" {
  description = "Initial database name."
  value       = aws_db_instance.main.db_name
}

output "db_master_secret_arn" {
  description = "Secrets Manager ARN holding the RDS master credentials (the app reads this via IAM)."
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

# ── ECR (Phase 10, slice 9) — the ECS task definitions reference these image repos ──
output "ecr_repository_urls" {
  description = "ECR repository URLs, keyed by app (backend/frontend)."
  value       = { for k, r in aws_ecr_repository.app : k => r.repository_url }
}

# ── ECS Fargate + ALB (Phase 10, slice 10) — the live app ──
output "app_url" {
  description = "Public URL of the running app (the ALB DNS name; HTTP this slice)."
  value       = "http://${aws_lb.main.dns_name}"
}

# ── Cognito (Phase 10, slice 11) — consumed by the backend BFF wiring slice ──
output "cognito_user_pool_id" {
  description = "Cognito user pool ID."
  value       = aws_cognito_user_pool.main.id
}

output "cognito_client_id" {
  description = "Cognito app client ID (the BFF's client_id)."
  value       = aws_cognito_user_pool_client.app.id
}

output "cognito_client_secret" {
  description = "Cognito app client secret (the BFF reads this; keep it out of code)."
  value       = aws_cognito_user_pool_client.app.client_secret
  sensitive   = true
}

output "cognito_issuer_url" {
  description = "OIDC issuer URL (Spring Security's issuer-uri for discovery)."
  value       = "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.main.id}"
}

output "cognito_hosted_ui_domain" {
  description = "Cognito hosted login page domain."
  value       = "https://${aws_cognito_user_pool_domain.main.domain}.auth.${var.aws_region}.amazoncognito.com"
}

# ── CloudFront (Phase 10 slice 15) — the public HTTPS URL of the app ──
output "cloudfront_url" {
  description = "Public HTTPS URL of the app (CloudFront in front of the ALB)."
  value       = "https://${aws_cloudfront_distribution.main.domain_name}"
}
