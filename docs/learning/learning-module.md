# HealthCloud — Learning Modules

> A running set of learning + interview-prep notes, one section per work session.
> Newest sections are appended at the bottom. See `docs/PROGRESS.md` for the project diary
> and `CLAUDE.md` for the stable rules.

---

## Phase 1 — Application Foundation & Multi-Tenant Identity (slices 1–7) — 2026-09-13

### What we built

Phase 1 turned an empty repo into a **runnable, multi-tenant web application** with real identity,
sessions, a consistent error contract, a first visible UI, and CI — all on **synthetic data only**.

In plain terms, by the end of Phase 1 you can: start PostgreSQL and the backend, log in as a seeded
demo user, and hit `/api/v1/me` to get *who you are and which organization (tenant) you belong to* —
derived entirely on the server. A React app shows that identity in a top bar and protects its pages.
Every request carries a correlation id, every error looks the same, and a **NorthCare user can never
see Green Valley's data**. GitHub Actions proves all of this builds and passes on every push.

It was built as seven small, individually-verified slices:

1. **Slice 1** — bootable Spring Boot 4.1 + PostgreSQL 17 (Docker) + Flyway migration `V1`.
2. **Slice 2** — identity & organization data model (`organization`, `app_user`, `role`, `organization_membership`, `user_role`).
3. **Slice 3** — facilities + a deterministic demo seed (NorthCare Health & Green Valley Clinic).
4. **Slice 4** — authentication foundation: session login, Spring Session JDBC, CSRF, `/me`.
5. **Slice 5** — backend-derived request/tenant context + a global `{code, message, correlationId, details}` error model.
6. **Slice 6** — the React/TypeScript frontend shell (login, protected routes, identity bar).
7. **Slice 7** — first GitHub Actions CI pipeline + the cross-tenant isolation acceptance proof.

### How it works

**Stack (frozen baseline).** Backend: Java 25, Spring Boot 4.1.0, Maven wrapper, Spring Security,
Spring Data JPA, Spring Session JDBC. DB: PostgreSQL 17 with Flyway migrations. Frontend: Node 24,
Vite 8, React 19.2, TypeScript 6, React Router 7, TanStack Query 5, Material UI 9, Vitest.

**Schema is owned by Flyway, not Hibernate.** Migrations live in
`backend/src/main/resources/db/migration/` (`V1__baseline.sql` → `V4__spring_session.sql`), and
Hibernate runs in `ddl-auto: validate` — it *checks* the schema matches the entities but never
generates DDL. The tenant backbone is in `V2`:

```sql
-- V2__identity_and_organization.sql (excerpt)
CREATE TABLE organization ( id UUID PRIMARY KEY DEFAULT gen_random_uuid(), name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', ... version BIGINT NOT NULL DEFAULT 0, ... );
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));   -- case-insensitive unique email
```

Conventions across every table/entity: **UUID primary keys** (`gen_random_uuid()` in SQL,
`@GeneratedValue(strategy = UUID)` in JPA), a `version BIGINT` column mapped to `@Version` for
**optimistic locking**, enums stored as strings (`EnumType.STRING`), and `TIMESTAMPTZ` timestamps.
A **partial unique index** enforces the "one ACTIVE organization membership per user" rule.

**Tenancy model.** A tenant *is* an organization. `app_user` is a **global** identity; a user's
tenancy comes from `organization_membership` (which carries the `organization_id` tenant key). This
is the shared-DB-with-tenant-key approach (ADR-002).

**Authentication (`com.healthcloud.auth`).** Session-based, not token-based. `SecurityConfig` sets:
unauthenticated protected requests → 401 (no login-page redirect), CSRF via a **readable** cookie,
`/dev-login` and `/actuator/health` public, everything else authenticated.

```java
// SecurityConfig (excerpt) — the filter order that makes tenant context trustworthy
.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
.addFilterAfter(new UserContextFilter(currentUserService), AuthorizationFilter.class)
.exceptionHandling(ex -> ex
    .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper))   // 401 as JSON
    .accessDeniedHandler(new RestAccessDeniedHandler(objectMapper)));           // 403 as JSON
```

Login itself is a **local-only dev stand-in** (`DevLoginController`, `@Profile("local")`): it looks
up a seeded ACTIVE user *by email only — no password, no MFA* (ADR-018), builds an `Authentication`,
and saves it into the HTTP session (persisted by Spring Session JDBC). The real Amazon Cognito + BFF
+ MFA flow replaces this later. CSRF uses the standard BFF pattern: `CsrfCookieFilter` forces the
token to be written to the readable `XSRF-TOKEN` cookie; the SPA echoes it back as the
`X-XSRF-TOKEN` header on state-changing requests.

**Backend-derived caller/tenant context (`com.healthcloud.context`).** After authorization,
`UserContextFilter` resolves the session principal to a `UserContext` (userId, email, org id/name,
roles) and clears it in a `finally` so nothing leaks between requests:

```java
// UserContextFilter (excerpt)
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (isAuthenticated(auth)) {
    currentUserService.resolveByEmail(auth.getName())
        .ifPresent(dto -> UserContextHolder.set(toContext(dto)));
}
```

Services read identity **only** through `UserContextAccessor`: `requireUser()` (→ 401 if none) and
`requireOrganizationId()` (→ 403 `TenantContextRequiredException` if the caller has no org). This is
the single trusted source of "who is calling and which tenant" — a client-sent org id is never
trusted. `/me` (`CurrentUserController`) is just a thin read of that context.

**Error model (`com.healthcloud.error`).** One JSON shape everywhere: `{code, message,
correlationId, details}` (`ApiError`), driven by an `ErrorCode` enum (each maps to an HTTP status +
default message). `GlobalExceptionHandler` (`@RestControllerAdvice`) handles controller-layer
exceptions; the security handlers cover 401/403 raised *inside* the filter chain — so controllers and
filters speak the same contract. The catch-all logs the real cause server-side and returns a generic
500 (never leaks internals). `CorrelationIdFilter` runs at **highest precedence** (before Security)
so even 401/403 carry an id:

```java
String correlationId = request.getHeader(CorrelationId.HEADER);
if (!isSafe(correlationId)) correlationId = UUID.randomUUID().toString(); // ≤64 chars, [A-Za-z0-9-]
MDC.put(CorrelationId.MDC_KEY, correlationId);        // → %X{correlationId} in every log line
response.setHeader(CorrelationId.HEADER, correlationId);
```

**Frontend (`frontend/`).** Same-origin dev setup: Vite (`:5173`) proxies `/api` + `/actuator` →
`:8080`, so the `SESSION` and `XSRF-TOKEN` cookies are first-party — no CORS, no tokens in JS. The
typed `api/client.ts` wraps `fetch`, throws `ApiClientError` (carrying `code`/`correlationId`) on
non-2xx, and auto-injects the CSRF header on non-GET requests:

```ts
if (method !== 'GET' && method !== 'HEAD') {
  const token = readCookie('XSRF-TOKEN')
  if (token) headers['X-XSRF-TOKEN'] = token
}
// credentials: 'same-origin' — send the session cookie
```

`useCurrentUser()` (TanStack Query) calls `/me`; **login state = whether `/me` returns 200**.
`ProtectedRoute` shows a spinner, redirects to `/login` on 401, or renders. `AppLayout` shows
email · organization · role chips · logout with a role-aware (placeholder) nav. `LoginPage` is a
dropdown of the 10 seeded demo users.

**CI (`.github/workflows/ci.yml`).** Two independent jobs on push/PR to `main`: **backend** (Temurin
JDK 25 → `./mvnw -B verify`; the runner's Docker powers Testcontainers) and **frontend** (Node 24 →
`npm ci` → `typecheck` → `test` → `build`), with `concurrency` cancelling superseded runs.

