# ── ECR container registries (Phase 10, slice 9) ─────────────────────────────────────
# One repository per image. ECS Fargate pulls from here natively via its IAM role (no
# registry passwords). scan_on_push runs a free vulnerability scan; a lifecycle policy keeps
# storage tiny; force_delete lets `terraform destroy` remove the repo even with images in it.

locals {
  ecr_repos = ["backend", "frontend"]
}

resource "aws_ecr_repository" "app" {
  for_each             = toset(local.ecr_repos)
  name                 = "${local.name_prefix}-${each.key}"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = { Name = "${local.name_prefix}-${each.key}" }
}

# Keep only the last 5 images per repo so storage stays negligible.
resource "aws_ecr_lifecycle_policy" "app" {
  for_each   = aws_ecr_repository.app
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep only the last 5 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 5
      }
      action = { type = "expire" }
    }]
  })
}
