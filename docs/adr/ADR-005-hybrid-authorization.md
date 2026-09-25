# ADR-005 — Hybrid Authorization
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 3)

## Context
Role-based access control alone is insufficient for healthcare-style data. Two users with the *same role* must
often get *different results*: a provider may see only patients they are assigned to; a consent directive may hide
a field from one caller but not another; a claims reviewer needs claim data without unrestricted clinical context.
A single "has role X" check cannot express this.

## Decision
Authorize every protected read through an ordered pipeline of **independent backend layers**, each of which can
only *narrow* access:

`tenant → role/function → object relationship → consent + purpose → business need → field-level classification → ALLOW/DENY → audit`

Concretely in the code:
- **Tenant** (ADR-003) and **role** (`UserContextAccessor.requireAnyRole`) gates first.
- **Object/relationship** through the single choke-point `PatientAccessGuard.requireAccessibleInTenant(...)` —
  every patient-scoped read routes through it (a provider reaches only assigned patients; a patient only their own
  record).
- **Consent + purpose** via `ConsentPolicyService.decideForActor` — deny-by-default, with the read's *purpose*
  fixed on the backend per action, not chosen by the client.
- **Field-level masking** builds field-safe DTOs; a withheld field is `null` and named in `maskedFields`.
- A relationship/consent denial is a **secure 404**, never a 403 that would confirm the row exists.

## Consequences
- ✅ True least-privilege: identical roles yield different results based on relationship, consent, and purpose —
  the platform's flagship differentiator.
- ✅ One enforcement path (the guard) means a new endpoint cannot silently bypass the relationship layer.
- ✅ The frontend can hide UI for convenience while the backend remains the only security boundary.
- ⚠️ More moving parts than plain RBAC; mitigated by the single choke-point and layer-specific tests.
- ⚠️ A full per-role/per-field permission matrix is only partially realized (e.g. some broad roles skip the
  relationship layer today) — refinements are a documented follow-up, not a change to this decision.
