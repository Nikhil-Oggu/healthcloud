# CLAUDE.md — HealthCloud project rulebook

> This file is read automatically at the start of every session. It holds the **stable rules**
> of the project. It is NOT a diary — what we did and what's next lives in `docs/PROGRESS.md`.

## What this project is
**HealthCloud: Consent-Aware Care Coordination & Claims Platform** — a multi-tenant, production-style
healthcare portfolio application built on **synthetic data only**. Full frozen design is in
`docs/source-of-truth/HealthCloud_Final_Source_of_Truth.pdf` (the single source of truth for every
*what-to-build* decision). The roadmap is `docs/PLAN.md`.

## Non-negotiable rules (from the source-of-truth)
1. **Synthetic data only.** No real patient, employer, client, or production data — ever.
2. **No unmeasured claims.** Never state a performance, security, uptime, cost, or coverage number
   that hasn't actually been measured. Write "target" vs "measured" explicitly.
3. **Healthcare-inspired, HIPAA-aligned — NOT certified.** Never describe HealthCloud as
   HIPAA-certified or production healthcare software.
4. **Backend is the only security boundary.** The frontend may hide/disable UI, but every protected
   operation is authorized on the backend. Never trust browser-supplied tenant/org IDs.
5. **No sensitive data in logs, errors, or event payloads.**

## Working rhythm (how we build)
- **One small verified slice at a time.** Never build multiple phases at once.
- Loop: **read the 3 files → plan the slice → build → verify (run/test) → commit → update PROGRESS.md.**
- The user is new to Claude Code: explain what was done in plain terms; go step by step.
- Every session starts by reading `CLAUDE.md`, `docs/PLAN.md`, and `docs/PROGRESS.md`.

## Build order (MVP = Phase 0–5)
0 Design/scaffold · 1 Foundation & multi-tenant identity · 2 Care-coordination workflow ·
3 Consent/authorization/privacy/documents · 4 Clinical context & claims intake ·
5 Basic adjudication engine  ← **MVP ends here** · 6 Advanced claims · 7 Security/governance ·
8 Event-driven (outbox/Kafka) · 9 Search/reporting/accessibility · 10 AWS deploy & CI/CD ·
11 Observability & recovery · 12 Final validation & portfolio.
**Do not start a phase before its dependencies are stable.**

## Technology stack (frozen baseline — §29)
- **Backend:** Java 25 LTS, Spring Boot 4.1.0, Maven (wrapper), Spring Security, Spring Data JPA,
  Spring Session JDBC, Spring Kafka.
- **Database:** PostgreSQL 17.10, Flyway 12.4 migrations.
- **Frontend:** Node 24 LTS, React 19.2, TypeScript 6.0, Vite 8, React Router 7, TanStack Query 5,
  React Hook Form 7 + Zod 4, Material UI 9.
- **Messaging:** Apache Kafka (KRaft locally; MSK Serverless in AWS validation).
- **Auth:** Amazon Cognito (OIDC auth-code) + Spring Boot BFF; secure HttpOnly session cookie + CSRF.
- **Documents:** private S3 + PostgreSQL metadata. **Cloud:** ECS Fargate, RDS, S3/CloudFront, Terraform.
- **Testing:** JUnit 5, Testcontainers, MockMvc, Mockito (backend); Vitest, React Testing Library
  (frontend); Playwright + axe-core (E2E/accessibility); k6 (perf). **Security:** CodeQL, Dependabot, Trivy, OWASP ZAP.
- Versions are the frozen baseline; patch/minor updates OK via lockfiles + CI. Major upgrades need an ADR.

## Architecture conventions (§31)
- **Modular monolith** (no microservices to start). Shared PostgreSQL DB with an `organization_id`
  tenant key on tenant-owned rows; tenant context is **derived on the backend**, never from the client.
- **Thin controllers.** Business rules, authorization, state transitions, claim math, audit creation,
  and event creation live in dedicated service/domain components. Repositories are tenant-safe by design.
- **One transaction** for important state changes: domain change + status/version history + audit event
  + outbox event, all atomic. Kafka publish happens only after commit.
- **Concurrency:** optimistic locking (version columns) for requests/claims/consent/assignments;
  row locks for financial accumulators.
- **Idempotency:** retriable commands (create request, submit claim, start adjudication) require an Idempotency-Key.
- **Errors:** consistent shape `{code, message, correlationId, details}`; secure 404 for existence-sensitive denials.

