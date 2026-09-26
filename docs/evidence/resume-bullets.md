# Resume Bullets & Talking Points — HealthCloud

> Interview-ready summaries of the project. Written to the project's **no-unmeasured-claims** rule:
> every number here is something actually counted or measured (test counts, table counts, phase
> count), never an invented performance/uptime/coverage figure. HealthCloud is a healthcare-*inspired*,
> HIPAA-*aligned* portfolio project on **synthetic data only** — describe it that way, never as
> certified or production healthcare software.

## One-line description

> **HealthCloud** — a multi-tenant, consent-aware care-coordination & claims platform (Java 25 /
> Spring Boot 4.1 backend, React 19 / TypeScript frontend) with a layered authorization model, an
> explainable claims-adjudication engine, event-driven processing, a tamper-evident audit trail, full
> observability, and an on-demand AWS deployment — built solo across 12 phases on synthetic data.

## Resume bullets (concise — pick 3–5)

- Built a **multi-tenant healthcare platform** (Java 25, Spring Boot 4.1, React 19/TypeScript,
  PostgreSQL) enforcing strict tenant isolation where the tenant is **derived on the backend**, never
  trusted from the client.
- Designed a **layered authorization pipeline** — tenant → role → patient relationship → consent +
  purpose → field-level masking — with **secure-404** denials so a request never reveals the existence
  of data the caller can't see.
- Implemented an **explainable claims-adjudication engine** (eligibility, deductible, copay,
  coinsurance, out-of-pocket max, exclusions, prior-auth, network) that records, per line, exactly how
  every dollar was computed, with **immutable versioned** re-adjudication.
- Built **event-driven processing** with the **transactional-outbox** pattern + Kafka, an idempotent
  consumer, retry/backoff, a dead-letter queue, and admin replay.
- Added a **tamper-evident audit trail** — a per-organization **HMAC-SHA256 hash chain** whose
  integrity is verifiable and which detects any modified, deleted, reordered, or inserted record.
- Instrumented the system with **Micrometer → Prometheus/Grafana** metrics, **OpenTelemetry → Jaeger**
  distributed tracing, health/readiness probes, alert rules, and a rehearsed **backup/restore drill**.
- Deployed to **AWS with Terraform** (ECS Fargate, RDS, CloudFront/HTTPS, **Cognito OIDC** via a Spring
  BFF) on an **on-demand** apply→verify→destroy cycle, with **GitHub Actions** CI/CD publishing images
  to GHCR.
- Backed the whole system with **694 automated tests** (511 backend via Testcontainers + 183 frontend),
  including negative security tests (cross-tenant, consent-masking, invalid-transition) as first-class
  acceptance proof.

## Resume bullets — with metrics (ATS-friendly, pick 4–6)

