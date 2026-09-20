# Runbook — Alert response

> What to do when a HealthCloud Prometheus alert fires (Phase 11 slice 7). One section per rule in
> `infrastructure/observability/alert-rules.yml`; each rule's `runbook` annotation links here. Read
> [README.md](README.md) first for the observability stack and the general triage workflow. All data is
> **synthetic**; the stack is local (see the scope note in the README).

Check any alert's live state at http://localhost:9090/alerts. A rule is `pending` while its `for:` window
elapses, then `firing`. Recovery below is written for the local dev stack; production (AWS) equivalents are noted.

---

## BackendTargetDown

**Means:** Prometheus cannot scrape `up{job="healthcloud-backend"}` — the backend is down, crashed, or
unresponsive for >1 minute. **Severity: critical.**

**Confirm**
```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/health/liveness   # expect 200
curl -sG localhost:9090/api/v1/query --data-urlencode 'query=up{job="healthcloud-backend"}'  # value "1" = up
docker compose ps                                                                   # is the process/container running?
```

**Likely causes**
- The app process isn't running (not started, crashed on boot, or OOM-killed).
- It's up but wedged (thread/deadlock) so the scrape times out.
- The scrape target/port is wrong (only if scrape config changed).

**Recover**
1. If the process is stopped, start it: `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
2. Check startup logs for a boot failure (Flyway/DB connection): the DB must be up — `docker compose ps postgres`;
   `curl -s localhost:8080/actuator/health/readiness` shows the `db` component.
3. If it boots then dies, look for OOM (see [JvmHeapHigh](#jvmheaphigh)) or a stack trace in the logs.
4. **Production:** confirm the ECS task is `RUNNING` and healthy and that RDS is reachable; a task that fails its
   health check is replaced automatically — check the service events / task stopped-reason.

**Resolved when** `up == 1` and the alert returns to `inactive`.

---

## OutboxBacklogHigh

**Means:** more than 100 outbox events are unpublished (`healthcloud_outbox_pending > 100`) for 5 minutes — the
transactional-outbox relay is not draining to Kafka. **Severity: warning.** (The `outbox` health component turns
`OUT_OF_SERVICE` at a higher level, 500; this warns earlier.)

**Confirm**
```bash
curl -sG localhost:9090/api/v1/query --data-urlencode 'query=healthcloud_outbox_pending'   # the backlog
curl -s localhost:8080/actuator/health | python3 -m json.tool | grep -A5 '"outbox"'        # relay + pending detail
```

**Likely causes**
- **Kafka is down/unreachable** — the relay can't publish (the most common local cause).
- The relay is disabled (`healthcloud.outbox.relay.enabled=false`) — then publishing is intentionally off; a
  backlog is expected and this alert can be ignored in that configuration.
- Publishing errors (poison payloads) parking messages — inspect the dead-letter feed.

**Recover**
1. Bring Kafka up: `docker compose up -d kafka`; the relay poller resumes and the backlog drains
   (`healthcloud_outbox_pending` falls). Watch it trend down in Prometheus.
2. Confirm the relay is enabled and its scheduler is running (relay logs; `healthcloud.outbox.relay.enabled`).
3. For messages that failed processing, inspect + replay via the dead-letter UI at `/dead-letters`
   (`GET /api/v1/dead-letter-events` → Replay, ORG_ADMIN) — the consumer is idempotent, so replay is safe.
4. **Production:** there is no MSK on AWS (event-driven is proven locally), so this alert applies to the local
   stack; deploying a broker is out of scope.

**Resolved when** the backlog drops back under threshold and the alert returns to `inactive`.

---

## HighHttp5xxRate

**Means:** more than 5% of HTTP responses were 5xx over the last 5 minutes, sustained 10 minutes. **Severity:
warning.**

**Confirm — which endpoint is erroring?**
```bash
# 5xx request rate broken down by uri/status:
curl -sG localhost:9090/api/v1/query --data-urlencode \
  'query=sum by (uri,status) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))'
```
Then open the Grafana **HealthCloud Overview** dashboard for the error/latency panels.

**Localize the failure**
- Reproduce or find a failing request and read its `X-Correlation-Id` response header.
- `grep 'cid=<that id>'` in the logs to see the exception + its `traceId`.
- Open that trace in **Jaeger** (http://localhost:16686) to see which span failed.

**Likely causes & recover**
- A downstream dependency failing (DB) → check `/actuator/health` (`db` component) and DB logs.
- A regression in a specific endpoint → the logged stack trace + trace pinpoint it; fix forward.
- Note: error responses use the app's uniform `{code,message,correlationId,details}` shape — the `correlationId`
  is your join key to logs/traces.

**Resolved when** the 5xx ratio falls below 5% and the alert returns to `inactive`.

---

## HighRequestLatencyP95

**Means:** the p95 of HTTP request duration exceeded 1s for 10 minutes (computed from the request histogram
enabled in slice 2). **Severity: warning.**

**Confirm — where is the latency?**
```bash
# p95 latency by endpoint:
curl -sG localhost:9090/api/v1/query --data-urlencode \
  'query=histogram_quantile(0.95, sum by (le,uri) (rate(http_server_requests_seconds_bucket[5m])))'
```

**Localize**
- Open a slow request's **trace in Jaeger** — the span breakdown shows whether time went to the DB, the
  `adjudicate-claim` span, or elsewhere.
- Check DB/connection-pool pressure: HikariCP metrics (`hikaricp_connections_*`, e.g. pending/active) — pool
  exhaustion shows as requests waiting for a connection.

**Likely causes & recover**
- Connection-pool exhaustion → check for slow queries / a leaked connection; scale the pool or fix the query.
- GC pauses → see [JvmHeapHigh](#jvmheaphigh) and the `jvm_gc_*` metrics.
- A slow dependency or an N+1 query surfaced by the trace → fix forward.

**Resolved when** p95 drops back under 1s and the alert returns to `inactive`.

---

## JvmHeapHigh

**Means:** JVM heap used has been above 90% of max for 10 minutes — memory pressure (a leak or
under-provisioning). **Severity: warning.**

**Confirm**
```bash
curl -sG localhost:9090/api/v1/query --data-urlencode \
  'query=sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"})'   # fraction 0..1
```
Also look at `jvm_gc_pause_seconds_*` and the heap-usage panel in Grafana — a sawtooth that keeps climbing after
GC suggests a leak; consistently high with healthy GC suggests under-provisioning.

**Recover**
1. **Immediate:** restart the process to relieve pressure (`spring-boot:run`, or the container/ECS task).
2. **Provisioning:** raise the heap via `JAVA_OPTS` (the Dockerfile exposes it, e.g. `-Xmx`), or give the
   container/task more memory.
3. **Leak:** if usage climbs back after restart, capture a heap dump and investigate the growing retained set;
   fix forward.
4. **Production:** check the ECS task memory sizing; an OOM shows as a task killed with an OutOfMemory
   stopped-reason, which then trips [BackendTargetDown](#backendtargetdown).

**Resolved when** heap usage falls below 90% and the alert returns to `inactive`.

---

## See also

- [README.md](README.md) — the observability stack, the three signals, the general triage workflow.
- [backup-and-restore.md](backup-and-restore.md) — recovering the database (backup, restore drill, RDS DR).
- `infrastructure/observability/alert-rules.yml` — the alert definitions (each links back here via its `runbook`
  annotation).
