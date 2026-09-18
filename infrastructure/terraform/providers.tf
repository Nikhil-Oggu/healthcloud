# AWS provider configuration (Phase 10, slice 5).
# default_tags stamps the common tags onto every taggable resource we ever create, so tagging is a
# convention, not a per-resource chore. No credentials are needed to `validate` — and this skeleton
# declares no resources, so `plan` makes no AWS API calls either (zero cost, no account required).
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.common_tags
  }
}
