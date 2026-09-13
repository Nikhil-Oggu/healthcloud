# PROGRESS.md — HealthCloud running log

> The **diary** of the project. Updated at the end of every session (by the `/wrap` command once it
> exists, or manually). Read this + `CLAUDE.md` + `docs/PLAN.md` at the start of every session.

## Current position
- **Phase:** 0 ✅ · Environment ✅ · Phase 1 COMPLETE ✅ · **Phase 2 COMPLETE ✅ (slices 1–8: patient CRUD+UI; service request create+read+state machine + Requests UI; comments; assignment)**
- **Repo:** https://github.com/Nikhil-Oggu/healthcloud (private, branch `main`)
- **Next up:** **Phase 3** — the flagship differentiator: consent lifecycle/versioning, the hybrid
  RBAC+attribute **policy evaluator** (tenant → object → **relationship** (now backed by `request_assignment`
  + the future `provider_patient_assignment`) → consent → purpose → field-level masking), secure S3 document
  upload/download with malware-scan/quarantine, and audit integration. **Run `/security-review` in Phase 3.**
  Plan the first slice before building. (Deferred Phase-2 niceties, if ever wanted: SLA/due-dates,
  provider/coordinator-to-patient assignment tables, request edit/priority UI.)
- **Run the frontend:** with Postgres + backend up, `cd frontend && npm run dev` → open
  http://localhost:5173 → sign in as a seeded demo user.
