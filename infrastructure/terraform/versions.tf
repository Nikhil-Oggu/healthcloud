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

  # NOTE: the `backend` block lives in backend.tf — an S3 remote backend (enabled in slice 6,
  # backed by the bucket that ./bootstrap creates), with S3-native locking (no DynamoDB).
}
