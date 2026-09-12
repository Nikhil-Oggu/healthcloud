# Event Catalog

> PLACEHOLDER (Phase 0). The transactional outbox + Kafka event model is built in **Phase 8**.
> This catalog will list each event: name, trigger, partition key, schema, consumers, and
> idempotency/retry/DLQ behavior. Event payloads carry **minimum necessary data only** — no sensitive
> clinical or document content.

## Planned events (illustrative — finalized in Phase 8)
| Event | Emitted when | Consumers |
|-------|--------------|-----------|
| request.status-changed | A service request transitions state | notifications, SLA |
| claim.finalized | A claim reaches a final adjudication outcome | notifications, reporting |
| document.scanned | A document's malware scan completes | document workflow |
| breakglass.activated | Emergency access is granted | audit, review workflow |
