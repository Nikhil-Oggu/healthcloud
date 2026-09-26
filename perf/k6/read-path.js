// HealthCloud — k6 load test: authenticated read path
//
// Simulates real user sessions against the running backend. Each virtual user
// (VU) logs in ONCE via the local dev-login stand-in, then repeatedly reads:
//   GET /api/v1/me            (identity/role/tenant resolution)
//   GET /api/v1/patients      (tenant-scoped + relationship-gated list)
//   GET /api/v1/claims        (tenant-scoped work-queue list)
// This exercises the full authorization pipeline + real Postgres reads, not
// just a trivial health-check ping.
//
// Honest-metrics note (project rule #2): the numbers this produces describe a
// LOCAL, SINGLE-NODE dev setup (app via `mvnw spring-boot:run`, Dockerized
// Postgres, synthetic seed data) — NOT a production-sized deployment. Always
// report them with that context.
//
// Run (Docker, no host install needed):
//   docker run --rm -i --add-host=host.docker.internal:host-gateway \
//     -v "$PWD/perf/k6:/scripts" grafana/k6 run /scripts/read-path.js
//
// Tunables via env: BASE_URL, LOGIN_EMAIL, VUS, DURATION.

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const EMAIL = __ENV.LOGIN_EMAIL || 'provider@northcare.example.org';
const PEAK_VUS = Number(__ENV.VUS || 50);

export const options = {
  scenarios: {
    read_path: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: PEAK_VUS }, // ramp up
        { duration: '1m', target: PEAK_VUS },  // hold at peak
        { duration: '30s', target: 0 },        // ramp down
      ],
      gracefulRampDown: '10s',
    },
  },
  // Thresholds are demo TARGETS (not measured SLOs). A breach marks the run
  // failed but still prints the real measured numbers, which is what we want.
  thresholds: {
    http_req_failed: ['rate<0.01'],       // <1% of requests error
    http_req_duration: ['p(95)<800'],     // 95% of requests under 800ms
  },
};

// k6 RESETS the cookie jar between iterations, but JS module state persists for
// the VU's lifetime. So we log in once per VU, stash the SESSION cookie VALUE in
// a per-VU variable, and re-apply it to the (freshly reset) jar every iteration.
// This models "a user logs in once, then browses many times" and creates one
// session per VU rather than one per request.
let sessionCookie = null;

function login() {
  const res = http.post(`${BASE}/api/v1/dev-login`, { email: EMAIL });
  check(res, { 'login is 200': (r) => r.status === 200 });
  const c = res.cookies['SESSION'];
  if (c && c.length) sessionCookie = c[0].value;
}

export default function () {
  if (!sessionCookie) {
    login();
  }
  // Re-apply the session cookie to this iteration's jar (it was reset).
  if (sessionCookie) {
    http.cookieJar().set(BASE, 'SESSION', sessionCookie);
  }

  const me = http.get(`${BASE}/api/v1/me`);
  check(me, { 'me is 200': (r) => r.status === 200 });

  const patients = http.get(`${BASE}/api/v1/patients?page=0&size=20`);
  check(patients, { 'patients is 200': (r) => r.status === 200 });

  const claims = http.get(`${BASE}/api/v1/claims?page=0&size=20`);
  check(claims, { 'claims is 200': (r) => r.status === 200 });

  sleep(1); // model think-time between a user's page views
}
