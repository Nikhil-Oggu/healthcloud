# ADR-011 — Concurrency Strategy
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 2 / Phase 5)

## Context
Two kinds of concurrent access need different guarantees. **Workflow aggregates** (requests, claims, consent,
assignments) can be edited by more than one user; a naïve last-write-wins would silently discard someone's change.
**Financial accumulators** (a patient's met deductible / out-of-pocket per plan-year) are read-modify-written by
the adjudication engine; a lost update here corrupts money.

## Decision
Use **two mechanisms, matched to the risk**:
- **Optimistic locking for workflow aggregates.** Entities carry a JPA `@Version` (36 versioned entities), and
  state-changing endpoints take a client-supplied `expectedVersion`; a mismatch is a clean `409 CONFLICT` rather
  than a silent overwrite. This suits the low-contention, user-driven edit path and doubles as double-apply safety
  for retriable state changes.
- **Pessimistic row locks for financial accumulators.** The engine reads-and-updates `benefit_accumulator` under a
  `PESSIMISTIC_WRITE` (`SELECT … FOR UPDATE`) lock (`BenefitAccumulatorRepository.lockByKey`), with an
  insert-if-absent (`ON CONFLICT DO NOTHING`) before the locked read so concurrent adjudications for the same
  patient/plan/year serialize without a lost update. The same pattern locks the `audit_chain_head` tip (ADR-010).

## Consequences
- ✅ No lost updates: workflow edits fail loudly on stale versions; money is serialized correctly under contention.
- ✅ Optimistic locking keeps the common path lock-free and cheap; pessimistic locks are reserved for the few rows
  where correctness demands serialization.
- ⚠️ Clients must send `expectedVersion` on state changes (a documented convention); a `409` is expected and
  retriable.
- ⚠️ Row locks add contention on a hot accumulator row, acceptable at this scale and scoped to the shortest
  possible critical section (inside the adjudication transaction only).