**Testing pattern.** Real PostgreSQL via Testcontainers (`TestcontainersConfiguration` +
`@ServiceConnection`), never mocks for data access. Repository/logic tests use `@SpringBootTest`;
full HTTP/session flows use a **real server** (`webEnvironment = RANDOM_PORT`) + the JDK `HttpClient`
(not MockMvc — see failures below). End of Phase 1: **25 backend tests** + **3 frontend tests**, all green.

### Key points to remember

- **Backend is the only security boundary.** The frontend hides/disables UI for convenience; the
  server authorizes everything. Tenant id is derived from the session, never read from the client.
- **The tenant-safe query rule (standing convention):** load tenant-owned rows by
  `(organizationId, id)` so another tenant's row is simply *not found* — a **secure 404, not a 403**
  (a 403 would leak that the row exists). Constrain every tenant-owned query by
  `requireOrganizationId()`. Extend the `TenantIsolation*` tests whenever a new tenant-owned resource
  is added.
- **Filter order is load-bearing.** `CorrelationIdFilter` (highest precedence) → Spring Security →
  `UserContextFilter` (after `AuthorizationFilter`). Context resolves *after* auth so it can trust
  the principal; correlation id sits *before* Security so failures still get an id.
- **Schema ownership:** Flyway owns DDL; Hibernate only validates (`ddl-auto: validate`). Spring
  Session tables are created by `V4`, not by Spring Session (`initialize-schema: never`). Add a new
  migration — never hand-edit the DB or let Hibernate generate it.
- **`local` profile is special:** it (and only it) seeds demo data and exposes `dev-login`. The
  seeder is **idempotent** (skips if NorthCare already exists).
- **No sensitive data in logs/errors.** The 500 handler logs the cause server-side but returns a
  generic message + correlation id to the client. Correlation ids are validated to block
  log-forging / response-header injection.
- **Observability today:** per-request correlation id in the log MDC (`%5p [cid=%X{correlationId:-}]`)
  and echoed as the `X-Correlation-Id` response header — enough to tie a client-visible response to
  its server logs. (Full tracing/metrics dashboards are Phase 11, not built yet.)
- **Non-negotiables honored:** synthetic data only; "HIPAA-aligned, NOT certified"; no unmeasured
  claims (we only wrote down test counts and CI results we actually observed).
- **Env for non-interactive shells:** must export `JAVA_HOME`/`PATH` for `openjdk@25` (backend) and
  `node@24` (frontend) — interactive terminals get these from `~/.zshrc`.

### Failures and how we fixed them

- **Jackson 3 package move (Slice 5 — compile failure).** *Symptom:* `com.fasterxml.jackson.databind.ObjectMapper`
  would not resolve. *Root cause:* Spring Boot 4.1 ships **Jackson 3**, which relocated the databind
  classes to the `tools.jackson.*` namespace (annotations stay under `com.fasterxml.jackson.annotation`).
  *Fix:* import `tools.jackson.databind.ObjectMapper` in `SecurityConfig` and the error/security
  handlers. Recorded in CLAUDE.md.
- **MUI 9 `Stack` `alignItems` typecheck error (Slice 6).** *Symptom:* `TS2769 No overload matches`
  on `<Stack alignItems="center">` with mixed/array children. *Root cause:* MUI 9's stricter prop
  typing. *Fix:* move alignment into `sx={{ alignItems: 'center' }}` instead of a direct prop.
- **Testcontainers artifact rename (Slice 2).** *Symptom:* dependencies not found. *Root cause:*
  Testcontainers is **2.0.x** in this Boot line; artifacts are `testcontainers-junit-jupiter` /
  `testcontainers-postgresql`. *Fix:* use the prefixed artifact names.
- **Spring Session didn't auto-configure (Slice 4).** *Root cause:* the raw Spring Session library
  alone isn't enough. *Fix:* depend on the **starter** `spring-boot-starter-session-jdbc`.
- **MockMvc can't test the session-cookie flow (Slice 4).** *Symptom:* session/CSRF behavior didn't
  reproduce under MockMvc. *Root cause:* MockMvc doesn't run the Spring Session servlet filter.
  *Fix:* test real session flows with `webEnvironment = RANDOM_PORT` + the JDK `HttpClient`. Also,
  `@AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure` in Boot 4.1.
- **Stale app on :8080 (Slice 3).** *Symptom:* a possibly-stale health response / port in use.
  *Fix:* ensure the port is free before launching and wait for the seeder's log line, not a health
  ping.
- **Java 25 on GitHub Actions (Slice 7 — flagged risk, resolved).** We were unsure `actions/setup-java`
  had Temurin 25. *Outcome:* it did — the backend job passed in ~1m15s. Planned fallback (another
  JDK distribution with 25) wasn't needed.
- **CI deprecation warnings (Slice 7 — open, non-fatal).** The run warns that `actions/*@v4` target
  the deprecated Node 20, and `setup-java@v4` is superseded by `@v5`. CI passes; bumping to `@v5` is
  a pending trivial cleanup, not yet done.

### Interview Q&A

#### 1. Beginner

**Q: What does "multi-tenant" mean in HealthCloud, and what is the tenant?**
A: Multiple isolated customer organizations share one application and one database. The **tenant is
an organization** (e.g. NorthCare Health). Rows owned by a tenant carry an `organization_id`, and one
tenant must never see another's data.

**Q: How does a user log in during development?**
A: Through a local-only `/dev-login` endpoint that accepts just an email, finds the seeded ACTIVE
user, and starts an authenticated session. There's deliberately no password or MFA yet — it's a
stand-in for the real Cognito login that comes later (recorded in ADR-018).

**Q: How does the app know who you are on later requests?**
A: A session cookie (`SESSION`, HttpOnly), backed by Spring Session stored in PostgreSQL. The browser
sends the cookie automatically; the server looks up the session to recover your identity.

**Q: What does `GET /api/v1/me` return and where does that data come from?**
A: Your identity — user id, email, name, organization, and roles. It comes from the backend-derived
`UserContext`, resolved from your session, *not* from anything the browser sent.

**Q: Why is the database schema managed by Flyway instead of letting Hibernate create tables?**
A: Flyway gives explicit, versioned, reviewable migrations (`V1…V4`). Hibernate runs as
`ddl-auto: validate`, so it checks the entities match the schema but never silently changes the DB.
This is safe and reproducible across machines and CI.

**Q: What is the standard error shape?**
A: `{code, message, correlationId, details}`. Every error — from controllers and from the security
filters — uses it, so clients can handle errors uniformly and quote a correlation id for support.

**Q: What does the frontend use to decide if you're logged in?**
A: Whether `/me` returns 200. There are no tokens in JavaScript; auth is the session cookie only.

#### 2. Intermediate

**Q: Why session cookies + CSRF instead of a JWT in localStorage?**
A: This is a BFF (backend-for-frontend) design. An HttpOnly session cookie can't be read by JS, which
neutralizes token theft via XSS. Because cookies are sent automatically we need CSRF protection: a
readable `XSRF-TOKEN` cookie the SPA echoes back as `X-XSRF-TOKEN`, which the server verifies. Vite
proxies `/api` to the backend so everything is same-origin — no CORS and no cross-site cookie issues.

**Q: Why does `UserContextFilter` run *after* the authorization filter, but `CorrelationIdFilter`
run *before* Spring Security?**
A: Context must resolve *after* authentication so it can trust the principal it reads. The
correlation id must be set *before* Security so that even 401/403 responses produced inside the
security chain still carry an id and appear in logs.

