# ADR-006 — Limited Clinical Context
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 4)

## Context
Care coordination and claims need *some* clinical information, but building a full electronic health record (EHR)
— encounters, orders, results, medications, problem lists, full clinical documentation — is an enormous scope, is
not the point of this platform, and would multiply the sensitive-data surface far beyond what the access model is
meant to demonstrate.

## Decision
Model **limited clinical context** rather than a full EHR: short `clinical_summary` notes about an encounter, each
pointing at a coded **ICD-10-CM diagnosis** from the global `medical_code` catalog, plus the claim-support data
carried on claims (coded procedures + amounts). The free-text `narrative` on a clinical summary is the one
consent-controlled field (masked deny-by-default via the consent engine); the structured diagnosis **code** stays
visible so a claims reviewer sees coded, claim-relevant data without unrestricted clinical context.

## Consequences
- ✅ Keeps scope tractable and focused on the platform's real subject — consent-aware access and explainable claims
  — not clinical record-keeping.
- ✅ Directly supports the §60 proof: coded clinical data is available for claims while the narrative is
  consent-gated (the flagship privacy demonstration).
- ✅ Fewer sensitive fields to classify and protect.
- ⚠️ HealthCloud is **not** a clinical system of record and must not be treated as one; it deliberately omits the
  breadth a real EHR provides.
