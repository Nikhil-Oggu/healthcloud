# ADR-014 — Immutable Consent and Claim Decisions
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 3 / Phase 5)

## Context
Consent choices and claim adjudications are decisions with legal and financial weight. Overwriting them in place
would destroy the historical record of *what was decided when* — exactly the information an audit, an appeal, or a
reprocessing run needs. History must be preserved, not mutated.

## Decision
**Never overwrite a historical consent or adjudication decision — record a new version instead.**
- **Consent** uses an append-only *supersede* pattern: a `consent_directive` change marks the current row
  `SUPERSEDED` (stamping `ended_at`) and inserts a new version, with a partial unique index enforcing at most one
  ACTIVE row per natural key. Nothing is edited in place.
- **Adjudication** is immutable and versioned: each run writes a new `adjudication` (header + per-line breakdown)
  with an incrementing `adjudicationVersion`; re-adjudication **appends** a new version (first backing out the
  prior version's benefit-accumulator contribution so amounts aren't double-counted) while every prior version is
  retained. `GET .../adjudication` returns the latest; `.../adjudication/versions` lists all.
- Every workflow state change also appends an immutable `*_status_history` row.

## Consequences
- ✅ Full, auditable history: any decision can show what applied and how every amount was computed (explainability,
  and the basis for appeals/reprocessing).
- ✅ Pairs naturally with the tamper-evident audit trail (ADR-010) and the one-transaction write rule.
- ⚠️ More rows over time (versions + histories) — acceptable and expected for an audit-first system.
- ⚠️ MVP limitation: re-adjudication reverses/recomputes *this claim only*, not sibling claims in the same benefit
  year (documented, not a change to this decision).