- **Run the demo:** `docker compose up -d postgres` then
  `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
  Log in: `curl -c j.txt -X POST localhost:8080/api/v1/dev-login --data email=admin@greenvalley.example.org`
  then `curl -b j.txt localhost:8080/api/v1/me`. Reset DB with `./scripts/db-reset.sh`.

## Log (newest first)

### 2026-09-13 — Phase 2, slice 8 ✅ (request assignment — Option A) — **Phase 2 COMPLETE**
- **`V8__request_assignment.sql`:** `request_assignment` (tenant key, `service_request_id`,
  `assignee_user_id`, `assigned_by_user_id`, `assignee_role`, `status` ACTIVE/SUPERSEDED, `assigned_at`,
  `ended_at`, `@Version`). **Composite FK** `(service_request_id, organization_id) → service_request` (§32.10);
  **partial unique index** `WHERE status='ACTIVE'` → at most one active assignment per request (also a
  concurrency backstop for racing assigns); active-assignee index for future "assigned to me".
- **`com.healthcloud.request`:** `RequestAssignment` (versioned; `supersede()`), `RequestAssignmentStatus`,
  repository, `RequestAssignmentDto`, `AssignableUserDto`, `AssignRequest` (`assigneeUserId`,
  `expectedVersion`), **`RequestAssignmentService`** (reads the `identity` repos to resolve/validate
  assignable users). Endpoints on the requests controller: `GET /{id}/assignment` (current active, or null),
  `GET /{id}/assignable-users` (coordinator/admin; same-tenant PROVIDER/CLAIMS_REVIEWER, minimum-necessary),
  `PUT /{id}/assignment` (assign/reassign).
- **Option A (chosen):** assignment is the **sole path to `ASSIGNED`**. Assigning a TRIAGED request advances
  it TRIAGED→ASSIGNED and appends a status-history row in **one transaction** (§31.6); reassigning an ASSIGNED
  request supersedes the prior row and swaps the assignee (status unchanged). A bare `PATCH /status` to
  ASSIGNED is now rejected `INVALID_STATE_TRANSITION` (409). Assigner = CARE_COORDINATOR/ORG_ADMIN (else 403);
  assignee must be a same-tenant provider/reviewer (else 400); cross-tenant request → secure 404;
  optimistic-locked on the request `expectedVersion` (stale → 409). Ineligible-assignee returns 400
  (`VALIDATION_FAILED`) without leaking whether the user exists.
- **Frontend:** client `getAssignment`/`listAssignableUsers`/`assign` + `useAssignment`/`useAssignableUsers`
  (enabled only for assigners)/`useAssign` hooks; an **Assignment card** on `RequestDetailPage` (current
  assignee + an assign/reassign selector for coordinators/admins, shown only when the status is TRIAGED/
  ASSIGNED). `transitions.ts` no longer offers ASSIGNED as a status button (Option A).
- **Verified — automated:** `./mvnw -B verify` → **61 tests** (+8 `RequestAssignmentApiIntegrationTest`:
  assign→ASSIGNED + one active row + history; reassign supersedes; non-assigner 403; ineligible assignee 400;
  can't assign before triage 409; bare status→ASSIGNED 409; stale version 409; cross-tenant 404; the state
  machine test now reaches ASSIGNED via the assign endpoint). Frontend: typecheck clean, `npm test` →
  **19 pass** (+3: shows assignee, coordinator assigns a triaged request, non-assigner sees no selector), build OK.
- **Verified — live in browser + curl:** assign a triaged request → ASSIGNED, timeline gains
  "TRIAGED → ASSIGNED · Assigned to Dana Provider"; **reassigned Dana → Riley through the UI**; the status
  actions correctly omit "Assign"; a manual PATCH to ASSIGNED returns 409. Flyway applied V8.
- **Deferred:** `provider_patient_assignment` / `care_coordinator_assignment` (patient-level relationships)
  and SLA/due-dates → Phase 3+/later. The assignee-relationship this slice records is what Phase 3's policy
  evaluator will consume.

### 2026-09-13 — Phase 2, slice 7 ✅ (request comments — the collaboration thread)
- **`V7__request_comment.sql`:** `request_comment` (tenant key `organization_id`, `service_request_id`,
  `author_user_id`, `body` ≤2000, `created_at`). **Composite FK** `(service_request_id, organization_id) →
  service_request(id, organization_id)` so a comment cannot attach to another tenant's request (§32.10);
  index `(organization_id, service_request_id)`.
- **`com.healthcloud.request`:** `RequestComment` (append-only, author + org stamped from context),
  `RequestCommentRepository` (tenant+request scoped, oldest-first), `RequestCommentDto`, `AddCommentRequest`
  (`@NotBlank @Size(max=2000)`). Two service methods (`addComment`/`getComments`) on `ServiceRequestService`.
- **Endpoints:** `POST /api/v1/requests/{id}/comments` (201; participant roles PATIENT/PROVIDER/
  CARE_COORDINATOR/ORG_ADMIN — read-only roles → 403; request must be in the caller's tenant else secure 404),
  `GET /api/v1/requests/{id}/comments` (same-tenant readers, oldest-first).
- **Frontend:** `api/client.ts` listComments/addComment; `useComments`/`useAddComment` hooks (invalidate the
  thread on add); a **Comments** card on `RequestDetailPage` (list + RHF/Zod add box ≤2000 chars, gated to
  participant roles — UI convenience; backend enforces). Surfaces `ApiClientError` message + Reference ID.
- **Verified — automated:** `./mvnw -B verify` → **53 tests pass** (+4 `RequestCommentApiIntegrationTest`:
  add+list oldest-first with two different participants; CLAIMS_REVIEWER → 403; cross-tenant add & read → 404;
  blank body → 400). Frontend: typecheck clean, `npm test` → **16 pass** (+3: renders thread, participant
  posts, read-only sees no box), build OK.
- **Verified — live in browser:** signed in as provider, opened a request, saw the comment thread (oldest
  first), **posted a comment through the UI** (CSRF handshake via the Vite proxy) → it appeared and the form
  reset. Confirmed end-to-end via curl too (201 add / list / blank→400) and that Flyway applied V7.
- **Deferred:** assignment (`request_assignment`) → slice 8; documents/consent/field-policy (Phase 3).

### 2026-09-13 — Phase 2, slice 6 ✅ (Requests UI — drive the whole workflow in the browser)
- **`src/requests/`:** `useRequests.ts` (list/one/create/change-status/history hooks with invalidation),
  `transitions.ts` (a client mirror of the §14.6 table + role rules — **UX only**; backend is the enforcer),
  `statusColor.ts`, `RequestsPage.tsx` (list + create form via RHF+Zod, native selects for patient/type/
  priority; create gated to create-roles), `RequestDetailPage.tsx` (fields + status + timeline from the
  history endpoint + transition buttons for allowed *and* role-authorized moves, sending `expectedVersion`
  and prompting for a reason on cancel/reject; surfaces `ApiClientError` incl. 409/403 + Reference ID).
- **`api/client.ts`:** listRequests/getRequest/createRequest/changeRequestStatus/getRequestHistory.
  **`App.tsx`:** `/requests` + `/requests/:id`. **`AppLayout`:** working "Requests" nav.
- **Verified — automated:** typecheck clean; `npm test` → **13 pass** (+6: RequestsPage list/create/role-gate,
  RequestDetailPage status+timeline / submit sends loaded version / patient sees no coordinator-only actions);
  build OK.
- **Verified — live in browser:** as coordinator, created a request → opened it → drove
  **DRAFT→SUBMITTED→TRIAGED→ASSIGNED→UNDER_REVIEW→APPROVED→CLOSED**, timeline grew to 7 entries, buttons
  changed per state, DRAFT offered no Cancel to the coordinator (correct §14.6), Reject revealed a required-
  reason field (Confirm disabled until filled), and CLOSED showed no further actions (terminal).
- **Deferred:** comments + assignment (slice 7); documents/consent/field-policy (Phase 3).

### 2026-09-13 — Phase 2, slice 5 ✅ (service-request state machine — the heart)
- **`PATCH /api/v1/requests/{id}/status`** `{ targetStatus, expectedVersion, reason? }` — controlled
  transitions per §14.6. In one transaction (§31.6): validate → update status (+`@Version` bump) → append a
  `request_status_history` row (`from→to`, actor, reason, correlationId). **`GET /api/v1/requests/{id}/history`**
  exposes the timeline.
- **`RequestTransitions`** encodes the exact §14.6 table (forward flow + cancellation) and the role rules:
  cancellation authority verbatim (patient: DRAFT/SUBMITTED/NEEDS_INFORMATION; coordinator/admin:
  SUBMITTED/TRIAGED/ASSIGNED/NEEDS_INFORMATION with a reason; provider: none); forward-transition roles are a
  documented synthetic MVP choice (refined once assignment exists). Terminal states (CANCELLED/CLOSED) have no
  outgoing moves.
- **New `ErrorCode.INVALID_STATE_TRANSITION` (409)** — distinct from a stale-version `CONFLICT`, so the UI can
  tell "not allowed now" from "someone else changed it". Reason mandatory for CANCELLED/REJECTED (else 400).
- **Verified:** `./mvnw -B verify` → **49 tests pass** (+7 `ServiceRequestStateMachineApiIntegrationTest`:
  full DRAFT→…→CLOSED with a 7-row history then terminal-409; illegal move → 409 INVALID_STATE_TRANSITION;
  stale version → 409 CONFLICT; provider approve → 403; patient cancels DRAFT → 200 / provider cancel → 403;
  reject needs a reason → 400/200; cross-tenant transition → 404).
- **Idempotency-Key: deferred (decided).** Optimistic `expectedVersion` already makes transitions safe against
  double-apply (a repeated move hits a stale version → 409); the canonical Idempotency-Key need is claim
  submission (Phase 4), where it'll be introduced.
- **Deferred:** assignment + comments/timeline (slice 6), Requests UI, audit events + assignee-relationship
  check on review (Phase 3 / assignment slice).

### 2026-09-13 — Phase 2, slice 4 ✅ (service request — create DRAFT + read)
- **`V6__service_request.sql`:** `service_request` (tenant key `organization_id`, `patient_id`, `type`,
  `status` default DRAFT, `priority` default NORMAL, `title`, `description`, `created_by`, `@Version`;
  CHECK constraints for the type/status/priority value sets) + `request_status_history` (append-only:
  from/to status, actor, reason, correlation_id, created_at). **Composite FK** `(patient_id,
  organization_id) → patient(id, organization_id)` so a request cannot reference another tenant's patient
  (§32.10); `UNIQUE(id, organization_id)` on the request for future child FKs; §32.11 indexes.
- **`com.healthcloud.request`:** enums (`ServiceRequestType` §14.4, `ServiceRequestStatus` §14.6,
  `ServiceRequestPriority`), `ServiceRequest` + `RequestStatusHistory` entities, tenant-safe repositories,
  `ServiceRequestService`, thin `ServiceRequestController`, DTO + validated create request.
- **Endpoints:** `POST /api/v1/requests` (201; creates a **DRAFT** for a patient in the caller's tenant),
  `GET /api/v1/requests` (optional `?patientId=`), `GET /api/v1/requests/{id}` (tenant-scoped → 404).
- **§31.6 one-transaction pattern:** create writes the request **and** its initial history row
  (`null → DRAFT`, actor + correlationId) atomically. Create gated to PATIENT/PROVIDER/CARE_COORDINATOR/
  ORG_ADMIN; patient must be in the caller's tenant (else secure 404).
- **Verified:** `./mvnw -B verify` → **42 tests pass** (+4 `ServiceRequestApiIntegrationTest`: create → DRAFT
  + one `null→DRAFT` history row; another tenant's patient → 404; reviewer → 403; blank title → 400).
- **Deferred to slice 5:** the controlled transition state machine (submit…close + cancellation, §14.6),
  optimistic-locking on transitions, Idempotency-Key. **Later:** assignment + comments/timeline, Requests UI,
  audit events + consent/field policy (Phase 3).

### 2026-09-13 — Phase 2, slice 3 ✅ (Patients UI — first visible business feature)
- **New deps (first use of the form stack):** `react-hook-form@7.88`, `zod@4.6`, `@hookform/resolvers@5.9`
  (resolvers v5 supports Zod 4). Lockfile committed for CI `npm ci`.
- **`src/patients/`:** `usePatients.ts` (TanStack Query `usePatients` list + `useCreatePatient` mutation that
  invalidates the list), `PatientsPage.tsx` — MUI table (name · MRN · DOB · status) + an **Add-patient form**
  (RHF + Zod, schema mirrors the backend Jakarta rules). The form shows **only for `CARE_COORDINATOR`/
  `ORG_ADMIN`** (role-aware UI mirroring the backend gate); other roles get the read-only list. Backend
  errors surfaced via `ApiClientError` (message + Reference ID / correlationId).
- **`api/client.ts`:** `listPatients()` + `createPatient()` (CSRF header auto-injected on POST).
  **`App.tsx`:** `/patients` route. **`AppLayout`:** working "Patients" nav for provider/coordinator/admin.
- **Verified — automated:** `npm run typecheck` clean; `npm test` → **7 pass** (+4 `PatientsPage.test.tsx`:
  list renders, form hidden for non-write roles, coordinator create calls the API, empty form → Zod
  "Required" and no submit); `npm run build` OK.
- **Verified — live in browser** (fresh `db-reset` seed): coordinator sees list + Add form, **created a
  patient** (appeared in the list, form reset), empty submit blocked by validation, **duplicate MRN → the
  409 message + Reference ID shown**; provider sees the **list but no Add form**. CSRF POST works through the
  Vite proxy end-to-end.
- **Deferred:** edit/deactivate UI (uses the existing PATCH + `expectedVersion`), pagination/search (Phase 9).

### 2026-09-13 — Phase 2, slice 2 ✅ (patient write path: create + update)
- **Endpoints:** `POST /api/v1/patients` (201 + Location) and `PATCH /api/v1/patients/{id}` (200), thin
  controller → `PatientService`. Requests: `PatientCreateRequest`/`PatientUpdateRequest` (Jakarta
  validation → 400 `VALIDATION_FAILED` with field details).
- **First backend role authorization:** writes require `CARE_COORDINATOR` or `ORG_ADMIN` via new
  `UserContextAccessor.requireAnyRole(...)` (reads stay open to any same-tenant user); disallowed → 403
  `ACCESS_DENIED`. Roles come from the backend-derived `UserContext`, never the client.
- **Tenant stamping on write:** `organizationId` is set from context on create — a client can't create in
  another tenant; cross-tenant `PATCH` → secure 404.
- **Conflicts (409):** duplicate MRN within a tenant (pre-checked, `ConflictException` + new
  `existsByOrganizationIdAndMedicalRecordNumber`); **optimistic locking** — client sends the `version` it
  last saw as `expectedVersion`, mismatch → 409 (nothing overwritten). `PatientDto` now exposes `version`;
  successful update flushes so the response carries the incremented version.
- **Verified:** `./mvnw -B verify` → **35 tests pass** (+6 `PatientWriteApiIntegrationTest`: create-as-coordinator
  → 201 & listed; provider → 403; duplicate MRN → 409; invalid → 400; stale version → 409; cross-tenant
  update → 404). Writes exercise the real CSRF cookie→header handshake for the first time on a business write.
- **Deferred on purpose:** Idempotency-Key (reserved for the retriable commands §31 names — create request /
  submit claim / start adjudication; a patient create doesn't need it); Patients UI (slice 3); audit/outbox
  on writes (Ph 3/7/8); consent/purpose + field masking (Phase 3).
- **Hardening (same day):** the service pre-checks (expected version, unique MRN) handle the common cases,
  but under a *true* write race two callers can both pass the pre-check and collide at the DB. Mapped the
  DB backstops — `ObjectOptimisticLockingFailureException` and `DataIntegrityViolationException` — to 409
  `CONFLICT` in `GlobalExceptionHandler` (generic message, no SQL/constraint leak), so that edge returns
  the right shape instead of a 500. Tests: `GlobalExceptionHandlerTest` (mapping + no-leak) and a repo test
  proving the `UNIQUE(organization_id, mrn)` constraint actually throws. `./mvnw -B verify` → **38 tests pass**.

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
