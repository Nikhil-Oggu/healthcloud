# PROGRESS.md — HealthCloud running log

> The **diary** of the project. Updated at the end of every session (by the `/wrap` command once it
> exists, or manually). Read this + `CLAUDE.md` + `docs/PLAN.md` at the start of every session.

## Current position
- **Phase:** 0 ✅ · Environment ✅ · Phase 1 slices 1–4 ✅ complete
- **Repo:** https://github.com/Nikhil-Oggu/healthcloud (private, branch `main`)
- **Next up:** **Phase 1, slice 5** — backend-derived request-scoped tenant/user context + the global
  error-handling model (`{code, message, correlationId, details}`), building on `/me`. Then the
  React frontend shell (first visible UI). Plan the slice first, then build.
- **Run the demo:** `docker compose up -d postgres` then
  `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
  Log in: `curl -c j.txt -X POST localhost:8080/api/v1/dev-login --data email=admin@greenvalley.example.org`
  then `curl -b j.txt localhost:8080/api/v1/me`. Reset DB with `./scripts/db-reset.sh`.

## Log (newest first)

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