## How to build, run & test (local)
Non-interactive shells must set the toolchain first (interactive terminals get it from `~/.zshrc`):
```
export JAVA_HOME="/opt/homebrew/opt/openjdk@25"
export PATH="/opt/homebrew/opt/openjdk@25/bin:/usr/local/bin:/opt/homebrew/bin:$HOME/.docker/bin:$PATH"
```
- **DB up:** `docker compose up -d postgres`  ·  **DB reset (reseed):** `./scripts/db-reset.sh`
- **Run app (seeds demo data):** `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
- **Tests:** `cd backend && ./mvnw test` (uses Testcontainers → Docker must be running)
- **Health:** `curl localhost:8080/actuator/health` · **Login+me:**
  `curl -c j -X POST localhost:8080/api/v1/dev-login --data email=provider@northcare.example.org && curl -b j localhost:8080/api/v1/me`
- Only the **`local`** profile seeds demo data and exposes `dev-login`.
- **CI** (`.github/workflows/ci.yml`, on push + PR to `main`): a **backend** job (Temurin JDK 25 →
  `./mvnw -B verify`; the runner's Docker powers the Testcontainers tests) and a **frontend** job
  (Node 24 → `npm ci` → `typecheck` → `test` → `build`). Keep both green — don't merge red.
- **Frontend (needs the backend running):** `cd frontend && npm install` once, then `npm run dev`
  (Vite on **:5173**, proxies `/api` + `/actuator` → `:8080`, so cookies are same-origin — no CORS).
  Checks: `npm run typecheck`, `npm test` (Vitest), `npm run build`. Node runs from `openjdk@25`'s
  sibling `node@24` — use `export PATH="/opt/homebrew/opt/node@24/bin:$PATH"` in non-interactive shells.

## Current implementation (Phase 1 & 2 COMPLETE; Phase 3 IN PROGRESS — see docs/PROGRESS.md for status)
- **Backend packages** under `com.healthcloud`: `organization` (Organization, Facility, FacilityMembership),
  `identity` (AppUser, Role, OrganizationMembership, UserRole), `auth` (SecurityConfig, DevLoginController,
  CurrentUserController/Service, CsrfCookieFilter), `context` (UserContext + UserContextAccessor/Filter),
  `error` (ApiError, ErrorCode, GlobalExceptionHandler, CorrelationId), `patient` (Patient CRUD:
  `GET/POST /api/v1/patients`, `GET/PATCH /api/v1/patients/{id}`, tenant-scoped → secure 404 cross-tenant;
  reads are relationship-gated (providers see only assigned patients, via the shared `PatientAccessGuard`) and
  consent-field-masked — see the Authorization-layering + Field-masking conventions below),
  `request` (ServiceRequest + RequestStatusHistory + `RequestTransitions` state machine: `GET/POST
  /api/v1/requests`, `GET /api/v1/requests/{id}`, `PATCH /api/v1/requests/{id}/status`,
  `GET /api/v1/requests/{id}/history`; controlled §14.6 transitions, optimistic-locked, history per move;
  request comments — `POST/GET /api/v1/requests/{id}/comments`, participant-role gated, tenant-scoped;
  and assignment — `GET /api/v1/requests/{id}/assignment`, `GET .../assignable-users`, `PUT .../assignment`,
  coordinator/admin-gated, `RequestAssignmentService`. **Assignment is the only path to ASSIGNED** — it
  advances TRIAGED→ASSIGNED atomically; a bare status PATCH to ASSIGNED is rejected. **A request is
  object/relationship-gated by its patient** (§21 layer 6): every request read (list, `?patientId=`, get,
  history, comments, assignment) and the participant writes (status change, comment) route the request's
  `patientId` through the shared `PatientAccessGuard`, so a PROVIDER reaches only requests about patients they
  are actively assigned to — an unreachable one is a secure 404; the unfiltered list is scoped to their active
  patients; coordinators/admins stay broad. Finer PATIENT-own / CLAIMS_REVIEWER-business-need rules are the
  deferred permission-matrix work),
  `consent` (Phase 3 — ConsentDirective lifecycle §22: `GET/POST /api/v1/patients/{patientId}/consent-directives`,
  `POST .../consent-directives/{id}/revoke`; immutable/versioned per the supersede pattern — recording a change
  supersedes the current directive for a natural key and inserts version+1, revocation flips it to REVOKED;
  writes gated to CARE_COORDINATOR/ORG_ADMIN, reads open to same-tenant users. Plus the **consent+purpose
  decision engine** (§22.5): `ConsentPolicy` (a pure policy class), `ConsentPolicyService`, and
  `GET .../consent-directives/decision?purpose=&dataCategory=` — decides GRANT/DENY for the *calling actor*
  by most-specific-tier (PROVIDER>CARE_TEAM>ORGANIZATION), DENY-wins, deny-by-default, with the effective-date
  window re-checked at decision time. **CARE_TEAM scope is evaluated via `CareTeamService.isOnCareTeam`** (active
  provider- or coordinator-assignment to the patient); the pure `ConsentPolicy.decide` takes `actorOnCareTeam`
  as a parameter so it stays DB-free. `ConsentPolicyService.decideForActor(org, actor, patient, purpose,
  category)` is the low-level hook field masking calls (it computes care-team membership too). The consent
  **reads** (`list` + `/decision`) pass the shared `PatientAccessGuard` first, so an unassigned provider gets a
  secure 404 here too),
  `relationship` (Phase 3 — provider↔patient care relationship §14.3: `GET/POST
  /api/v1/patients/{patientId}/provider-assignments`, `POST .../{id}/revoke`; effective-dated, auditable,
  states PENDING/ACTIVE/EXPIRED/REVOKED, at most one current per (patient, provider); coordinator/admin-gated,
  the assignee must be a same-tenant PROVIDER. **Enforces the object/relationship gate** (§21 layer 6): a
  PROVIDER reads only actively-assigned patients — the gate logic lives in the shared `PatientAccessGuard`
  (patient package), which `PatientService`, the consent reads, and this module's `listCurrent` all route
  through; unassigned → secure 404, coordinators/admins broad. Also **`care_coordinator_assignment`** (§14.3,
  the sibling table — `GET/POST /api/v1/patients/{id}/coordinator-assignments`, `POST …/{id}/revoke`; same
  effective-dated/versioned/one-current-per-pair shape, assignee must be a same-tenant CARE_COORDINATOR): the
  two tables together are the **care team** a CARE_TEAM-scoped consent directive applies to, surfaced by
  **`CareTeamService.isOnCareTeam`** and consumed by the consent engine (§22.5). Each table also exposes a
  candidate-picker read for the assignment UI — `GET .../provider-assignments/candidates` and
  `.../coordinator-assignments/candidates` (coordinator/admin-gated, patient-tenant-scoped → secure 404):
  same-tenant users holding the required role, minus anyone already currently assigned, minimum-necessary
  (`AssignmentCandidateDto{userId, fullName}`). `DevDataSeeder` assigns each provider to 2 of 3 patients and
  the coordinator to 2 of 3),
  `devdata` (DevDataSeeder, local-only).
- **Tenant-owned entity pattern (Phase 2+):** hold `organizationId` as the tenant key; repositories expose
  only org-scoped finders (`findByIdAndOrganizationId`, `findByOrganizationId…`) — no bare `findById` in
  business code; services derive the org from `UserContextAccessor.requireOrganizationId()`. `patient` is
  the reference implementation; add composite `UNIQUE(id, organization_id)` so child rows can FK-with-org (§32.10).
- **Write pattern (Phase 2+):** stamp `organizationId` from context on create (never from the client);
  gate writes with `UserContextAccessor.requireAnyRole(...)` (role-based authz on the backend → 403);
  validate request records with Jakarta `@Valid` (→ 400 `VALIDATION_FAILED`); optimistic locking via a
  client-supplied `expectedVersion` compared to the row's `@Version` (mismatch → `ConflictException` 409);
  pre-check uniqueness for a clean 409 rather than surfacing a raw DB-constraint error. Idempotency-Key is
  reserved for the retriable commands §31 names (create request / submit claim / start adjudication).
- **Aggregate + history pattern (§31.6):** an important state change writes the domain row **and** a
  status/history row in **one `@Transactional`** (e.g. `service_request` + `request_status_history`,
  `null → DRAFT` on creation). Child tables carry `organization_id` and FK-with-org back to the parent's
  `UNIQUE(id, organization_id)` so tenancy is structurally enforced. History is append-only; stamp the
  actor and `CorrelationId.current()` on each row. State-machine transitions are validated on the backend.
- **Pure policy classes:** keep decision logic (state-machine transition tables, the consent evaluator) in a
  pure, unit-testable class with no Spring/DB deps — `RequestTransitions` (§14.6 moves) and `ConsentPolicy`
  (§22.5 consent+purpose) are the two exemplars; a thin service loads data and applies the policy.
- **State machines:** with the transition table in a pure policy class (`RequestTransitions`, above), the
  service checks, in order, **exists → legal move → role → reason → version**, then
  updates status + appends history in one tx. An illegal move is `INVALID_STATE_TRANSITION` (409), distinct
  from a stale-version `CONFLICT` (409). For state changes, client-supplied `expectedVersion` gives
  double-apply safety, so a separate Idempotency-Key isn't needed there (reserve it for create-type commands).
  A given status may be **owned by a dedicated command** rather than a bare status change: reaching it goes
  through an endpoint that does the extra work (e.g. `ASSIGNED` is reached only by `PUT .../assignment`, which
  records the assignee *and* advances the status in one tx), and a plain `PATCH /status` to that status is
  refused. Prefer this over letting a status be set with no accompanying record.
- **Authorization layering (§21.1; Phase 3+):** protected reads pass through independent backend layers, in
  order — tenant → function/role permission → **object/relationship** (e.g. a PROVIDER may read only patients
  they are actively assigned to; `provider_patient_assignment`) → **consent + purpose** (§22.5) → **field-level
  masking** (§23). Each is a separate check that can only *narrow* access; a broad role (coordinator/admin) may
  skip the relationship layer but still faces consent/field policy. An object/relationship denial is a **secure
  404** (§21.5), never a 403 that would confirm the row exists. Gate role-agnostically off the caller's actual
  roles from `UserContext`, never the client. **The relationship layer has ONE implementation —
  `PatientAccessGuard.requireAccessibleInTenant(patientId)`** (in `com.healthcloud.patient`) — and **every**
  patient-scoped read routes through it: the patient read itself *and* everything nested under a patient
  (consent directives, the consent decision, provider/coordinator assignments) *and* resources **about** a
  patient that live under their own top-level route — a **service request** is gated by its patient, so request
  reads/writes call `requireAccessibleInTenant(request.getPatientId())` too (list reads scope to the caller's
  active patients via `activePatientIdsFor`). A new endpoint that exposes a patient or patient-linked data MUST
  call the guard, so the gate can never be side-stepped by a nested or sibling route. The guard depends only on
  repositories (not on the services it protects), so any service can use it with no bean cycle.
- **Field-level masking (§23; Phase 3+):** the backend is the only trusted masker — build **field-safe DTOs**,
  never rely on the frontend to hide a value it received (§23.3). Map each resource's fields to a
  §23.1 `DataClassification` (+ a `ConsentDataCategory` when consent-controlled) in a small policy enum (e.g.
  `PatientFieldPolicy`); the read's **purpose is backend-fixed** per action (§21.4), not client-chosen. For each
  consent-controlled field, call `ConsentPolicy` (via `ConsentPolicyService.decideForActor`) for (purpose,
  category) as the calling actor; **deny-by-default** → withhold unless an applicable GRANT exists. A masked read
  returns the field as `null` and names it in a `maskedFields` list; **write responses stay unmasked** (the
  caller supplied the data). Omitted fields must not resurface in logs/exports/events (§23.4). `dateOfBirth` on
  the patient read is the reference implementation.
- **Versioned relationship / supersede pattern (§31.7; `request_assignment` and now `consent_directive`):**
  a mutable relationship is an append-only, `@Version`-locked child table where **at most one row is ACTIVE**
  (a partial unique index `WHERE status='ACTIVE'` enforces it and backstops races). "Changing" it *supersedes*
  the current ACTIVE row (`status→SUPERSEDED`, stamp `ended_at`) and inserts a new ACTIVE one — never mutates
  in place — so history is retained. Flush the supersede **before** the insert so the unique index is honored
  within the tx. Read via `findBy…AndStatus(ACTIVE)`. Validate cross-package participants (e.g. an assignee's
  role) through the owning module's repos, exposing only **minimum-necessary** fields, and return 400 (not a
  leaky 404/403) when the referenced same-tenant user is ineligible. Variations: a table can hold **many
  concurrent** current rows keyed by a natural key (e.g. `consent_directive` per patient×purpose×category×scope)
  — then the "one current" invariant is a partial unique index on that **natural key** (fold nullable key parts
  with `COALESCE(col, <sentinel>)` since Postgres treats NULLs as distinct), the "current" set can be
  `status IN ('ACTIVE','SCHEDULED')`, and a `<thing>_group_id` links the versions of one logical row. When the
  domain needs its own version number, keep it separate from the JPA `@Version` (e.g. `version` vs `lock_version`).
- **Caller/tenant context:** every request's identity is derived on the backend by `UserContextFilter`
  (resolves the session principal → user/org/roles) into a request-scoped `UserContext`. Services read it
  **only** via `UserContextAccessor` (`requireUser()`, `requireOrganizationId()`) — never trust a client-sent
  org/tenant id. Constrain all tenant-owned queries by `requireOrganizationId()`: load rows by
  `(organizationId, id)` so another tenant's row simply isn't found (a secure 404, not a 403). Isolation is
  proven by the `TenantIsolation*` tests — **extend them whenever you add a tenant-owned resource** (Phase 2+).
- **Errors:** one shape `{code, message, correlationId, details}` (`ApiError`). Throw `ApiException`
  subclasses (e.g. `NotFoundException`) or add an `ErrorCode`; `GlobalExceptionHandler` (@RestControllerAdvice)
  + the Security `RestAuthenticationEntryPoint`/`RestAccessDeniedHandler` render them uniformly (controllers
  AND filter-chain 401/403). `CorrelationIdFilter` sets a per-request id (MDC `%X{correlationId}` in logs,
  echoed as `X-Correlation-Id`). Never leak internal/sensitive text in `message`/`details`.
- **Entities:** UUID PKs (`@GeneratedValue(strategy = UUID)`), `@Version` on mutable rows, enums as
  `EnumType.STRING`, `OffsetDateTime` timestamps set via `@PrePersist`/`@PreUpdate`.
- **Schema is owned by Flyway** (`db/migration/V*.sql`); Hibernate is `ddl-auto: validate` (never generates DDL).
- **Auth:** session-based; `SESSION` cookie (HttpOnly), Spring Session JDBC (tables in `spring_session`);
  CSRF via readable `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header; unauthenticated protected requests → 401.
  Login is a **local dev stand-in** (email only, no password/MFA — ADR-018); Cognito+MFA come later.
