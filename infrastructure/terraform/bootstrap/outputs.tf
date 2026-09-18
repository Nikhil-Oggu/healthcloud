# Outputs from the bootstrap (Phase 10, slice 6).
# state_bucket_name is what you plug into the main config's backend.tf `bucket = ...`.
output "state_bucket_name" {
  description = "Name of the S3 bucket that holds Terraform remote state (use in ../backend.tf)."
  value       = aws_s3_bucket.state.id
}

output "state_bucket_arn" {
  description = "ARN of the state bucket."
  value       = aws_s3_bucket.state.arn
}
