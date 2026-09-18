# HealthCloud — Terraform state-backend bootstrap (Phase 10, slice 6)

This tiny, **one-time** config creates the S3 bucket that the main config (`../`) uses as its
**remote-state backend**. It exists to solve a chicken-and-egg problem: Terraform can't store its
state *in* a bucket that doesn't exist yet, so this bootstrap creates that bucket first, using a
**local** state file of its own.

## What it creates
- **One S3 bucket** named `healthcloud-tfstate-<your-account-id>` — globally unique, with:
  - **versioning** on (roll back a bad state write),
  - **encryption** at rest (SSE-S3 / AES256),
  - **all public access blocked**.

Nothing else. No compute, no networking.

## Cost
Effectively **$0** — an empty bucket is free; a kilobyte-sized state file costs a fraction of a
cent per month. This bucket is **kept** (it is not part of the deploy → destroy cycle; it must
outlive the resources whose state it stores).

## How it's used (the flow)
```bash
# 1. From this bootstrap directory — create the bucket (ONE TIME):
cd infrastructure/terraform/bootstrap
terraform init
terraform plan      # review — should show the S3 bucket + its 3 settings being created
terraform apply     # ⚠️ first real AWS resource (cost ~$0)

# 2. Then in the MAIN config, the `backend "s3"` block in ../backend.tf points at this bucket,
#    and you migrate the existing local state into it:
cd ..
terraform init -migrate-state
```

After that, all main-config state lives safely in S3 with locking (S3-native `use_lockfile`,
so **no DynamoDB table is needed** on Terraform ≥ 1.10). Everyday `plan`/`apply` in `../` then
use the remote backend automatically.

## Notes
- This bootstrap's own state (`terraform.tfstate`) stays **local and git-ignored** — that's
  intentional and fine; it only records the one bucket.
- You rarely touch this directory again after the initial run.
