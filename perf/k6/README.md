# Load testing (k6)

Local load tests for HealthCloud, using [k6](https://k6.io). These produce **honest,
measured** latency/throughput numbers for the **local, single-node dev setup** — never
treat them as production-sized figures (project rule #2: no unmeasured/misrepresented claims).

## Prerequisites

- The backend running on the `local` profile (seeds demo data + enables `dev-login`):
  ```bash
  cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
  ```
  (Postgres via `docker compose up -d postgres`.)
- Docker (k6 runs as a container — no host install needed).

## Run

From the repo root:

```bash
docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -v "$PWD/perf/k6:/scripts" grafana/k6 run /scripts/read-path.js
```

The app is reached at `http://host.docker.internal:8080` from inside the container.

### Tunables (env)

| Var | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://host.docker.internal:8080` | Target base URL |
| `LOGIN_EMAIL` | `provider@northcare.example.org` | dev-login user |
| `VUS` | `50` | Peak concurrent virtual users |

Example: `-e VUS=100` before the image name to push harder.

## What `read-path.js` does

Each virtual user logs in once (dev-login), then loops over `/me`, the patients list, and
the claims list with 1s think-time — exercising the auth pipeline + real Postgres reads.

## Results

Captured runs live in [`docs/evidence/load-test.md`](../../docs/evidence/load-test.md).
