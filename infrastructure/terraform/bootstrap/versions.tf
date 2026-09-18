# Toolchain + provider version pins for the state-backend BOOTSTRAP (Phase 10, slice 6).
# Same pins as the main config so both resolve identical Terraform + provider versions.
terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # DELIBERATELY no `backend` block → this bootstrap uses the default LOCAL backend.
  # It creates the very S3 bucket the MAIN config (../) then uses for remote state, so it
  # cannot itself live in that bucket (chicken-and-egg). Its local state file is git-ignored
  # and is only touched during this one-time bootstrap.
}
