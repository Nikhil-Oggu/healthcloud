# ── Terraform remote-state bucket (Phase 10, slice 6) ────────────────────────────────
# Creates the single S3 bucket that the MAIN config (../) uses as its remote-state backend.
# This runs with LOCAL state (see versions.tf) because the bucket must exist BEFORE the
# remote backend that points at it. Run once; it is kept (not part of the deploy/destroy cycle).
#
# Cost: an empty S3 bucket is free to create; storing a kilobyte-sized state file is a
# fraction of a cent per month. No compute, no networking. All data stays synthetic.

# The account id makes the bucket name globally unique (S3 bucket names are global) with no
# random suffix, so re-running this is deterministic.
data "aws_caller_identity" "current" {}

locals {
  state_bucket_name = "${var.project}-tfstate-${data.aws_caller_identity.current.account_id}"
}

# The state bucket itself.
resource "aws_s3_bucket" "state" {
  bucket = local.state_bucket_name
}

# Keep every version of the state file, so a bad write can be rolled back to a prior version.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Encrypt state at rest (it can hold sensitive values) with S3-managed keys (SSE-S3).
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# State is private — block all forms of public access (belt and braces).
resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