- **Testing pattern:** real PostgreSQL via `TestcontainersConfiguration` (`@ServiceConnection`), imported with
  `@Import(TestcontainersConfiguration.class)`; repository/logic tests use `@SpringBootTest`; full HTTP/session
  flows use a real server (`webEnvironment = RANDOM_PORT`) + JDK `HttpClient` (not MockMvc). No mocks for data access.
  - **State-changing (POST/PATCH) tests must do the CSRF handshake:** log in, `GET /me` to obtain the readable
    `XSRF-TOKEN` cookie, then send it back as the `X-XSRF-TOKEN` header (dev-login itself is CSRF-exempt).
  - A `RANDOM_PORT` test may also `@Autowired` repositories to assert one-transaction side-effects (e.g. that a
    `request_status_history` row was written) — the test runs in the same context as the embedded server.
  - Every new tenant-owned resource gets a cross-tenant test proving another tenant's id → **secure 404**.
- **Frontend** (`frontend/`, Phase 1 slice 6+): Vite + React + TS, React Router 7, TanStack Query 5, MUI 9.
  Structure: `api/` (typed `fetch` client + `ApiClientError` + CSRF header injection), `auth/` (`useCurrentUser`
  querying `/api/v1/me`, `ProtectedRoute`, `LoginPage`), `layout/AppLayout`, `pages/`, `components/`. Auth =
  session cookie only — the SPA never holds tokens; login state = whether `/me` returns 200. Tests: Vitest +
  React Testing Library (mock the `api` object, keep the real `ApiClientError` for `instanceof`).

