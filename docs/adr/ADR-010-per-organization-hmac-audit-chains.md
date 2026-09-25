# ADR-010 — Per-Organization HMAC Audit Chains
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 7)

## Context
An audit trail that can be silently edited is worthless for accountability. HealthCloud records sensitive actions
(adjudication, consent revoke, break-glass, retention, replay); we need to be able to *prove* that the trail has
not been modified, deleted, reordered, inserted into, or truncated — including by someone with database write
access.

## Decision
Make the audit trail **tamper-evident with a per-organization HMAC-SHA256 hash chain**:
- Each `audit_event` is append-only and carries a per-org monotonic `sequenceNo`, the previous event's
  fingerprint `prevHash`, and its own `entryHash = HMAC(orgKey, canonical(event, prevHash))`.
- The canonical serialization + HMAC live in the pure, DB-free `AuditHashChain` (shared by writer and verifier).
- Appends serialize per org under a `PESSIMISTIC_WRITE`-locked `audit_chain_head` holding the chain tip.
- The per-org key is **derived** (`AuditSigningKeys`: `HMAC(masterSecret, orgId)`) from a master secret held in
  **configuration, never in the database it protects** — so tampering with `audit_event` alone can't forge a valid
  fingerprint.
- `GET /api/v1/audit-events/verify` recomputes the chain and reports the first break.
- The audit row is written **in the same transaction** as the action it records (`AuditService.record` joins the
  caller's tx).

## Consequences
- ✅ Any modify/delete/reorder/insert/truncate of audit rows is detectable; each org's chain is independent.
- ✅ A DB-only attacker cannot forge the chain without also holding the config master secret.
- ⚠️ The master secret is currently a **config value** with an env-overridable dev default; a production
  deployment should source it from a KMS/HSM (documented limitation, not a design change).
- ⚠️ Audit rows are **permanent** — never purged, since deleting one would break the chain (data retention
  therefore targets only operational data, and the purge is itself audited).
