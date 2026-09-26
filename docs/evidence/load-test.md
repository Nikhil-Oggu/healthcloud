# Evidence — Load Test (local, single-node)

> Captured 2026-09-25 with [k6](https://k6.io) against the backend running locally.
> **Scope caveat (project rule #2 — no misrepresented claims):** these numbers describe a
> **local, single-node development setup**, *not* a production-sized deployment. They are an
> honest measure of the app's read-path behavior on one developer machine — nothing more.
> They are included because a *real measured* number is worth more than an invented one.

## Dashboard

k6's built-in web dashboard (`K6_WEB_DASHBOARD=true`), full report for the run captured below. The
**Overview** charts show the **request rate** (green) ramping to the 50-VU plateau and holding, **p95
request duration** (blue) staying low, and **request failures** (purple) flat at zero. The **Timings**
section breaks latency into per-phase percentiles (waiting, connecting, sending, receiving, TLS), and
the **Summary** trends table lists every metric — the classic healthy load-test shape end to end.

![k6 web dashboard (full report) — Overview charts, per-phase Timings, and the Summary trends table](screenshots/load-test-k6-dashboard.png)

## What was tested

The **authenticated read path** — the flow a real user drives most often:

1. `POST /api/v1/dev-login` — establish a session (once per virtual user)
2. `GET /api/v1/me` — resolve identity, roles, tenant on the backend
3. `GET /api/v1/patients?page=0&size=20` — tenant-scoped + relationship-gated list
4. `GET /api/v1/claims?page=0&size=20` — tenant-scoped work-queue list

This exercises the **full authorization pipeline** (tenant → role → relationship gate) plus
**real PostgreSQL reads** with server-side pagination — not a trivial health-check ping.

Script: [`../../perf/k6/read-path.js`](../../perf/k6/read-path.js) · How to run: [`../../perf/k6/README.md`](../../perf/k6/README.md)

## Load profile

`ramping-vus` executor: 0 → **50** virtual users over 30s, hold 50 for 60s, ramp to 0 over 30s
(~2 minutes total), with 1s think-time between each user's iterations.

## Environment

| Piece | Detail |
|---|---|
| App | Spring Boot 4.1 backend via `mvnw spring-boot:run` (`local` profile), single JVM |
| Database | PostgreSQL 17 in Docker (local) |
| Data | Synthetic seed data |
| Load generator | k6 in Docker (`grafana/k6`), same machine |
| Host | Apple-Silicon Mac (developer laptop) |

Single-node, everything on one machine — so the app and the load generator share CPU. A real
deployment would separate them and be sized very differently.

## Results (measured)

| Metric | Value |
|---|---|
| Total requests | **12,896** (0 failed) |
| Error rate | **0.00%** |
| Throughput | **~107 requests/sec** |
| Latency — average | 18.4 ms |
| Latency — median | 15.0 ms |
| Latency — p90 | 29.2 ms |
| Latency — **p95** | **39.8 ms** |
| Latency — p99 | 77 ms |
| Latency — max | 368 ms |
| Completed user iterations | 4,282 |
| Peak concurrent users | 50 |
| Data received / sent | 9.82 MB / 2.61 MB |

Both k6 thresholds passed: `http_req_failed rate<0.01` (actual 0.00%) and
`http_req_duration p(95)<800ms` (actual 39.8ms).

### Raw k6 summary

```
  █ TOTAL RESULTS

    checks_total.......: 12896   107.072029/s
    checks_succeeded...: 100.00% 12896 out of 12896
    checks_failed......: 0.00%   0 out of 12896

    ✓ login is 200
    ✓ me is 200
    ✓ patients is 200
    ✓ claims is 200

    HTTP
    http_req_duration..............: avg=18.37ms min=3.37ms med=15.04ms max=367.67ms p(90)=29.23ms p(95)=39.8ms
    http_req_failed................: 0.00%  0 out of 12896
    http_reqs......................: 12896  107.072029/s

    EXECUTION
    iteration_duration.............: avg=1.05s   min=1.01s  med=1.04s   max=1.52s    p(90)=1.08s  p(95)=1.11s
    iterations.....................: 4282   35.552298/s
    vus_max........................: 50
```

## What this proves (and what it does not)

**Proves:** under 50 concurrent users on a single dev machine, the authenticated read path —
including the full authorization pipeline and real Postgres reads — served ~107 req/s with a
**p95 of 39.8ms and zero errors**. The app is correct and responsive under modest concurrent load.

**Does NOT prove:** production throughput, latency at scale, or any SLA. Real capacity depends on
production-sized infrastructure (instance sizes, connection pool, data volume, network) that this
setup deliberately does not model.

## Reproduce

```bash
# Backend on the local profile (seeds data + dev-login), Postgres in Docker:
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# From the repo root, run k6 in Docker:
docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -v "$PWD/perf/k6:/scripts" grafana/k6 run /scripts/read-path.js
```

## Honest note on the first run

The first attempt reported a 98% error rate — **a bug in the test script, not the app**: k6 resets
its cookie jar between iterations, so after each virtual user's first iteration the session cookie
was dropped and reads returned 401. The fix stashes the `SESSION` cookie value in per-VU state and
re-applies it each iteration (modeling "log in once, then browse"). The numbers above are from the
corrected run.
