# Terraform state backend (Phase 10 — enabled in slice 6).
#
# State now lives in the S3 bucket created by ./bootstrap (see bootstrap/README.md). The bucket is
# versioned + encrypted + private. Locking uses S3-native `use_lockfile` (Terraform >= 1.10), so
# there is NO DynamoDB lock table to run — one fewer resource, and $0 extra.
#
# The bucket must already exist (run the bootstrap once) before `terraform init` here succeeds.
# To (re)initialize after changing this block: `terraform init -migrate-state`.
terraform {
  backend "s3" {
    bucket       = "healthcloud-tfstate-927747714796"
    key          = "healthcloud/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