**Q: How is tenant isolation actually enforced, and how do you prove it?**
A: Identity/tenant is derived server-side into `UserContext`; queries are constrained by
`requireOrganizationId()`, loading rows by `(organizationId, id)`. We prove it two ways:
`TenantIsolationIntegrationTest` (HTTP) shows each session sees only its own org via `/me` and that
spoofed `?organizationId=`/`X-Organization-Id` hints are ignored; `TenantIsolationRepositoryTest`
shows the `(org, id)` lookup returns nothing for another tenant's row.

**Q: Why test full auth flows with a real server + `HttpClient` instead of MockMvc?**
A: MockMvc doesn't run the Spring Session servlet filter, so the `SESSION`/`XSRF-TOKEN` cookie
handshake and 401-after-logout behavior don't reproduce. `RANDOM_PORT` + JDK `HttpClient` exercises
the real filter chain and cookie lifecycle.

**Q: Why the "secure 404, not 403" rule for cross-tenant access?**
A: A 403 confirms the resource exists (just that you can't see it), which leaks information. Returning
404 for another tenant's row hides existence entirely, which is the correct behavior for
existence-sensitive, cross-tenant denials.

**Q: What does the correlation id give you, and how is it made safe?**
A: It ties a client-visible response (via the `X-Correlation-Id` header) to its server-side log lines
(via the MDC). Inbound ids are only reused if short and `[A-Za-z0-9-]`; otherwise a fresh UUID is
generated — preventing log forging and response-header injection from a hostile header.

**Q: How does CI run database tests without a managed Postgres service?**
A: The GitHub-hosted Ubuntu runner ships Docker, so Testcontainers starts a real PostgreSQL 17
container on demand. `./mvnw -B verify` runs the same tests as locally — no service-container wiring.

#### 3. Advanced

**Q: The dev-login authority is a flat `ROLE_USER`, yet `/me` returns real domain roles. Where do the
real roles come from, and what's the risk of this split?**
A: Authentication only proves *who* you are (email → session). The domain roles come from the
database via `CurrentUserService` when building `UserContext`. The split is intentional for the dev
stand-in, but the risk is that Spring Security method-level checks (e.g. `@PreAuthorize`) would see
only `ROLE_USER`. Phase 2+ authorization must therefore key off the derived `UserContext`/domain
roles (or map domain roles into granted authorities) rather than the placeholder authority. This is a
known limitation of the ADR-018 stand-in and disappears with Cognito.

**Q: `UserContext` is stored in a `ThreadLocal`. What breaks that model, and what are the
alternatives?**
A: ThreadLocal assumes one thread per request. It breaks with reactive/async handoffs, `@Async`
tasks, or work dispatched to other threads (the value won't propagate and the `finally` clear won't
cover the child thread). With the current servlet, one-thread-per-request model it's fine and it's
cleared per request. If we adopt async/reactive later, we'd propagate context explicitly (task
decorators / context propagation) or pass it as a parameter rather than relying on ThreadLocal.

**Q: How is the "one active membership per user" rule enforced, and why enforce it in the database?**
A: A partial unique index (unique on `app_user` where membership status = ACTIVE). Enforcing it in the
DB makes it race-safe under concurrency — two simultaneous inserts can't both win — whereas an
application-level check has a check-then-act TOCTOU gap. It's proven by a test asserting the second
active membership is rejected.

**Q: `@Version` columns exist now but nothing mutates state yet. Why add them in Phase 1?**
A: They're the foundation for optimistic locking used in Phase 2+ (service requests, claims, consent).
Baking `version` into the schema and entities from the start avoids a later migration and keeps the
concurrency strategy (§31: optimistic locking for these aggregates) consistent from day one.

**Q: Where could tenant isolation still leak today, despite the tests?**
A: The Phase-1 tests cover `/me` and the repository lookup pattern, but there is **no tenant-owned
business endpoint yet**. The real risk surfaces in Phase 2: any new query that forgets to filter by
`organizationId`, any cross-aggregate join, or a service that reads a client-supplied id without
scoping. Mitigations planned: keep repositories tenant-scoped by design, always go through
`requireOrganizationId()`, and extend `TenantIsolation*` with a genuine "fetch another tenant's
record → 404" test as soon as the first such resource exists.

**Q: If you moved from the shared-DB tenant-key model to stronger isolation, what are the options and
trade-offs?**
A: Options ascend in isolation and cost: (1) shared schema + tenant key (current — simplest, cheapest,
relies on disciplined queries + tests; ADR-002); (2) schema-per-tenant (stronger separation, more
migration/ops overhead); (3) database-per-tenant (strongest, highest cost/complexity, hard to query
cross-tenant for platform admins). We chose (1) deliberately for a portfolio-scale system; PostgreSQL
row-level security could be added later as defense-in-depth on top of the tenant key.

**Q: Why `open-in-view: false`, and what does it force you to do?**
A: It disables the Open-Session-In-View anti-pattern, so lazy associations can't be loaded during
view rendering. It forces loading/DTO-assembly to happen inside the service transaction (which is
exactly why `/me` builds a `CurrentUserDto` in the service). This avoids surprise N+1 queries and
`LazyInitializationException`s leaking into the web layer.

---

## Phase 2 care coordination — patients, service requests & the state machine (slices 1–6) — 2026-09-13

> Backfill note: slices 1–6 were built in an earlier working session; this section documents them from
> the committed code and `docs/PROGRESS.md` so the learning file covers all of Phase 2. Slices 7–8
> (comments & assignment) are the section below.

### What we built
The core care-coordination workflow on top of the Phase 1 foundation:

- **Patient profiles** (slices 1–3): a tenant-owned `patient` entity with read + create/update, and a
  React Patients screen (list + add form).
- **Service requests** (slices 4–6): create a request in `DRAFT` and read it; the full **§14.6 state
  machine** (submit → triage → … → approve/reject → close, plus cancellation); and a Requests UI that
  drives the whole lifecycle in the browser (list, create, detail with a status timeline and transition
  buttons).

Test totals grew slice by slice (all observed green): backend 29 → 35 → 38 → 42 → 49; frontend 7 → 13.

### How it works

**Tenant-owned entity pattern (the reference implementation is `patient`).** The row holds
`organization_id` as a plain tenant key, and the repository exposes *only* org-scoped finders — there is
no bare `findById` in business code:

```java
// PatientRepository
Optional<Patient> findByIdAndOrganizationId(UUID id, UUID organizationId);
List<Patient> findByOrganizationIdOrderByFullNameAsc(UUID organizationId);
boolean existsByOrganizationIdAndMedicalRecordNumber(UUID organizationId, String mrn);
```

The service always derives the org from `UserContextAccessor.requireOrganizationId()` and loads by
`(id, organizationId)`, so another tenant's row is simply **not found** — a secure 404, not a 403
(`PatientService.getById`). The migration adds `UNIQUE(organization_id, medical_record_number)` (MRN is
unique *within* a tenant) and `UNIQUE(id, organization_id)` so child tables can later FK-with-org.

**Write path (slice 2).** Create/update require a write role, stamp the tenant from context, validate
with Jakarta `@Valid`, and use optimistic locking:

```java
// PatientService.create — abridged
userContext.requireAnyRole(WRITE_ROLES);              // CARE_COORDINATOR / ORG_ADMIN, else 403
UUID organizationId = userContext.requireOrganizationId();
if (patients.existsByOrganizationIdAndMedicalRecordNumber(organizationId, request.medicalRecordNumber()))
    throw new ConflictException("A patient with that medical record number already exists."); // clean 409
```

