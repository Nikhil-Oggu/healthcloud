# ADR-001 — Modular monolith first
- Status: Accepted
- Date: 2026-09-12

## Context
HealthCloud is built and maintained by a single engineer as a portfolio project. It needs strong
transactional correctness (domain change + history + audit + outbox in one transaction), low running
cost, and easy local development, while still keeping clean domain boundaries for future growth.

## Decision
Build the backend as a **modular Spring Boot monolith** with well-separated modules (identity,
organizations, patients/providers, consent, authorization, requests/workflow, clinical context,
claims, adjudication, documents, audit, events, admin, reporting) rather than starting with
microservices. Extract services later only if scaling or ownership evidence justifies it.

## Consequences
- ✅ Simpler transactions, cheaper deployment, faster local dev, realistic for one engineer to finish.
- ✅ Module boundaries preserved so extraction stays possible later.
- ⚠️ A single deployable has a larger logical blast radius; discipline needed to keep modules decoupled.
- Revisit only with measured scaling/ownership pressure (see §62 trade-offs).
