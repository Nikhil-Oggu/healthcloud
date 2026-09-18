# AWS provider for the bootstrap (Phase 10, slice 6).
# default_tags stamps these onto the state bucket automatically. Unlike the main config,
# the bootstrap is environment-agnostic (the state bucket is shared across all environments),
# so it tags Project/ManagedBy but not Environment.
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "Terraform"
      Purpose   = "terraform-remote-state"
    }
  }
}
