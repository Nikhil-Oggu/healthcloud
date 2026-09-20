# HealthCloud runbooks

> Operational playbooks (Phase 11 slice 7). Start here when something looks wrong. Everything below refers to
> tooling that actually exists in this repo. All data is **synthetic**.
>
> **Scope / honesty note:** the observability stack (Prometheus, Grafana, Jaeger) runs **locally** (the
> `observability` docker-compose profile); the `/actuator/*` endpoints exist both locally and on the deployed
> app. Wiring Prometheus/Jaeger to the AWS deployment is a documented follow-up (see CLAUDE.md → Observability
> conventions → Deploy), so these runbooks are written for the local stack, with production (AWS) equivalents
> noted where they differ.

## Runbook index

| Runbook | Covers |
|---|---|
| [alert-response.md](alert-response.md) | What to do when a Prometheus alert fires (one section per alert) |
| [backup-and-restore.md](backup-and-restore.md) | Database backup, the restore drill, restoring for real, RDS DR |

## The observability stack at a glance

Bring it up (opt-in — everyday `docker compose up -d postgres kafka` is unchanged):

```bash
docker compose --profile observability up -d prometheus grafana jaeger
```

| Tool | URL | What it answers |
|---|---|---|
| **Prometheus** | http://localhost:9090 | Metrics + alert rule state (`/alerts`, `/rules`); ad-hoc queries |
| **Grafana** | http://localhost:3000 | The auto-provisioned **HealthCloud Overview** dashboard (anonymous Viewer) |
| **Jaeger** | http://localhost:16686 | Distributed traces (e.g. the `adjudicate-claim` span under an HTTP span) |
| **Actuator** | http://localhost:8080/actuator | `health`, `health/liveness`, `health/readiness`, `prometheus`, `metrics`, `info` |

Config lives in `infrastructure/observability/` (Prometheus scrape + `alert-rules.yml`, Grafana provisioning +
dashboard JSON). The app exports metrics at `/actuator/prometheus` and traces over OTLP to Jaeger.

## The three signals (and how they connect)

- **Metrics** (Prometheus/Grafana) — *how much / how often*: request rates, latency percentiles, JVM/DB gauges,
  the domain counter `healthcloud_adjudications_total`, the backlog gauge `healthcloud_outbox_pending`.
- **Traces** (Jaeger) — *where a request spent its time*: each HTTP request is a trace; custom `@Observed` spans
  (e.g. `adjudicate-claim`) nest under it; the trace context propagates through Kafka.
- **Logs** — *what exactly happened*: every log line carries `[cid=<correlationId> trace=<traceId>/<spanId>]`
  (see the `logging.pattern` in `application.yml`), and the API returns the same id in the `X-Correlation-Id`
  response header.

**They connect by id:** a failing response's `X-Correlation-Id` → grep logs for that `cid=` → read its
`traceId` → open that trace in Jaeger. That is the standard triage path.

## General triage workflow

1. **Confirm** the alert in Prometheus — http://localhost:9090/alerts (state `firing` vs `pending`) — and glance
   at the Grafana **HealthCloud Overview** dashboard for the affected signal.
2. **Localize** — which endpoint/component? Break the metric down (e.g. by `uri`/`status`), or check the relevant
   `/actuator/health` component.
3. **Correlate** — from an affected request grab its `X-Correlation-Id` (or find `cid=`/`traceId` in the logs)
   and open the **trace in Jaeger** to see where time/errors occurred.
4. **Recover** — follow the specific steps in [alert-response.md](alert-response.md) for that alert.
5. **Confirm resolution** — the alert returns to `inactive` and the signal is back to normal.

## Health & probes (quick reference)

```bash
curl -s localhost:8080/actuator/health/liveness    # process alive? (container HEALTHCHECK target)
curl -s localhost:8080/actuator/health/readiness   # can it serve traffic? (includes db)
curl -s localhost:8080/actuator/health             # full aggregate (db + outbox + …)
```

- **Liveness** = process only — a failure means restart the process; an external-dependency blip must not.
- **Readiness** = `readinessState` + `db` — DOWN takes the app out of the load balancer (traffic gating).
- **Root health** = the full monitoring aggregate; may report `OUT_OF_SERVICE` (e.g. a big outbox backlog) to
  *signal* degradation without killing anything.