Update compares a client-supplied `expectedVersion` to the row's `@Version` (mismatch → 409) and
`saveAndFlush`es so the response DTO carries the incremented version (the client's next
`expectedVersion`).

**Service request + one-transaction history (slice 4).** `service_request` is tenant-owned and points at
a patient via a **composite FK that includes the tenant** (`(patient_id, organization_id) →
patient(id, organization_id)`), so a request can't reference another tenant's patient. Creation writes
the request *and* its first `request_status_history` row (`null → DRAFT`) in one `@Transactional`
(§31.6).

**State machine (slice 5).** The transition table and role rules live in a pure, unit-testable policy
class, `RequestTransitions`:

```java
ALLOWED.put(DRAFT, Set.of(SUBMITTED, CANCELLED));
ALLOWED.put(SUBMITTED, Set.of(TRIAGED, CANCELLED));
// … UNDER_REVIEW → {NEEDS_INFORMATION, APPROVED, REJECTED}; CANCELLED/CLOSED are terminal
```

`ServiceRequestService.changeStatus` checks, in order, **exists → legal move → role → reason →
version**, then updates the status and appends a history row in one transaction. An illegal move is
`INVALID_STATE_TRANSITION` (409); a stale `expectedVersion` is a distinct `CONFLICT` (409); cancel/reject
require a reason (else 400). `PATCH /api/v1/requests/{id}/status` and `GET .../history` expose it.

**Frontend (slices 3 & 6).** Feature folders `src/patients/` and `src/requests/` follow the same shape:
a hooks file (TanStack Query list/mutation with invalidation), a page, and RHF + Zod forms whose schema
mirrors the backend Jakarta rules. Write UI is role-gated for convenience (e.g. the patient Add form
shows only to `CARE_COORDINATOR`/`ORG_ADMIN`), but the backend still enforces it. `transitions.ts` is a
**client mirror** of §14.6 used only to decide which action buttons to show; the backend re-validates
every move, so drift is a UX bug, never a hole. `RequestDetailPage` renders the status, a timeline from
the history endpoint, and transition buttons that send `expectedVersion` and prompt for a reason on
cancel/reject.

### Key points to remember
- **Secure 404, not 403, for cross-tenant reads** — loading by `(id, organizationId)` means you never
  confirm another tenant's row exists.
- **No bare `findById` in business code.** Every finder takes `organizationId`. This is the single most
  important habit for the shared-DB tenant model.
- **`expectedVersion` is the client's optimistic-lock token.** Return the bumped version in the response
  so the client always has the next one.
- **Pre-check uniqueness for a clean 409**, but also map the DB backstop (see failures below) so a true
  race still returns 409, not 500.
- **Keep the state machine pure and unit-testable**; the service orchestrates, the policy class decides.
- **Native `<input type="date">` in tests/automation:** set its value directly (ISO `yyyy-mm-dd`) — you
  can't reliably "type" into it.

### Failures and how we fixed them
- **True write race fell through to a 500.** The service pre-checks (unique MRN, `expectedVersion`)
  handle the common cases, but two callers can both pass the pre-check and collide at the DB. Symptom:
  `ObjectOptimisticLockingFailureException` / `DataIntegrityViolationException` hit the catch-all 500
  instead of 409. Fix: map both in `GlobalExceptionHandler` to a 409 `CONFLICT` with a generic message
  (no SQL/constraint text leaked); added a test proving the `UNIQUE` constraint actually throws.
- **`RequestsPage` test race.** `userEvent.selectOptions` ran before the patients query populated the
  dropdown (only the "Loading…" option existed). Fix: `await screen.findByRole('option', { name: 'Sam
  Sample (NC-0001)' })` before selecting.
- **Live browser: native date input.** Typing `1990-01-01` into `<input type="date">` didn't register →
  Zod reported "Required" (validation working correctly). Fix in automation: set the ISO value directly.
- **MUI select mixed `MenuItem` with a native select** in the create form. Fix: use plain `<option>` for
  native selects and drop the unused `MenuItem` import.

### Interview Q&A

#### 1. Beginner

**Q: What makes an entity "tenant-owned" here?**
A: It carries an `organization_id` column (the tenant key), and all access goes through repository
finders that require that org id. The org comes from the backend session context, never the client, so a
user only ever sees rows in their own organization.

**Q: What's a service request and what states can it be in?**
A: It's a unit of care-coordination work about a patient. Its lifecycle is DRAFT → SUBMITTED → TRIAGED →
ASSIGNED → UNDER_REVIEW → (NEEDS_INFORMATION ↔ UNDER_REVIEW) → APPROVED/REJECTED → CLOSED, with
CANCELLED as an allowed early exit. CANCELLED and CLOSED are terminal.

**Q: Why does creating a request also write a history row?**
A: For an auditable timeline. The first row records `null → DRAFT` with the actor and correlation id;
every later transition appends another. It's written in the same transaction as the request so the two
can never drift.

#### 2. Intermediate

**Q: Why 404 (not 403) when a user requests another tenant's patient?**
A: A 403 would confirm the row exists ("it's there, but you can't have it"), which leaks information
across tenants. Loading by `(id, organizationId)` returns nothing for another tenant's id, so we honestly
report "not found" — the source-of-truth's "secure 404 for existence-sensitive denials".

**Q: How do you prevent lost updates when two users edit the same request/patient?**
A: Optimistic locking. The row has a `@Version`; the client sends the `expectedVersion` it last saw. If
it no longer matches, someone changed the row first, so we throw a 409 and overwrite nothing. The client
reloads and retries.

**Q: Why keep the transition rules in a separate class instead of inline in the service?**
A: `RequestTransitions` is pure logic (no I/O), so it's trivially unit-testable and is the single source
of truth for "what moves are legal and who may make them". The service just orchestrates the ordered
checks and the transaction. It also lets the frontend mirror the same rules for UX without duplicating
service code.

**Q: The frontend hides buttons by role. Isn't that a security risk?**
A: No, because it's not the control. The UI gating is convenience; every transition, create, and update
is authorized again on the backend. If the client mirror drifts from the server rules, the worst case is
a button that shouldn't be there returning a 403/409 — a UX bug, not a breach.

#### 3. Advanced

**Q: You pre-check the unique MRN and the version — why also map DB exceptions to 409?**
A: Pre-checks lose a genuine race: two requests can both pass the check, then one loses at the DB
(`DataIntegrityViolationException` on the unique index, or `ObjectOptimisticLockingFailureException` on
the version). Without mapping, that surfaces as a 500. Mapping both to a 409 with a generic message
closes the race window and avoids leaking SQL/constraint internals.

