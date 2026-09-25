# ADR-015 — On-Demand Full AWS Validation
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 10)

## Context
A portfolio project should *prove* it runs on real managed cloud infrastructure — but a permanently-running AWS
stack costs money every hour, and this is a solo project on a personal account. The tension: demonstrate the full
managed architecture without either (a) paying for an always-on environment, or (b) weakening the technology stack
just to fit a permanent $0 free tier.

## Decision
Stand the full stack up **on demand**, capture evidence, then tear it down:
- Everything is Terraform (ADR-013): `apply → capture evidence (health, login, screenshots) → terraform destroy`
  back to ~$0. Only the near-zero-cost state bucket is kept between runs.
- **Do not weaken the core stack for cost.** Where a managed service is too expensive to leave running, prove the
  capability locally instead of degrading the design — e.g. **no MSK** (the event-driven system is proven locally
  with Kafka, ADR-009).
- Apply the same cost discipline structurally: **no NAT Gateway** (public-subnet egress instead, ~$32/mo saved),
  **ARM64/Graviton** runtime (~20% cheaper), one Fargate task holding both containers.

## Consequences
- ✅ Real, demonstrable cloud proof (ECS Fargate + RDS + CloudFront + Cognito over HTTPS) at ~$0 steady-state cost.
- ✅ The design isn't compromised to chase a permanent free deployment; local proof covers what's uneconomic to
  leave running.
- ⚠️ **No always-on public URL** — the environment must be re-applied to demo (a few minutes), and evidence
  (screenshots/logs) is the durable artifact.
- ⚠️ Some AWS drift accumulates between apply/destroy cycles (e.g. hand-created local Cognito client) — documented
  in `CLAUDE.md`, to be reconciled on a future apply.
