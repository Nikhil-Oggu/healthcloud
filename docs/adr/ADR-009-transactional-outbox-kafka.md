# ADR-009 — Transactional Outbox + Kafka
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 8)

## Context
Important state changes need to emit domain events (e.g. `claim.adjudicated`) for asynchronous consumers. But a
Kafka publish cannot join a database transaction — the classic **dual-write problem**: if we write the DB and
then publish, a crash between them either loses the event or (if we publish first) emits an event for a change
that rolled back.

## Decision
Use the **transactional outbox** pattern:
- `OutboxService.record(...)` writes an `outbox_event` row **inside the domain service's own `@Transactional`
  method**, so the event commits atomically with the business state (the "+ outbox event" of the one-transaction
  rule).
- A scheduled `OutboxRelay` polls committed, unpublished rows and publishes them to Kafka **after commit**, then
  stamps `published_at`.
- The consumer is **idempotent** — it dedupes on `event_id` (`existsByEventId`) with a `UNIQUE(event_id)`
  backstop — because delivery is at-least-once.
- Failures flow through bounded retry/backoff → a dead-letter topic → drain into `dead_letter_event` → inspect →
  ORG_ADMIN replay.

## Consequences
- ✅ No lost events and no events for rolled-back changes; exactly the guarantee the naïve approach can't give.
- ✅ At-least-once delivery made safe by consumer idempotency; poison messages are isolated to the DLT rather than
  blocking the partition.
- ⚠️ **Single-instance relay** today (a scheduled poller); a multi-instance deployment needs
  `SELECT … FOR UPDATE SKIP LOCKED`.
- ⚠️ Eventual (not immediate) consistency for consumers.
- ⚠️ On AWS the event bus is **not** deployed (no MSK — see ADR-015); the full pipeline is proven locally with
  Kafka in KRaft mode.
