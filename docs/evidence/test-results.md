# Evidence — Test Suite Results

> A fresh full-suite run captured for the evidence pack. Numbers below are **measured**, not
> estimated. Synthetic data only.

**Captured:** 2026-09-25
**Toolchain:** Java 25 (Temurin `openjdk 25.0.4.1`), Node 24.21.0, Docker (Testcontainers for the
backend integration tests).

## Summary

| Suite | Command | Tests | Failures | Errors | Skipped | Result |
|-------|---------|------:|---------:|-------:|--------:|--------|
| Backend | `./mvnw -B clean verify` | **511** | 0 | 0 | 0 | ✅ BUILD SUCCESS |
| Frontend | `npx vitest run` (40 files) | **183** | 0 | — | — | ✅ all pass |
| Frontend typecheck | `npm run typecheck` (`tsc --noEmit`) | — | — | — | — | ✅ clean |
| Frontend build | `npm run build` | — | — | — | — | ✅ built |
| **Total** | | **694** | **0** | | | ✅ **all green** |

## Backend — `./mvnw -B clean verify`

Runs the full backend suite against a **real PostgreSQL** (and a real Kafka broker for the
event-driven tests) provisioned by **Testcontainers** — no mocks for data access — including the
negative security tests (`TenantIsolation*`, secure-404, consent masking, invalid transitions) that
are the project's core acceptance proof.

```
[INFO] Tests run: 511, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  03:18 min
```

## Frontend — `npx vitest run`

Vitest + React Testing Library across every feature area, including the `axe-core` accessibility
assertions (`expectNoAxeViolations`) added in the Phase 9 WCAG pass.

```
 Test Files  40 passed (40)
      Tests  183 passed (183)
```

Type safety and the production build:

```
$ npm run typecheck   # tsc --noEmit  → exit 0, no errors
$ npm run build       # vite build     → built (dist/ produced)
```

## Reproduce

```bash
# toolchain (non-interactive shell)
export JAVA_HOME="/opt/homebrew/opt/openjdk@25"
export PATH="/opt/homebrew/opt/openjdk@25/bin:$PATH"

# backend (Docker must be running — Testcontainers)
cd backend && ./mvnw -B clean verify

# frontend
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
cd frontend && npm ci && npm run typecheck && npx vitest run && npm run build
```

## Honesty note

When the backend `verify` and the frontend `vitest` runs were executed **concurrently**, one
frontend test (`PatientDetailPage` document-download) hit its default async `findByText` timeout
because the two suites were saturating the CPU. Re-run **without** that contention, the file passes
18/18 and the full frontend suite passes 183/183 (the numbers reported above). This is a
load-induced test-timing artifact, not a product or test regression — noted here rather than hidden,
per the project's no-unmeasured-claims rule.
