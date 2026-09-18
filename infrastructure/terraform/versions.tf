# Toolchain + provider version pins (Phase 10, slice 5 — the Terraform skeleton).
# Pinned so every machine and CI run resolves the same Terraform + provider versions
# (the .terraform.lock.hcl this produces is committed and locks the exact provider hashes).
terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # NOTE: no `backend` block here → Terraform uses the default LOCAL backend (state on disk,
  # git-ignored). The S3 remote-state backend is a later slice — see backend.tf.
}
