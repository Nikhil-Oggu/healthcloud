# ADR-013 — Terraform + GitHub Actions
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 10)

## Context
A portfolio project that claims cloud and CI/CD experience must *show* it reproducibly, not with click-ops in a
console that leaves no trace. Both the infrastructure and the delivery pipeline need to be reviewable (as code, in
version control) and evidence-producing (a reviewer can see what was built and how).

## Decision
Use **Terraform for infrastructure** and **GitHub Actions for delivery**:
- **Terraform** (`infrastructure/terraform/`) defines all AWS resources, with **S3 remote state** + native locking;
  `.terraform.lock.hcl` is committed, and `default_tags` stamp every resource. This makes the whole environment
  reproducible and destroyable on demand (ADR-015).
- **GitHub Actions** (`.github/workflows/ci.yml`, on push + PR to `main`): a backend job (`mvnw verify` with
  Testcontainers), a frontend job (typecheck → test → build), and backend/frontend **container-image** jobs that
  publish to GHCR (`sha-<short>` + `latest`) only on `main`. All jobs must stay green.

## Consequences
- ✅ Infrastructure and delivery are reproducible, reviewable (code review + PR CI), and evidence-producing.
- ✅ Secrets are handled by the pipeline's built-in `GITHUB_TOKEN` / AWS credentials — none committed.
- ✅ Immutable `sha-<short>` image tags give deploys something precise to pin.
- ⚠️ Some AWS drift can accumulate between apply/destroy cycles (e.g. a hand-created local Cognito client);
  documented in `CLAUDE.md` and reconciled on a future apply.
- ⚠️ ECR image publishing to AWS is currently a manual `crane` step (CI publishes to GHCR); wiring CI→ECR via OIDC is
  a documented option not yet taken.