> Same project, framed **metric-first** for resume/ATS scanning. **Every number here is real** —
> counted (tests, tables, phases, layers) or **measured** (the load-test latency/throughput, the
> AWS hourly cost) — never an invented performance/uptime figure (project rule #2). The one runtime
> performance number (throughput/latency) is from a **local, single-node** k6 run and is labeled as
> such — see [`load-test.md`](load-test.md). Keep that "local single-node" qualifier if you use it,
> so it holds up in an interview.

- **Delivered a multi-tenant healthcare platform across 12 delivery phases** — **30 domain modules,
  50 PostgreSQL tables over 43 Flyway migrations** (Java 25 / Spring Boot 4.1, React 19 / TypeScript)
  — with tenant identity **derived server-side on every request**, never trusted from the client.
- **Engineered a 5-layer authorization pipeline** (tenant → role → patient-relationship →
  consent+purpose → field-masking) with **secure-404** denials, so two users with the *same role*
  get different results — enforced entirely on the backend and validated by negative security tests.
- **Load-tested the authenticated read path with k6 at 50 concurrent users**, measuring **~107
  requests/sec at a p95 latency of 38 ms with a 0% error rate** (local single-node) across the full
  authorization pipeline plus live PostgreSQL reads.
- **Backed the system with 694 automated tests** (511 backend on Testcontainers against real
  PostgreSQL + 183 frontend), including **cross-tenant, consent-masking, and invalid-transition
  negative security tests** treated as first-class acceptance criteria.
- **Built an explainable claims-adjudication engine** (eligibility, deductible, copay, coinsurance,
  out-of-pocket max, exclusions, prior-auth, provider network) that records **per line** exactly how
  every dollar was computed, with **immutable, versioned** re-adjudication.
- **Implemented event-driven processing** with the transactional-outbox pattern + Kafka — idempotent
  consumer, retry/backoff, a dead-letter queue, and admin replay — surfaced across **8 server-side
  paginated, searchable work queues**.
- **Added a tamper-evident audit trail** — a per-organization **HMAC-SHA-256 hash chain** keyed from
  a secret held *outside* the database — with **on-demand integrity verification** that detects any
  modified, deleted, reordered, or inserted record.
- **Provisioned reproducible AWS infrastructure with Terraform** (ECS Fargate, RDS, CloudFront/HTTPS,
  Cognito OIDC via a Spring BFF) on an on-demand apply→verify→destroy cycle measured at **~$0.08–0.10/hr
  (~$0 at rest)** — saving **~$32/mo** by routing Fargate egress through public subnets instead of a NAT
  gateway — shipped through **GitHub Actions CI/CD (4 gated jobs)** publishing images to GHCR.
- **Instrumented full observability** — Micrometer→**Prometheus/Grafana** metrics (18-panel dashboard),
  OpenTelemetry→**Jaeger** tracing, health/readiness probes, **5 alert rules**, and a rehearsed
  backup/restore drill — with the frontend **WCAG 2.2 AA-aligned** behind an automated axe-core gate.

## Shorter variants (for tighter formats)

- **Backend-heavy:** "Multi-tenant Spring Boot 4.1 platform with backend-derived tenancy, a 5-layer
  authorization pipeline (relationship + consent + field masking, secure-404 denials), an explainable
  claims-adjudication engine, transactional-outbox + Kafka eventing, and a per-org HMAC tamper-evident
  audit chain; 511 backend tests on real Postgres via Testcontainers."
- **Full-stack:** "Full-stack healthcare app (Spring Boot 4.1 + React 19/TS) with consent-aware,
  field-level data masking enforced on the backend; explainable claims adjudication; AWS deploy
  (Terraform/ECS/Cognito/CloudFront) with CI/CD; 694 tests."
- **Platform/DevOps-leaning:** "Containerized a Spring Boot + React app, published images via GitHub
  Actions → GHCR, and provisioned AWS (ECS Fargate, RDS, CloudFront, Cognito) with Terraform on an
  on-demand, cost-controlled apply/destroy cycle; added Prometheus/Grafana metrics, Jaeger tracing,
  alerts, and a rehearsed DB restore drill."

## Interview talking points (the "why", not just the "what")

- **Security is a pipeline of independent narrowing layers, not one check.** Two users with the *same
  role* get *different results* because of the patient-relationship and consent layers — demonstrated
  with live request/response transcripts (see `security-proofs.md`). Denials are secure-404 so they
  don't leak existence.
- **The backend is the only security boundary.** The React UI hides/disables controls for UX, but every
  protected operation is re-authorized server-side; the client never supplies tenant/roles.
- **Explainability over a black box.** For any claim decision the platform can show which plan applied
  and how allowed / copay / deductible / coinsurance / OOP / plan-paid / member were each derived — and
  keeps every prior adjudication version immutably.
- **Correctness under concurrency.** Optimistic locking for state machines; a pessimistic row lock for
  the financial benefit accumulator; the transactional outbox so a domain change and its event commit
  atomically (no dual-write).
- **Trust but verify (auditing).** The audit trail is an HMAC hash chain keyed per organization from a
  secret held *outside* the database it protects, so tampering with the table alone can't forge a valid
  entry — and integrity is verifiable on demand.
- **Operability was built in, not bolted on.** Metrics, tracing, health/readiness split, alert rules,
  runbooks, and a restore drill — "a backup you've never restored isn't a backup."
- **Cost-aware cloud.** AWS runs on-demand (apply → capture evidence → destroy) with no NAT gateway and
  a single Fargate task, deliberately trading always-on for ~$0 at rest.
- **Honesty as a discipline.** The project's own rules forbid unmeasured claims and require calling it
  HIPAA-*aligned*, not certified — and deferred work is documented as conscious trade-offs (see the ADRs
  and the limitations section of the README).

## Proof to point an interviewer at

- **Case study:** [`README.md`](../../README.md)
- **Architecture + data model:** [`docs/architecture/`](../architecture/), [`docs/er-diagram/`](../er-diagram/)
- **Threat model:** [`docs/threat-model/`](../threat-model/) · **Decisions:** [`docs/adr/`](../adr/)
- **This evidence pack:** test results, live security transcripts, observability, UI, and the AWS
  deployment — [`README.md`](README.md)