**Q: How does the composite FK `(patient_id, organization_id)` add safety over a plain FK?**
A: A plain FK on `patient_id` alone would let a bug link a request to a patient in a *different* tenant.
Including `organization_id` in the FK (backed by `patient`'s `UNIQUE(id, organization_id)`) makes
cross-tenant linkage impossible at the schema level — the database refuses it, independent of app code.

**Q: Why is `expectedVersion` enough for state transitions instead of an Idempotency-Key?**
A: A repeated transition carries the version the caller last saw; once applied, that version is stale, so
a duplicate hits a 409 rather than applying twice. That gives double-apply safety without an extra
mechanism. Idempotency-Key is reserved for create-type retriable commands (create request / submit claim
/ start adjudication) where there's no prior version to compare against.

---

## Phase 2 collaboration — request comments & assignment (slices 7–8) — 2026-09-13

### What we built
Two features that complete the Phase 2 care-coordination workflow on top of the service-request state
machine that already existed:

- **Comments** — a collaboration thread on each request. Workflow participants (patient, provider,
  coordinator, admin) can post notes; read-only roles can view but not write.
- **Assignment** — a coordinator/admin can assign a request to a responsible **provider or claims
  reviewer**, and reassign it later. We chose **"Option A": assignment is the only way a request
  reaches the `ASSIGNED` status** — so a request can never be "assigned" with nobody on it.

Both are backend + React UI, tenant-scoped, and fully tested. After these, backend was **61 tests** and
frontend **19 tests** (both observed green locally and in CI).

### How it works

**Comments (slice 7).** A tenant-owned append-only child of `service_request`:

```sql
-- V7__request_comment.sql (abridged)
CREATE TABLE request_comment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    service_request_id UUID NOT NULL,
    author_user_id UUID NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_request_comment_request FOREIGN KEY (service_request_id, organization_id)
        REFERENCES service_request (id, organization_id)   -- composite FK includes the tenant (§32.10)
);
```

`POST/GET /api/v1/requests/{id}/comments` in `ServiceRequestService.addComment/getComments`
(`backend/src/main/java/com/healthcloud/request/`). The author and org are stamped from
`UserContextAccessor` — never the client. Participant roles gate writes; another tenant's request id is
a secure 404. The React side (`RequestDetailPage.tsx` `CommentsCard`) lists the thread oldest-first and
shows an add box (React Hook Form + Zod, ≤2000 chars) only to participant roles.

**Assignment (slice 8).** A **versioned relationship table** where at most one row is ACTIVE:

```sql
-- V8__request_assignment.sql (abridged)
CREATE TABLE request_assignment (
    ..., assignee_user_id UUID NOT NULL, assigned_by_user_id UUID NOT NULL,
    assignee_role VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | SUPERSEDED
    ended_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_request_assignment_request FOREIGN KEY (service_request_id, organization_id)
        REFERENCES service_request (id, organization_id)
);
CREATE UNIQUE INDEX ux_request_assignment_active
    ON request_assignment (service_request_id) WHERE status = 'ACTIVE';  -- one active per request
```

`RequestAssignmentService.assign(...)` does the work in one `@Transactional`:

1. `requireAnyRole(CARE_COORDINATOR, ORG_ADMIN)` → else 403.
2. Load the request by `(id, organizationId)` → else secure 404.
3. Status must be `TRIAGED` (first assign → advances to `ASSIGNED`) or `ASSIGNED` (reassign) → else
   `INVALID_STATE_TRANSITION` (409).
4. Optimistic lock: `request.version == expectedVersion` → else `CONFLICT` (409).
5. Assignee must be a same-tenant user with an ACTIVE membership holding `PROVIDER`/`CLAIMS_REVIEWER`
   → else `VALIDATION_FAILED` (400). We resolve this by reading the `identity` module's repos
   (`OrganizationMembershipRepository`, `UserRoleRepository`).
6. **Supersede then insert:** if an ACTIVE row exists, `supersede()` it (`status→SUPERSEDED`, stamp
   `ended_at`) and **`saveAndFlush` before inserting** the new ACTIVE row (so the partial unique index
   is honored inside the tx), then insert.
7. On a first assignment (from TRIAGED) set status `ASSIGNED`, `saveAndFlush` the request to bump
   `@Version`, and append a `request_status_history` row (`TRIAGED → ASSIGNED`).

Endpoints: `GET /{id}/assignment` (current active or null), `GET /{id}/assignable-users`
(coordinator/admin; minimum-necessary `{userId, fullName, role}`), `PUT /{id}/assignment`. The React
`AssignmentCard` shows the current assignee to everyone and an assign/reassign `<select>` to
coordinators/admins, but only while the status is TRIAGED/ASSIGNED.

Because assignment now owns the ASSIGNED transition, `ServiceRequestService.changeStatus` explicitly
rejects a bare `PATCH /status` to `ASSIGNED`, and the client mirror `transitions.ts` no longer offers
it as a status button.

### Key points to remember
- **Option A (assignment owns ASSIGNED)** keeps status and assignee always consistent. The cost was a
  small state-machine change and updating the state-machine test to reach ASSIGNED via the assign
  endpoint. Worth it — the alternative left two disconnected notions of "assigned".
- **Supersede, don't mutate.** Reassignment keeps full history; the partial unique index
  (`WHERE status='ACTIVE'`) enforces the one-active invariant *and* backstops two coordinators racing
  to assign (the second insert violates the index → mapped to 409).
- **Flush ordering matters.** Hibernate can order INSERTs before UPDATEs in a flush; without an explicit
  `saveAndFlush` on the supersede, the new ACTIVE insert would collide with the still-active old row.
- **Minimum-necessary + no existence leak.** `assignable-users` returns only id/name/role; an ineligible
  same-tenant user returns **400**, not a 403/404 that would reveal role or existence details.
- **Composite tenant FK** on every request child (`comment`, `assignment`, `status_history`) makes
  cross-tenant linkage structurally impossible, not just checked in code.
- This assignee relationship is the input Phase 3's authorization policy evaluator will consume (the
  "unassigned provider → denied" scenario).

