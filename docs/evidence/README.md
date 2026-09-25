# HealthCloud — Evidence Pack

> A curated, **sanitized** set of proofs that HealthCloud works as described. Everything here is
> captured against **synthetic data only** and reports **only measured/observed results** (no
> unmeasured claims — see the project rules in `CLAUDE.md`). This pack is the "show me, don't tell
> me" companion to the [README case study](../../README.md) and the
> [architecture](../architecture/architecture.md) / [threat model](../threat-model/threat-model.md) docs.

## What this pack contains

| Evidence | File | Proves |
|----------|------|--------|
| Test suite results | [test-results.md](test-results.md) | The whole suite is green (measured counts, backend + frontend) |
| Security boundary transcripts | [security-proofs.md](security-proofs.md) | Real HTTP request/response proof of the security model |
| Observability screenshots | [screenshots/](screenshots/) | Metrics dashboards, Prometheus, distributed tracing work |
| Application UI screenshots | [screenshots/](screenshots/) | The app runs end-to-end across every major screen |
| Live AWS cloud capture | [screenshots/aws/](screenshots/aws/) | The app deploys and runs on AWS over HTTPS with real Cognito login |
| Resume bullets | [resume-bullets.md](resume-bullets.md) | Interview-ready, measured summaries of the work |

## Acceptance criteria → evidence map

The source-of-truth (§60) defines acceptance criteria per phase. This pack maps each headline
criterion to the artifact that proves it:

| Acceptance criterion (§60) | Where it is proven |
|----------------------------|--------------------|
| The full automated test suite passes | [test-results.md](test-results.md) |
| A NorthCare user cannot access Green Valley data (tenant isolation) | [security-proofs.md](security-proofs.md) → *Cross-tenant isolation* |
| Two users with the **same role** get **different results** (relationship + consent) | [security-proofs.md](security-proofs.md) → *Relationship gate* / *Consent field masking* |
| A consent-controlled field is withheld unless consent allows it | [security-proofs.md](security-proofs.md) → *Consent field masking* |
| Invalid / concurrent state transitions are rejected, not silently applied | [security-proofs.md](security-proofs.md) → *Invalid state transition* |
| For any adjudication decision, the platform shows how every amount was computed | Application UI → *Claim adjudication breakdown* screenshot |
| The system is observable (metrics, dashboards, tracing) | [screenshots/](screenshots/) → Grafana / Prometheus / Jaeger |
| The system deploys to the cloud and runs there | [screenshots/aws/](screenshots/aws/) |

## How this pack was produced

- **Local stack.** Postgres (Docker) + the Spring Boot backend (`local` profile — synthetic seed +
  the dev-login stand-in) + the React frontend (Vite), plus the `observability` compose profile
  (Prometheus / Grafana / Jaeger) for the metrics/tracing captures.
- **Cloud stack.** The Terraform-managed AWS environment (ECS Fargate + ALB + RDS + Cognito +
  CloudFront), stood up **on demand**, captured, then torn down to control cost.
- **Reproducibility.** The exact commands to reproduce every artifact are noted inside each file.

> **Synthetic data notice.** Every name, patient, claim, organization, and credential in these
> screenshots and transcripts is synthetic demo data. HealthCloud is a healthcare-*inspired*,
> HIPAA-*aligned* portfolio project — **not** certified healthcare software and never connected to
> real patient data.
