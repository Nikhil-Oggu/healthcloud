# HealthCloud — infrastructure (Terraform)

Infrastructure-as-Code for HealthCloud's AWS deployment (Phase 10). **This is the skeleton**: it pins
the toolchain and establishes the provider + naming/tagging conventions, but declares **no resources**.

## Cost & account status — read this first
- **This skeleton provisions nothing and costs nothing.** It requires **no AWS account and no credentials**:
  `validate` and even `plan` run offline (there are no resources to plan).
- The **first step that needs an AWS account and incurs cost** is a *later* slice that adds real resources
  and runs `terraform apply`. That will be called out explicitly before it happens — it is never a side
  effect of working in this directory.

## Layout
- `versions.tf` — Terraform + AWS provider version pins (locked by `.terraform.lock.hcl`, which is committed).
- `providers.tf` — the `aws` provider; `default_tags` stamps common tags on every resource.
- `variables.tf` — `aws_region`, `project`, `environment` (with defaults, so no tfvars are required).
- `locals.tf` — `name_prefix` + `common_tags`, the conventions every resource reuses.
- `outputs.tf` — echoes the conventions.
- `backend.tf` — documents the state backend. **Local backend for now** (state on disk, git-ignored);
  the S3 + DynamoDB remote backend is bootstrapped in the deploy slice.
- `terraform.tfvars.example` — copy to `terraform.tfvars` (git-ignored) to override defaults.

## Verify locally (no AWS account, no cost)
```bash
cd infrastructure/terraform
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform plan          # "No changes" — the skeleton declares no resources
```
Install Terraform with `brew tap hashicorp/tap && brew install hashicorp/tap/terraform` if needed.

## Not here yet (later slices)
Real resources (VPC, ECS Fargate, RDS, S3/CloudFront, Cognito, MSK), the S3 remote-state backend, a
per-environment composition, and an optional `terraform fmt/validate` CI check. All data stays synthetic.
