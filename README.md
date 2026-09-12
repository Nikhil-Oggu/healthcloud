# HealthCloud — Consent-Aware Care Coordination & Claims Platform

> ⚠️ **Status: under construction (Phase 0).** This is a healthcare-**inspired**, HIPAA-**aligned**
> engineering portfolio project built on **synthetic data only**. It is not HIPAA-certified and is not
> used with real patient data. Performance/security/cost figures will be added only after they are measured.

## The problem
Healthcare-style workflows involve many participants — patients, providers, care coordinators, claims
reviewers, administrators, auditors — who must each see different data for different reasons.
Authentication only answers *who you are*; it does not answer whether you belong to the right
organization, are assigned to this patient, have consent, have a permitted purpose, need a specific
field, or may perform a given action. HealthCloud is a system whose engineering value is in answering
**those** questions — and auditing every sensitive action.

## Core engineering story
A multi-tenant platform where access is controlled by **identity → organization → role → patient
relationship → consent → purpose of use → business need → field-level visibility → ALLOW/DENY → audit**,
with controlled workflow state machines, an explainable synthetic claims-adjudication engine, secure
documents, immutable decision history, event-driven reliability (transactional outbox + Kafka), and
production-style cloud operations.

## Technology
Java 25 / Spring Boot 4.1 · React 19 / TypeScript / Vite · PostgreSQL 17 + Flyway · Apache Kafka ·
Amazon Cognito + Spring BFF · AWS (ECS Fargate, RDS, S3/CloudFront, MSK) · Terraform · GitHub Actions.
Architecture: **modular monolith**, shared-DB multi-tenancy, hybrid RBAC + attribute-based authorization.

## Documentation
- **Design source of truth:** `docs/source-of-truth/HealthCloud_Final_Source_of_Truth.pdf`
- **Roadmap:** `docs/PLAN.md` · **Progress log:** `docs/PROGRESS.md`
- **Decisions:** `docs/adr/` · **Project rules for contributors/AI:** `CLAUDE.md`

## Build roadmap (MVP = Phase 0–5)
0 Design/scaffold · 1 Foundation & multi-tenant identity · 2 Care-coordination workflow ·
3 Consent/authorization/privacy/documents · 4 Clinical context & claims intake · 5 Basic adjudication (MVP) ·
6 Advanced claims · 7 Security/governance · 8 Event-driven · 9 Search/reporting/accessibility ·
10 AWS deploy & CI/CD · 11 Observability & recovery · 12 Final validation & portfolio.

## Local setup
_Coming in Phase 1_ (Docker Compose brings up PostgreSQL + the app locally). The full case-study
README — screenshots, demo video, evidence, ADRs, limitations — is assembled in Phase 12.
