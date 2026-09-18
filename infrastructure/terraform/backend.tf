# Terraform state backend (Phase 10, slice 5).
#
# For now this file is INTENTIONALLY just documentation: with no `backend` block declared, Terraform
# uses its default LOCAL backend — state is written to ./terraform.tfstate on disk (git-ignored), which
# needs no AWS account and costs nothing. That is correct for a skeleton with no resources.
#
# When we start provisioning real infrastructure (a later slice, which DOES require an AWS account and
# incurs cost), we switch to a shared S3 remote backend with DynamoDB state locking so state is durable
# and safe for multiple people/CI. That backend's bucket + lock table must be created FIRST (a one-time
# "bootstrap"), because Terraform can't create the very bucket it stores its own state in. Enabling the
# block below is therefore a deliberate, announced step — never a side effect of this skeleton.
#
# terraform {
#   backend "s3" {
#     bucket         = "healthcloud-tfstate-<unique-suffix>"  # created during bootstrap
#     key            = "healthcloud/<environment>/terraform.tfstate"
#     region         = "us-east-1"
#     dynamodb_table = "healthcloud-tflock"                   # created during bootstrap
#     encrypt        = true
#   }
# }