## Boot 4.1 notes (learned; avoid re-discovering)
- Testcontainers is **2.0.x** here → artifacts are `testcontainers-junit-jupiter` / `testcontainers-postgresql`.
- Spring Session needs the **starter** `spring-boot-starter-session-jdbc` (the raw library alone doesn't auto-configure).
- Some test types moved packages: `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure`.
- MockMvc doesn't run the Spring Session filter → test real session cookies with RANDOM_PORT + HttpClient.
- **Jackson 3** here: `ObjectMapper` is `tools.jackson.databind.ObjectMapper` (not `com.fasterxml.jackson.databind`);
  annotations stay under `com.fasterxml.jackson.annotation`. `writeValue(...)` throws unchecked `JacksonException`.

## Frontend notes (learned; avoid re-discovering)
- **MUI 9 `Stack`:** passing `alignItems` (etc.) as a direct prop can fail typecheck when children are a
  mixed/array set — put alignment in `sx={{ alignItems: 'center' }}` instead.
- `tsconfig` uses `verbatimModuleSyntax` → import types with `import type { ... }`.
- **Tests that render a component using `Link`/`useParams`/router hooks must wrap it in a `MemoryRouter`**
  (use `initialEntries` + a `<Routes><Route path="…">` when the component reads a URL param) — otherwise React
  Router throws "Cannot destructure property 'basename' of … null". Adding a router `Link` to an existing page
  breaks that page's older tests until they're wrapped too.
- Dev server binds IPv6 `localhost` and proxies `/api` + `/actuator` to `:8080`; the backend must be running
  or `/me` calls fail.
- **Forms (Phase 2 slice 3+):** React Hook Form 7 + Zod 4 via `@hookform/resolvers/zod` (resolvers **v5**
  supports Zod 4). Pattern: a Zod schema that **mirrors the backend Jakarta validation**, `useForm({ resolver:
  zodResolver(schema) })`, and surface server errors from `ApiClientError` (show `message` + `correlationId`).
  Feature code lives in a feature folder (e.g. `src/patients/`), mirroring `src/auth/`.
- **Role-aware UI = convenience, not security.** Gate write UI by `useCurrentUser().roles` to match the
  backend rule (e.g. patient create shown only to CARE_COORDINATOR/ORG_ADMIN), but the backend still enforces it.
  The requests UI mirrors the §14.6 transition table in `src/requests/transitions.ts` purely to choose which
  action buttons to show — the backend re-validates every move, so drift there is a UX bug, never a hole.
- **Feature pages so far:** `src/patients/` (list + create + **detail** `patients/:id`; the DOB column/field
  shows a muted "Restricted" when the backend masks it — the API sends `dateOfBirth: null` + a `maskedFields`
  list, §23; the list a provider sees is also relationship-gated on the backend, so a provider simply gets fewer
  rows — no client logic needed. The detail page has the **consent-directive UI** — record/revoke directives
  via `src/consent/useConsent.ts`, gated to coordinator/admin; recording invalidates the patient + list queries
  so a masked field flips live — and a **Care team card** — assign/revoke providers and coordinators via
  `src/relationship/useAssignments.ts` (candidate picker + optional effective dates), gated to coordinator/admin;
  a care-team change invalidates the assignment lists, the candidate lists, and the patient query, so a masked
  field driven by a PROVIDER/CARE_TEAM directive can flip live) and `src/requests/` (list + create + detail with
  status timeline, transition buttons, comments, assignment). Both follow the feature-folder + hooks + RHF/Zod pattern.
- **Consent/field masking in the UI (Phase 3+):** the backend already withholds masked values, so the SPA only
  *displays* the state — render a "Restricted"/placeholder for a `null` consent-controlled field (named in
  `maskedFields`); never assume a field is present. This is display-only, not a security control.
- **Native `<input type="date">` in tests/automation:** set its value directly (ISO `yyyy-mm-dd`), not by typing.

## Repo layout
`backend/` `frontend/` `worker/` `infrastructure/{terraform,environments}` `api/openapi/`
`docs/{architecture,er-diagram,events,threat-model,adr,runbooks,evidence,learning,source-of-truth}/`
`synthetic-data/` `scripts/` `.github/workflows/` · plus `CLAUDE.md`, `docs/PLAN.md`,
`docs/PROGRESS.md`, `docker-compose.yml`, `README.md`.

## Custom tooling (see docs/PLAN.md Part C for the full plan)
- **Exists today:** `.claude/launch.json` (the `frontend` dev-server config for the browser preview);
  slash command **`/learning-module`** (`.claude/commands/learning-module.md`) — appends a per-session
  learning + interview-prep note to `docs/learning/learning-module.md` (never overwrites; based on what
  we actually built). This is the `/capture-module` idea from PLAN.md Part C, realized.
- **Planned, NOT yet created** (don't assume these exist): commands `/status` (session start),
  `/wrap` (session end), `/adr`; Phase-3 subagents (HealthCloud code-reviewer +
  security-reviewer); Phase-1+ hooks (format/compile after edits; later a synthetic-data guard).
- Until they exist, use built-ins: `/code-review`, `/security-review`, and read the 3 files manually.
