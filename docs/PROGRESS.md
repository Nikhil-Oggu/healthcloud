# PROGRESS.md — HealthCloud running log

> The **diary** of the project. Updated at the end of every session (by the `/wrap` command once it
> exists, or manually). Read this + `CLAUDE.md` + `docs/PLAN.md` at the start of every session.

## Current position
- **Phase:** 0 ✅ · Environment ✅ · Phase 1 COMPLETE ✅ · **Phase 2 slice 1 ✅ (patient profile)**
- **Repo:** https://github.com/Nikhil-Oggu/healthcloud (private, branch `main`)
- **Next up:** **Phase 2, slice 2** — the natural companions to the patient read model: provider profiles +
  assignments (`provider`, `provider_patient_assignment`, `care_coordinator_assignment`) and/or the
  patient **write** path (POST/PATCH create-update, stamping org from context) and a small frontend
  Patients screen. Then service requests + the state machine (later slices). Plan the slice first, then build.
- **Run the frontend:** with Postgres + backend up, `cd frontend && npm run dev` → open
  http://localhost:5173 → sign in as a seeded demo user.
- **Run the demo:** `docker compose up -d postgres` then
  `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
  Log in: `curl -c j.txt -X POST localhost:8080/api/v1/dev-login --data email=admin@greenvalley.example.org`
  then `curl -b j.txt localhost:8080/api/v1/me`. Reset DB with `./scripts/db-reset.sh`.

## Log (newest first)

### 2026-09-13 — Phase 2, slice 1 ✅ (patient profile — first tenant-owned business resource)
- **`db/migration/V5__patient.sql`:** `patient` table — tenant key `organization_id`, synthetic MRN,
  `full_name`, `date_of_birth`, status, `@Version`. Constraints: `UNIQUE(organization_id, mrn)` and
  `UNIQUE(id, organization_id)` (so future rows like `service_request` can use a **composite FK**
  including org id — §32.10, a bug can't cross tenants); index `(organization_id, status)` (§32.11).
- **`com.healthcloud.patient`:** `Patient` (holds `organizationId` as a plain UUID tenant key),
  `PatientRepository` (tenant-safe by design — every finder takes `organizationId`, no bare
  `findById` for business code), `PatientService` (reads via `UserContextAccessor.requireOrganizationId()`;
  cross-tenant → `NotFoundException` = **secure 404, not 403**), thin `PatientController`
  (`GET /api/v1/patients`, `GET /api/v1/patients/{id}`), `PatientDto`.
- **Seed:** `DevDataSeeder` now adds 3 synthetic patients per org (MRN prefixes `NC-`/`GV-`).
- **Fulfilled the Phase-1 deferral:** `PatientApiIntegrationTest` proves a NorthCare caller requesting a
  **real Green Valley patient id → 404** (code `NOT_FOUND`, no data leak), own-tenant read → 200, each
  tenant lists only its own patients, and unauth → 401. `PatientRepositoryTest` proves the
  `(id, organizationId)` lookup denies cross-tenant reads.
- **Verified:** `./mvnw -B verify` → **29 tests pass** (+1 repo, +3 API) and the jar packages.
- **Deferred on purpose (per §34.5):** patient access here is **tenant-scoped only**. Object-relationship
  checks (provider↔patient assignment) come with assignments; consent/purpose + **field-level masking**
  are Phase 3; audit + outbox events on access arrive with the §31.6 one-transaction write path (Ph 3/7/8);
  patient create/update (POST/PATCH) and the frontend Patients screen are the next slice.

### 2026-09-13 — Phase 1, slice 7 ✅ (CI pipeline + cross-tenant isolation proof) — **Phase 1 done**
- **First GitHub Actions CI** (`.github/workflows/ci.yml`), on push + PR to `main`, two independent jobs:
  **backend** (Temurin JDK 25 + Maven cache → `./mvnw -B verify`; runner's Docker powers the
  Testcontainers/real-Postgres tests) and **frontend** (Node 24 + npm cache → `npm ci` → `typecheck`
  → `test` → `build`). `concurrency` cancels superseded runs on the same ref.
- **Cross-tenant isolation acceptance proof** (§60), two layers, honestly scoped for Phase 1:
  - **HTTP** `TenantIsolationIntegrationTest` (RANDOM_PORT): each session sees only its own org via
    `/me` (NorthCare user never sees Green Valley and vice-versa); a spoof attempt with
    `?organizationId=…` + `X-Organization-Id` header is **ignored** — the tenant is derived from the
    session, so a browser cannot pick a different tenant.
  - **Tenant-key** `TenantIsolationRepositoryTest`: the `(organizationId, id)` lookup pattern every
    Phase-2 endpoint will use returns nothing for another tenant's row, and scoped listings never leak.
- **Verified:** `./mvnw -B verify` → **25 tests pass** (+2 HTTP, +1 repo) and the app jar packages;
  frontend `npm ci` + `typecheck` + `test` (3 pass) + `build` all green locally with the exact CI commands.
  The GitHub Actions run itself is verified on the first push (watched, not assumed).
- **Deferred on purpose (recorded):** the full "fetch another tenant's *business* record → secure 404"
  test needs a tenant-owned resource endpoint, which arrives with Phase 2's service requests. At Phase 1
  the only tenant-owned surface is the caller's own identity (`/me`), which is what the proof asserts.

### 2026-09-12 — Phase 1, slice 6 ✅ (React frontend shell — first visible UI)
- Scaffolded `frontend/`: Vite 8 + React 19 + TS 6, React Router 7, TanStack Query 5, MUI 9, Vitest + RTL.
- **Same-origin dev setup:** Vite proxies `/api` + `/actuator` → `:8080`, so the HttpOnly `SESSION` and
  readable `XSRF-TOKEN` cookies are first-party — no CORS, no tokens in JS (matches the BFF model).
- `api/client.ts`: typed `fetch` wrapper, `ApiClientError` (carries `code`/`correlationId`), auto-injects
  `X-XSRF-TOKEN` on state-changing calls. `useCurrentUser()` queries `/me` (401/403 not retried).
- Screens: `LoginPage` (dev-login dropdown of the 10 seeded users), `AppLayout` (top bar: email · org ·
  role chips · logout; role-aware nav placeholders), `HomePage` (identity card), `ProtectedRoute`
  (spinner → redirect to /login on 401 → else render), plus `ErrorScreen` (shows message + correlationId)
  and `DeniedPage`/`NotFoundPage`.
- **Verified:** `npm run typecheck` clean; `npm test` → **3 Vitest tests pass** (org/roles render, 401→login
  redirect, ErrorScreen shows correlationId). **Live in-browser:** unauth `/`→`/login`; sign in as
  `provider@northcare.example.org` → shell shows *NorthCare Health / PROVIDER / Dana Provider*; **logout POST
  returned 200** (CSRF header handshake works) → back to /login.
- Added `.claude/launch.json` (frontend dev server). Gotcha: MUI 9 `Stack` `alignItems` as a direct prop
  can fail typecheck with mixed children — put it in `sx` instead.

### 2026-09-12 — Phase 1, slice 5 ✅ (backend-derived request context + global error model)
- **`com.healthcloud.context`:** `UserContext` (immutable snapshot: user, org/tenant, roles),
  `UserContextFilter` (resolves the session principal → context, added after `AuthorizationFilter`,
  cleared per request), `UserContextAccessor` (`current()`, `requireUser()` → 401,
  `requireOrganizationId()` → 403). This is the trusted, backend-only source of caller/tenant identity.
- **`com.healthcloud.error`:** `ApiError` `{code, message, correlationId, details}`, `ErrorCode` enum,
  `ApiException`(+`NotFoundException`, `TenantContextRequiredException`), `GlobalExceptionHandler`
  (@RestControllerAdvice: ApiException, validation, missing-param, AccessDenied, catch-all 500 with a
  generic message — no internals leaked). Security-chain 401/403 now emit the same JSON via
  `RestAuthenticationEntryPoint`/`RestAccessDeniedHandler` (replaced the empty-body 401 entry point).
- **`CorrelationIdFilter`** (highest precedence, before security): reuses a *safe* inbound
  `X-Correlation-Id` or generates one, exposes it in the log MDC (`%X{correlationId}`) and echoes it
  on the response; hostile header values are rejected (anti log/response-injection).
- Refactored `/me` to build its DTO from `UserContext` (proves the context populates end-to-end; the
  response contract is unchanged).
- **Verified:** `./mvnw test` → **22 tests pass** (+4 `UserContextAccessorTest`, +4
  `ErrorContractIntegrationTest`; all 14 prior tests still green). Real-server tests confirm: 401 →
  `{code:UNAUTHENTICATED,...}` with an `X-Correlation-Id` header; an inbound id is echoed in header +
  body; an unsafe id is replaced; a missing required param → 400 `VALIDATION_FAILED` with field details.
- **Boot 4.1 gotcha handled:** Jackson 3 → `tools.jackson.databind.ObjectMapper` (annotations stay
  `com.fasterxml.jackson.annotation`). Recorded in CLAUDE.md.
- Not in this slice (later, on purpose): Idempotency-Key + optimistic-lock conflict responses (arrive
  with Phase 2 write endpoints); the React error UI (frontend shell slice).

### 2026-09-12 — Phase 1, slice 4 ✅ (authentication foundation: login + sessions + /me)
- Added `spring-boot-starter-security` + `spring-boot-starter-session-jdbc`; `V4__spring_session.sql`.
- `SecurityConfig`: session-based, 401 entry point (no redirect), CSRF via readable cookie +
  `CsrfCookieFilter`, logout at `/api/v1/logout`; `/actuator/health` + `/dev-login` public, rest authenticated.
- `DevLoginController` (`@Profile local`): email-only session login stand-in (NO password/MFA — ADR-018).
- `CurrentUserController` `/api/v1/me` + `CurrentUserService` → user → active membership → org → roles.
- **Verified:** `./mvnw test` → 14 tests pass (4 MockMvc logic + 1 real-server session lifecycle).
  Live: dev-login sets HttpOnly SESSION cookie, /me returns correct org/roles, session row in
  `spring_session`, /me without cookie → 401.
- ADR-018 records the dev-login stand-in and its honest limitations (Cognito+MFA later).
- Boot 4.1 gotchas handled: test annotations moved packages (`AutoConfigureMockMvc` →
  `...webmvc.test.autoconfigure`); Spring Session needs the **starter** module, not the raw library;
  session-cookie flow must be tested with a real server (RANDOM_PORT + JDK HttpClient), not MockMvc.

### 2026-09-12 — Phase 1, slice 3 ✅ (facilities + NorthCare/Green Valley demo seed)
- Flyway `V3__facilities.sql`: `facility`, `facility_membership` (+ entities/repositories in
  `com.healthcloud.organization`).
- `DevDataSeeder` (`@Profile("local")`, idempotent): seeds NorthCare Health + Green Valley Clinic,
  each with 1 facility and 5 users (patient/provider/coordinator/reviewer/admin) with memberships,
  roles, and facility links for provider/coordinator.
- `scripts/db-reset.sh`: wipe DB volume + fresh Postgres → deterministic reseed on next local run.
- **Verified:** `./mvnw test` → 9 tests pass (added 4 seeder tests). Ran app with `local` profile
  against dev DB and confirmed via psql: 2 orgs, 5 members each, correct roles/facilities.
- Gotcha handled: a stale slice-1 app instance was still on :8080; ensure port is free before
  launching, and wait for the seeder log line rather than a possibly-stale health response.

### 2026-09-12 — Phase 1, slice 2 ✅ (identity & organization data model)
- Flyway `V2__identity_and_organization.sql`: `organization`, `app_user`, `role` (7 roles seeded),
  `organization_membership` (tenant key), `user_role`. UUID PKs, `@Version` columns, unique email
  (case-insensitive), one-ACTIVE-membership-per-user partial unique index, tenant FKs.
- JPA entities + Spring Data repositories in `com.healthcloud.organization` and `com.healthcloud.identity`.
- Testing pattern established: **Testcontainers** (real PostgreSQL 17) via shared
  `TestcontainersConfiguration` + `@ServiceConnection`; tests never use the dev DB (CI-friendly).
- **Verified:** `./mvnw test` → 5 tests pass. Proves roles seeded, org/user/membership persist,
  org-scoped query returns only that tenant's rows, duplicate email rejected, second active membership rejected.
- Note: Testcontainers is **2.0.5** in Boot 4.1 → artifacts are `testcontainers-junit-jupiter` /
  `testcontainers-postgresql` (renamed with prefix in TC 2.0).

### 2026-09-12 — Phase 1, slice 1 ✅ (bootable app + Postgres + Flyway)
- Generated Spring Boot **4.1.0** / Java **25** project into `backend/` (Web, Actuator, Data JPA,
  PostgreSQL, Flyway, Validation) via Spring Initializr; Maven wrapper (`mvnw`).
- `application.yml`: DB connection, Flyway enabled, actuator health with DB details; `ddl-auto: validate`.
- `docker-compose.yml`: real `postgres:17` service (volume + healthcheck). `.env.example` added.
- First migration `V1__baseline.sql` (creates `platform_metadata`).
- **Verified:** `docker compose up -d postgres` (PostgreSQL 17.11 healthy) → `./mvnw spring-boot:run`
  → `/actuator/health` = UP with `db: UP` → Flyway V1 applied (success) → `platform_metadata` row present.
- Decision: local dev runs Postgres in Docker + app via `mvnw`; backend containerized later (Phase 10).

### 2026-09-12 — Phase 0 started
- Created clean repo root `~/Desktop/healthcloud` (lowercase, no space).
- Renamed `documents/` → `docs/`; source-of-truth PDF now at `docs/source-of-truth/`.
- Built full monorepo skeleton (backend/frontend/worker/infrastructure/api/docs/synthetic-data/scripts/.github).
- Wrote `CLAUDE.md` (rulebook), `docs/PLAN.md` (roadmap), this `docs/PROGRESS.md`.
- Drafted first ADRs (001 modular monolith, 002 shared-DB multi-tenancy, 004 Cognito+BFF) + ADR template/index.
- Added `README.md`, `.gitignore`, `docker-compose.yml` placeholder, OpenAPI + event-catalog stubs.
- `git init`, first commit, created **private** GitHub repo `Nikhil-Oggu/healthcloud`, pushed `main`. ✅
- Phase 0 complete.

## Environment status (2026-09-12) — ALL READY ✅
- ✅ Git, GitHub CLI (logged in as `Nikhil-Oggu`), VS Code, git identity.
- ✅ Java `25.0.4` (Homebrew `openjdk@25`), `javac` 25 — JAVA_HOME + PATH set in `~/.zshrc`.
- ✅ Node `24.21.0` (Homebrew `node@24`) + npm `11.19` — on PATH via `~/.zshrc`.
- ✅ Docker Desktop: daemon running (v29.7.2, aarch64), `hello-world` container ran; Compose v5.5.1.
- Maven: not global — using the per-project Maven wrapper (`mvnw`) instead, per baseline.
- Note: `~/.zshrc` created with a `HealthCloud dev env` block (openjdk@25 + node@24 on PATH); Docker Desktop appended its CLI-completions block.

## Definition of "slice done"
Functionality works + negative/security cases pass + tests written + docs updated + committed + this file updated.