### Failures and how we fixed them
- **Existing state-machine test drove ASSIGNED via `PATCH /status`.** Adopting Option A made that path
  return 409, which would have broken three tests. Fix: added an `assign()` helper to
  `ServiceRequestStateMachineApiIntegrationTest` (GET assignable-users → PUT assignment → re-read the
  request's new version) and replaced the `advance(..., "ASSIGNED", ...)` calls with it.
- **Detail-page tests failed once the page called `getAssignment` on every render.** The mocked `api`
  didn't have the new methods, so the assignment query errored. Fix: added `getAssignment` /
  `listAssignableUsers` / `assign` to the mock and set safe defaults (`null` / `[]`) in `beforeEach`.
- No production/runtime bugs this session — backend `verify` and frontend tests were green on the first
  full run after wiring, and the live browser + curl checks matched.

### Interview Q&A

#### 1. Beginner

**Q: What does "assignment is the only path to ASSIGNED" mean?**
A: You can't move a request to the ASSIGNED status with a plain status change. The only way in is to
assign a specific user via `PUT .../assignment`, which records who is responsible *and* flips the status
in the same transaction. A bare `PATCH /status` to ASSIGNED is rejected. This guarantees an ASSIGNED
request always has an assignee.

**Q: Who can comment, and who can assign?**
A: Comment: workflow participants — patient, provider, coordinator, admin (read-only roles like the
claims reviewer can view but not post). Assign: coordinators and org admins only, and the assignee must
be a same-tenant provider or claims reviewer. All of this is enforced on the backend; the UI only
hides controls as a convenience.

**Q: How do we stop a comment or assignment from attaching to another tenant's request?**
A: Two layers. The service loads the parent request by `(id, organizationId)` from the caller's
backend-derived context, so another tenant's id is simply not found (secure 404). And the child table's
foreign key is composite — `(service_request_id, organization_id)` — so the database itself refuses a
cross-tenant link.

#### 2. Intermediate

**Q: Why supersede rows instead of updating the assignment in place?**
A: To keep an auditable history of who was assigned and when, and to make "one current assignee" an
explicit, enforceable invariant. Updating in place would lose the previous assignment and give us no
record of reassignments. Supersession (old row → SUPERSEDED with `ended_at`, new row ACTIVE) preserves
the trail; a partial unique index on `status='ACTIVE'` enforces exactly one current row.

**Q: How is concurrency handled for assignment?**
A: Two mechanisms. The request carries `@Version` and the caller sends `expectedVersion`; a stale value
→ 409 CONFLICT (someone changed the request first). And for the assignment rows themselves, the partial
unique index means two simultaneous assigns can't both insert an ACTIVE row — the loser hits a
constraint violation that maps to 409. So we don't rely on read-then-write being atomic in app code.

**Q: Why did assigning to an ineligible user return 400 rather than 403 or 404?**
A: 403 or 404 could leak information ("this id exists but isn't allowed" vs "doesn't exist"). The
request is well-formed but references a user who isn't an assignable role in this tenant, so it's a
validation failure (400) with a generic message. It doesn't confirm whether that user id exists.

**Q: Why does reassignment not append a status-history row, but the first assignment does?**
A: The first assignment changes the request's *status* (TRIAGED → ASSIGNED), which is exactly what the
status timeline records. A reassignment doesn't change status, so writing a `to=ASSIGNED,from=ASSIGNED`
row would pollute the timeline with non-transitions. The assignment table (superseded + new active
rows) is itself the audit trail for reassignments.

#### 3. Advanced

**Q: Why must you flush the supersede before inserting the new ACTIVE assignment?**
A: The partial unique index allows only one `status='ACTIVE'` row per request. Within a single flush,
Hibernate may order the INSERT before the UPDATE, so the new ACTIVE row would momentarily coexist with
the old ACTIVE row and trip the index. Calling `saveAndFlush` on the superseded row forces the UPDATE to
hit the DB first, so the invariant holds throughout the transaction.

**Q: What changed in the state machine to support Option A, and how do you keep the client honest?**
A: The structural table still lists `TRIAGED → ASSIGNED` (the assign service uses it), but
`changeStatus` explicitly refuses any bare move whose target is `ASSIGNED`. On the client,
`transitions.ts` filters `ASSIGNED` out of the action buttons. The client mirror is a UX convenience
only — the backend re-validates every move — so drift is a UX bug, never a security hole.

**Q: This slice reaches across modules (request → identity). How did you keep that clean?**
A: `RequestAssignmentService` reads the `identity` module's repositories to resolve and validate
assignable users, but exposes only minimum-necessary fields (`userId`, `fullName`, `role`) via a
purpose-specific `assignable-users` endpoint — not a general user directory, which would carry its own
privacy concerns. The dependency direction is one-way (request depends on identity), matching the
modular-monolith conventions.

**Q: How does this set up Phase 3?**
A: Phase 3's hybrid RBAC + attribute policy evaluator checks a **relationship** dimension — e.g. "is
this provider actually assigned to / related to this request or patient?". `request_assignment` is the
first concrete relationship the evaluator can consult; `provider_patient_assignment` and consent join it
later. The seed's "unassigned provider → denied" scenario is precisely this check.

---

## Phase 3 — Consent, Authorization, Privacy & Documents (slices 1–16) — 2026-09-14

### What we built
Phase 3 is the flagship of HealthCloud: the layer that makes the platform *consent-aware* and privacy-safe.
Across 16 slices we built a **five-layer authorization pipeline** and the two big features that depend on it —
**consent** and **secure documents**. The §21.1 pipeline, applied to every protected read in order, is:

```
tenant  →  function/role  →  object/relationship  →  consent + purpose  →  field-level masking
```

Each layer is an independent check that can only *narrow* access; a broad role (coordinator/admin) skips the
relationship layer but still faces consent + masking. The headline acceptance test (§60): **two users with the
same role get different results** because relationship/consent/purpose differ — proven end to end.

What landed, by theme:

- **Consent** (slices 1, 2, 8, 13) — a versioned consent-directive lifecycle, a pure GRANT/DENY decision engine
  (most-specific-tier, DENY-wins, deny-by-default), the CARE_TEAM tier wired to real care-team data, and
  patient self-service (a patient controls their own sharing).
- **Field masking** (slice 3) — the backend withholds a consent-controlled field (`dateOfBirth`) and names it in
  a `maskedFields` list; the SPA only *displays* the restriction.
- **Care relationships** (slices 4, 7) — `provider_patient_assignment` and `care_coordinator_assignment`:
  effective-dated, versioned records that define who is on a patient's care team.
- **The object/relationship gate** (slices 5, 6, 11, 12) — one choke point, `PatientAccessGuard`, that every
  patient-scoped read routes through; a provider reaches only assigned patients, a patient only their own, and an
  unreachable resource is a **secure 404**. Extended to patient-nested endpoints, to service requests, and to the
  patient-self case.
- **UI** (slices 9, 10, 16) — the patient detail page grew a consent card, a care-team card, and a documents card.
- **Secure documents** (slices 14, 15, 16) — metadata in Postgres + bytes behind a storage abstraction, gated by
  the same guard, malware-scanned and quarantined, surfaced in the browser.

End state (observed locally + CI): **backend 156 tests, frontend 33 tests, all green**, and a focused
`/security-review` of the document feature came back clean.

### How it works

**Consent lifecycle & versioning (slice 1, `V9__consent_directive.sql`, `com.healthcloud.consent`).**
A `consent_directive` is an immutable, versioned record of a patient's decision to GRANT or DENY access for a
`(purpose, dataCategory, scope)` natural key. "Changing" it never mutates in place — it **supersedes** the
current row and inserts version+1 in the same `directive_group_id`. At most one *current* (ACTIVE/SCHEDULED) row
per natural key, enforced by a partial unique index. Nullable scope refs are folded with `COALESCE` so org- and
care-team-scoped currents also collide correctly:

```sql
CREATE UNIQUE INDEX ux_consent_current ON consent_directive
    (organization_id, patient_id, purpose, data_category, scope_type, COALESCE(scope_ref_id, '00000000-…'))
    WHERE status IN ('ACTIVE','SCHEDULED');
```

Two version numbers on purpose: a domain `version` (per group: 1, 2, 3…) and a JPA `@Version lock_version`.

**Consent + purpose decision engine (slice 2, `ConsentPolicy` + `ConsentPolicyService`).**
The decision logic is a **pure class** with no Spring/DB deps (same shape as `RequestTransitions`):

```java
ConsentDecision decide(UUID actor, ConsentPurpose purpose, ConsentDataCategory cat,
                       List<ConsentDirective> directives, boolean actorOnCareTeam, LocalDate today)
```

Steps: (1) filter to *applicable* directives — status ACTIVE **and** in force today (effective window re-checked
here, which also covers the not-yet-built time sweep) **and** scope applies to this actor **and** purpose+category
match; (2) pick the **most-specific tier present** — `PROVIDER > CARE_TEAM > ORGANIZATION`; (3) within that tier,
**DENY wins**; (4) nothing applicable → **deny by default**. `ConsentPolicyService` loads the data, computes
care-team membership, and exposes `decideForActor(...)` (the low-level hook masking calls) — you can only ask
"may **I** access this?", never impersonate another actor. Endpoint:
`GET /api/v1/patients/{id}/consent-directives/decision?purpose=&dataCategory=`.

**Field-level masking (slice 3, `PatientFieldPolicy`, `DataClassification`, `PatientDto`).**
The backend is the only trusted masker (§23.3): we build **field-safe DTOs**, never rely on the client to hide a
value it received. Each consent-controlled field maps to a `(DataClassification, ConsentDataCategory)`; the read's
**purpose is backend-fixed per action** (§21.4), not client-chosen. For each such field the read asks
`ConsentPolicyService.decideForActor(...)` — deny-by-default → the field comes back `null` and its name is added
to `maskedFields`. `dateOfBirth` (`DEMOGRAPHICS_CONTACT` / `CONFIDENTIAL`) is the reference implementation; write
responses are never masked (the caller supplied the data).

**Care relationships (slices 4 & 7).** Two sibling tables, `provider_patient_assignment` and
`care_coordinator_assignment`, mirror the versioned/effective-dated shape (states PENDING/ACTIVE/EXPIRED/REVOKED,
at most one current per pair via a partial unique index, composite tenant FK). Assign/revoke are recorded, never
deleted. Together they are the **care team** a CARE_TEAM-scoped directive applies to.

**The object/relationship gate — one choke point (slices 5, 6, 11, 12, `PatientAccessGuard`).**
This is the central design decision of the phase. The relationship layer has **exactly one implementation**:

```java
Patient requireAccessibleInTenant(UUID patientId) {
    UserContext caller = userContext.requireUser();
    UUID org = userContext.requireOrganizationId();
    Patient patient = patients.findByIdAndOrganizationId(patientId, org)
            .orElseThrow(NotFoundException::new);              // cross-tenant → secure 404
    if (isProviderGated(caller) && !isActivelyAssigned(org, caller.userId(), patientId))
        throw new NotFoundException();                          // unassigned provider → secure 404
    if (isPatientSelfGated(caller) && !caller.userId().equals(patient.getAppUserId()))
        throw new NotFoundException();                          // patient reading another → secure 404
    return patient;
}
```

Every patient-scoped read routes through it: the patient read itself, everything nested under a patient (consent
directives, the decision, provider/coordinator assignments, documents), **and** resources *about* a patient that
live under their own top-level route — a **service request is gated by its patient**, so request reads/writes call
`requireAccessibleInTenant(request.getPatientId())`. List reads share one scoping source,
`accessiblePatientIdsIfGated(caller, org)`, which returns the id set a gated caller may see (provider → assigned;
patient → their one linked profile) or `Optional.empty()` for broad roles. The guard depends only on repositories,
so any service can use it with no bean cycle. An object/relationship denial is a **secure 404 (§21.5)** — never a
403 that would confirm the row exists.

**CARE_TEAM scope wiring (slice 8, `CareTeamService`).** `ConsentPolicy.decide` stayed pure by taking
`actorOnCareTeam` as a parameter; `CareTeamService.isOnCareTeam` (an active provider **or** coordinator assignment)
computes the fact and both the `/decision` path and the masking hook pass it in. This closed the "CARE_TEAM not
evaluable" limitation the policy had carried since slice 2, so the full PROVIDER > CARE_TEAM > ORGANIZATION ladder
now works.

**Patient self-service (slices 12 & 13, `V12__patient_user_link.sql`).** A nullable `patient.app_user_id`
(partial-unique, FK to `app_user`) links a login to its own patient profile. That unblocks the patient-self gate
above, and lets a PATIENT record/revoke consent **on their own record** — the write path routes through the same
guard (another patient → secure 404), and the write roles became `{PATIENT, CARE_COORDINATOR, ORG_ADMIN}`.

**Secure documents (slices 14–16, `V13__patient_document.sql`, `com.healthcloud.document`).**
Design faithful to §19 — "bytes in a private object store, metadata in Postgres":

- **Metadata** in `patient_document` (tenant key, `patient_id`, filename/type/size, opaque `storage_key`,
  `scan_status`, uploader). **Bytes** behind a `DocumentStorage` interface with a `LocalFileSystemDocumentStorage`
  stand-in (layout `org/patient/uuid`, path-traversal guarded) — private S3 plugs in at Phase 10 with no
  service/controller change.
- Endpoints nested under the patient: `POST /documents` (multipart), `GET /documents`, `GET /documents/{id}/content`.
  **Every path routes through `PatientAccessGuard`.** Upload is gated to the document write roles; download loads by
  `(documentId, org)` filtered by `patientId` (defeats cross-patient/cross-tenant IDOR) and re-authorizes.
- **Malware scan (slice 15):** a swappable `DocumentScanner` (`FakeDocumentScanner` flags the EICAR test
  signature); `download` withholds anything not `CLEAN` with a 409 `DOCUMENT_NOT_AVAILABLE` (not a secure 404 — the
  caller already sees the row and its status). Sync now; the async event-driven scanner is Phase 8.
- **UI (slice 16):** a documents card — upload, a table with a scan-status chip, and a Download button only for
  CLEAN files (blob fetch → object-URL save). Multipart upload sends `FormData` with **no** explicit
  `Content-Type` so the browser sets the boundary; the client's CSRF header still injects.

### Key points to remember
- **One choke point beats scattered checks.** `PatientAccessGuard` is the single relationship-layer implementation;
  a new patient-or-patient-linked endpoint MUST call it, so the gate can't be side-stepped by a nested/sibling
  route. Slice 6 existed precisely because slice 5 had left the *nested* endpoints ungated — a real back door.
- **Secure 404, not 403, for existence-sensitive denials (§21.5).** A 403 confirms the row exists; a 404 reveals
  nothing. The document *quarantine* refusal is the deliberate exception — a 409 — because the caller can already
  see the document (with its status) in the list.
- **Deny-by-default everywhere in consent.** No applicable GRANT → the field/decision is withheld. Masked fields
  never resurface in logs/exports/events (§23.4).
- **Keep decision logic pure.** `ConsentPolicy` (and `RequestTransitions`) take facts as parameters (`today`,
  `actorOnCareTeam`) so they stay DB-free and trivially unit-testable; a thin service loads data and applies them.
- **Supersede, don't mutate; flush before insert.** The consent, provider- and coordinator-assignment tables all
  reuse the versioned/supersede pattern with a partial unique index on the "current" set — flush the supersede
  before inserting the new current row so the index holds within the transaction.
- **The backend is the only masker.** The SPA renders "Restricted" for a `null` consent-controlled field it was
  never sent; role-aware UI (hiding a form/button) is convenience, never a control.
- **Storage abstraction earns its keep.** Because bytes live behind `DocumentStorage`, the S3 swap at Phase 10 is
  a one-class change, and tests point the local dir at `target/` so `clean` leaves nothing behind.
- **Sync-then-async on purpose.** The malware scan is synchronous now for deterministic tests; `PENDING` stays in
  the model and the download gate defends it, so Phase 8's outbox/worker flow is a drop-in with no API change. We
  explicitly rejected an after-commit event listener now because it would make tests timing-flaky.

### Failures and how we fixed them
- **Shared-Testcontainers "first patient" nondeterminism (recurring, slices 11 & 12).** Tests that grabbed "the
  first patient in the list" passed in isolation but failed in the full suite, because a single Postgres container
  is shared across the JVM fork so list order isn't stable. Symptom: a provider/patient expected 403/200 but got
  404. Fix: target seeded patients **by name/MRN** (e.g. "Sam Sample"), never by position.
- **Flush ordering on supersede (slice 1, same lesson as Phase 2 assignment).** Inserting the new current
  directive before flushing the superseded row tripped the partial unique index. Fix: `saveAndFlush` the supersede
  first.
- **Blast radius on existing tests when a new layer landed.** Slice 5 (the gate) and slice 8 (CARE_TEAM) changed
  what existing provider-read tests returned. Fix: those tests now **assign the provider first** or sign in as a
  broad role, so they keep testing what they intended (tenant isolation, masking) rather than the new gate.
- **`target/ " 2"` duplicate-class artifact (recurring).** The machine kept creating `Foo 2.class` copies under
  `target/`, breaking Surefire / the jar repackage ("wrong name" / "single main class"). Fix: `./mvnw -B clean
  verify` (target/ is git-ignored, so it never affects the commit).
- **Stale local backend after backend changes (recurring).** Live curl/browser checks hit the *old* build still on
  :8080. Fix: always kill :8080 and restart onto the new build after backend code changes (the DB persists; only
  reseed when a new migration needs it).
- **Frontend async-render test flakiness (slices 9, 10, 13).** `getByText('Dana Provider')` before the care-team
  query resolved. Fix: `await screen.findByText(...)`.
- **Misleading browser screenshot vs DOM (slice 13).** A stale screenshot showed "Restricted" while
  `get_page_text`/`find` confirmed the real value — compositor frame lag. Fix: treat the DOM reads as authoritative,
  never send the misleading screenshot as proof.
- **Vite HMR console noise (slice 16).** `DocumentsCard is not defined` and 401/502 lines appeared in the console
  buffer — they were from hot-reloads *mid-edit* and from backend restarts, not real bugs. The reloaded page
  rendered correctly and the production build (which would fail on an undefined reference) succeeded.

### Interview Q&A

#### 1. Beginner

**Q: What does "consent-aware" mean here?**
A: Access to a patient's data isn't decided by role alone. A patient records consent directives — GRANT or DENY
for a specific purpose, data category, and scope (a named provider, the care team, or the whole org). When someone
reads the data, the backend evaluates those directives for *that caller and that purpose* and withholds anything
not granted. Deny-by-default: no applicable GRANT means no access.

**Q: What are the five authorization layers, in order?**
A: Tenant → function/role → object/relationship → consent + purpose → field-level masking. Each is independent and
can only narrow access. Tenant keeps orgs apart; role gates by permission; the relationship layer limits a provider
to their assigned patients (and a patient to their own record); consent applies the directives; masking hides
individual fields.

**Q: What is "field masking"?**
A: A read can return an object with some fields blanked out. For a patient, `dateOfBirth` comes back as `null` and
its name appears in a `maskedFields` list when consent doesn't grant it. The value never leaves the server, so the
UI simply shows "Restricted" — it isn't relying on the client to hide anything.

**Q: Why does an unassigned provider get a 404 instead of a 403 when reading a patient?**
A: A 403 ("forbidden") would confirm the patient exists. A 404 ("not found") reveals nothing about whether the row
exists at all. For existence-sensitive resources we deliberately return a secure 404.

**Q: How does secure document upload/download work at a high level?**
A: Metadata (name, type, size, scan status) is stored in Postgres; the file bytes go into a storage layer (a local
folder now, private S3 later). Upload scans the file for malware; only a CLEAN file can be downloaded. Every
request is authorized on the backend against the patient the document belongs to.

#### 2. Intermediate

**Q: Why is the relationship check a single class, and why does that matter?**
A: `PatientAccessGuard` is the only implementation of the object/relationship layer. Centralizing it means every
patient-scoped endpoint — the patient read, nested resources, service requests, documents — enforces the exact same
rule, so a new route can't accidentally become a bypass. Slice 6 was created specifically because slice 5 gated the
patient read but not the nested endpoints, which leaked existence and data around the gate.

**Q: How does the consent engine decide when directives conflict?**
A: Most-specific tier wins: PROVIDER over CARE_TEAM over ORGANIZATION. Within the deciding tier, DENY wins over
GRANT. If nothing applies, deny by default. So a PROVIDER-scoped GRANT overrides a CARE_TEAM DENY, but a CARE_TEAM
DENY overrides an ORGANIZATION GRANT.

**Q: How did you keep the consent policy testable?**
A: `ConsentPolicy.decide(...)` is a pure function — it takes the directives, `today`, and `actorOnCareTeam` as
inputs and returns a decision, with no Spring or DB. Facts that need the database (like care-team membership) are
computed by a thin service and passed in. That makes the policy exhaustively unit-testable and keeps the "same role,
different result" scenarios easy to assert.

**Q: Why store consent as immutable versions instead of updating a row?**
A: For an auditable history of what the patient decided and when, and to make "one current directive per natural
key" an enforceable invariant. Recording a change supersedes the current row (→ SUPERSEDED, `ended_at`) and inserts
version+1; a partial unique index on the current set guarantees one, and backstops races.

**Q: Why does a quarantined document return 409, not a secure 404 like the relationship denials?**
A: Because an authorized caller already sees the document in the listing with its `scanStatus`. Hiding it with a 404
would confirm nothing new and would be confusing. The 409 `DOCUMENT_NOT_AVAILABLE` says "this exists but its
current state forbids download" — the right signal, and the frontend keys on the stable code.

**Q: How do requests inherit the patient gate without new gate logic?**
A: A request is "about" a patient, so `ServiceRequestService` loads the request in-tenant, then calls
`accessGuard.requireAccessibleInTenant(request.getPatientId())` for every single-request read and participant write.
List reads scope through `accessiblePatientIdsIfGated`. No new relationship code — it reuses the one guard.

#### 3. Advanced

**Q: Walk through exactly what happens when a provider reads a patient they aren't assigned to.**
A: Tenant layer loads the patient by `(id, organizationId)` — found (same tenant). Role layer passes (PROVIDER can
read patients). Relationship layer: `isProviderGated` is true (PROVIDER, not broad) and `isActivelyAssigned` is
false, so the guard throws `NotFoundException` → **secure 404**. The consent and masking layers never run. If the
same provider *were* assigned, they'd pass the gate but `dateOfBirth` could still be masked by the consent layer —
two independent narrowings.

**Q: How is the effective-date window handled so a stale ACTIVE row doesn't over-grant?**
A: `ConsentPolicy` re-checks `effective_from ≤ today ≤ effective_to` at decision time, not just the stored status.
So a directive whose window has passed but whose status hasn't been swept to EXPIRED yet is correctly treated as
not-in-force. That also means the not-yet-built scheduled status sweep (Phase 8) isn't a correctness gap today.

**Q: Why did you fold nullable scope refs with COALESCE in the consent unique index?**
A: Postgres treats NULLs as distinct in a unique index, so two ORGANIZATION-scoped currents (both with
`scope_ref_id = NULL`) wouldn't collide and the "one current per natural key" invariant would break for org/
care-team scopes. `COALESCE(scope_ref_id, <sentinel-uuid>)` maps NULL to a fixed value so those rows collide as
intended, while PROVIDER-scoped rows still key on the real provider id.

**Q: What's the threat model for the document download path, and how is each threat closed?**
A: Cross-tenant IDOR — closed by loading via the tenant-safe finder `findByIdAndOrganizationId` plus a composite
tenant FK. Cross-patient IDOR — closed by `.filter(d -> d.getPatientId().equals(patientId))` *and* the guard on the
patientId. Path traversal — storage keys are server-generated UUIDs and `resolve()` verifies the path stays under
the root. XSS via served bytes — a content-type allowlist excludes HTML/SVG and downloads are always
`Content-Disposition: attachment`. Malware — the scan gate withholds non-CLEAN bytes. A focused `/security-review`
confirmed no high/medium findings.

**Q: How would you evolve the synchronous malware scan into the real async design without breaking the API?**
A: The pieces are already in place: `scan_status` has a `PENDING` state, `DocumentScanner` is an interface, and the
download gate already refuses anything not CLEAN. At Phase 8, upload writes the row `PENDING` and emits an outbox
event; a worker consumes it, runs the real scanner, and updates the row to CLEAN/QUARANTINED. The endpoints,
DTOs, and download gate are unchanged — only *when* the status flips moves from inline to a worker.

**Q: A patient can now write their own consent. How is "own record only" enforced, and why there?**
A: The write path calls the same `PatientAccessGuard.requireAccessibleInTenant(patientId)`, which for a
patient-self-gated caller requires `patientId`'s `app_user_id` to equal the caller's user id — another patient is a
secure 404. Enforcing it in the shared guard (not a special-case check in the consent service) means the identical
rule protects reads, consent writes, and documents, and a future patient-facing endpoint inherits it for free.
