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

---

## Phase 4 — Clinical Context & Claims Intake (slices 1–6) — 2026-09-15

### What we built

Phase 4 is where HealthCloud stops being "care coordination + consent" and starts being a **claims
platform**. Backend-only across six slices, all synthetic data. In dependency order:

1. **Medical code catalog** — a global ICD-10-CM / HCPCS / CPT vocabulary that everything else points at.
2. **Clinical summaries** — short clinical notes about a patient encounter, each citing an ICD-10 diagnosis.
3. **Claims intake** — a claim header + claim lines, each line billing a CPT/HCPCS procedure with a charge.
4. **Claim state machine** — the submission/validation workflow (DRAFT → SUBMITTED → ACCEPTED/REJECTED).
5. **Coverage plan** — a benefit plan (deductible/coinsurance/copay/OOP-max) the org administers.
6. **Patient eligibility** — a patient's enrollment in a plan for an effective-dated period.

Together these are the inputs Phase 5's adjudication engine will consume. Two Phase-4 slices also deliver
the two halves of the §60 acceptance proof — *a claims reviewer sees claim-relevant data without unrestricted
medical context*: slice 2 (the clinical narrative is consent-masked while the coded diagnosis stays visible)
and slice 3 (a claim carries no narrative at all, so the reviewer's work queue is claim data only).

### How it works

**Slice 1 — the catalog is a new pattern: global reference data, NOT tenant-owned.**
Unlike every business table since Phase 2, `medical_code` has **no `organization_id`, no `PatientAccessGuard`,
no consent** — codes are public national standards, identical for every tenant (the app's first shared
reference table, like `role`). Reads require an authenticated caller but are not tenant-scoped. Rows are
immutable in-app (no `@Version`). Files: `backend/src/main/java/com/healthcloud/coding/` (`CodeSystem` enum
carrying a `label` + `category`, `MedicalCode`, `MedicalCodeRepository` with a bounded `search`, service,
controller). `GET /api/v1/medical-codes?system=&q=` and `GET /api/v1/medical-codes/{system}/{code}`. An
unknown `system` binds to no enum constant → 400 via the existing `MethodArgumentTypeMismatchException` handler.

**Slice 2 — clinical summaries reuse the whole Phase-3 stack.**
`clinical_summary` is tenant-owned and about a patient, so it routes every read/write through the shared
`PatientAccessGuard` and consent-masks one field. The free-text `narrative` is `CLINICAL_CONTEXT` /
`SENSITIVE_HEALTH_DATA` (masked deny-by-default via `ConsentPolicyService.decideForActor`, read purpose fixed
to `CARE_COORDINATION`); the structured `diagnosisCode` stays visible. The diagnosis FKs the catalog and is
service-validated as an active ICD-10-CM code. Files: `com/healthcloud/clinical/`, migration
`V15__clinical_summary.sql`.

**Slice 3 — the claim is the first parent→child aggregate with money.**
`claim` (header) owns `claim_line` children; money is `BigDecimal` mapped to `NUMERIC(12,2)`. The header
`totalChargeAmount` is **computed on the backend** from the lines. Claims are **top-level (`/api/v1/claims`)
but gated by their patient** — mirroring `service_request`, not nested like clinical summaries — so a reviewer
gets a cross-patient work queue (`accessiblePatientIdsIfGated` scopes the list). Create is one transaction:
resolve each procedure code (system inferred from the code across CPT/HCPCS; a diagnosis or unknown code → 400),
compute the total, write header + lines atomically. Files: `com/healthcloud/claim/`, `V16__claim.sql`.

```java
// ClaimService: resolve a caller-supplied code to an active CPT/HCPCS entry, else a clean 400.
private MedicalCode resolveProcedureCode(String code) {
    for (CodeSystem system : PROCEDURE_SYSTEMS) { // CPT, HCPCS
        var match = medicalCodes.findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(system, code);
        if (match.isPresent()) return match.get();
    }
    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown procedure code (CPT/HCPCS): " + code);
}
```

**Slice 4 — the claim state machine mirrors the request one.**
A pure `ClaimTransitions` policy class (no Spring/DB) encodes legal moves, role rules, and reason-required;
`ClaimService.changeStatus` applies them in a fixed order and writes a `claim_status_history` row in the same
transaction. `PATCH /api/v1/claims/{id}/status`, `GET /api/v1/claims/{id}/history`.

```java
ALLOWED.put(DRAFT,     Set.of(SUBMITTED, CANCELLED));
ALLOWED.put(SUBMITTED, Set.of(ACCEPTED, REJECTED, CANCELLED));
ALLOWED.put(ACCEPTED,  Set.of(ADJUDICATED)); // engine-owned; a bare PATCH to it is refused
```

Check order: exists + patient gate → reserved (`ADJUDICATED` → 409) → legal move → role → reason →
submit-validation (≥1 line, total > 0) → optimistic `expectedVersion` → status change + history row.
The submitter roles (PROVIDER-assigned / CARE_COORDINATOR / ORG_ADMIN) submit and cancel; the
**CLAIMS_REVIEWER** (+ ORG_ADMIN) accept/reject — the reviewer's first write action in the app.

**Slice 5 — coverage plan: tenant-owned but NOT patient-scoped.**
A third shape: `coverage_plan` uses the tenant pattern (org-scoped finders, cross-tenant → secure 404) but
has **no `PatientAccessGuard`** — it's administrative benefit config, not PHI. Reads open to any same-tenant
user; **create requires ORG_ADMIN**; `plan_code` unique per tenant. Holds the Phase-5 parameters:
`deductibleAmount`, `coinsuranceRate` (member share after deductible, `NUMERIC(5,4)`, CHECK 0..1),
`copayAmount`, optional `outOfPocketMax`, `planType` (HMO/PPO/EPO/HDHP). Files: `com/healthcloud/coverage/`,
`V18__coverage_plan.sql`.

**Slice 6 — patient eligibility bridges patient ↔ plan ↔ date.**
`patient_eligibility` (same `coverage` package, but patient-scoped) records an enrollment for an effective-dated
period (`effectiveFrom`, nullable `effectiveTo`, a `memberId`), FKing both `patient` and `coverage_plan`
with-org. Enroll is CARE_COORDINATOR/ORG_ADMIN behind the patient gate; the plan must be in-tenant (else 400);
and periods for a patient are kept **non-overlapping** (enforced in the service → 409) so coverage-on-a-date is
deterministic. The Phase-5 hook is the covering-date query (also surfaced as `GET .../eligibility?asOf=`):

```java
@Query("""
        SELECT e FROM PatientEligibility e
        WHERE e.organizationId = :organizationId AND e.patientId = :patientId
          AND e.effectiveFrom <= :date AND (e.effectiveTo IS NULL OR e.effectiveTo >= :date)
        """)
List<PatientEligibility> findCovering(UUID organizationId, UUID patientId, LocalDate date);
```

### Key points to remember

- **Three tenancy shapes now coexist, and choosing the right one is the core design skill of Phase 4:**
  (a) *global reference* — `medical_code` (no org, no gate); (b) *tenant-owned, not patient-scoped* —
  `coverage_plan` (org-scoped, no relationship gate); (c) *tenant-owned + patient-scoped* — `clinical_summary`,
  `claim`, `patient_eligibility` (org-scoped **and** through `PatientAccessGuard`).
- **Nested-under-patient vs top-level-gated-by-patient** is a deliberate call: clinical summaries and eligibility
  are nested (`/patients/{id}/...`) because they're read per-patient; claims are top-level (`/claims`) because a
  reviewer needs a cross-patient queue. Both still route the patient id through the same guard.
- **Money is `BigDecimal` + `NUMERIC(12,2)`** everywhere, with DB CHECKs (`>= 0`, coinsurance `0..1`). Never
  `double`. Jackson serialises `BigDecimal` by its scale, so `195.50` may print as `195.5` — assert on a
  substring (`"195.5"`), not exact text, in tests.
- **Structural integrity via composite FKs:** child rows FK `(parent_id, organization_id)` to the parent's
  `UNIQUE(id, organization_id)` so tenancy can't be crossed even by a bug; and coded fields FK the global
  catalog `(code_system, code)` so a claim line / diagnosis can't reference a code that doesn't exist.
- **Reference data first:** the seeder was reordered so `medical_code` seeds *before* the orgs, because
  clinical summaries and claim lines FK the catalog and are seeded during org setup.
- **The §60 proof is split:** clinical narrative is consent-masked (slice 2) *and* claims structurally carry no
  narrative (slice 3). The stronger "reviewer sees claims regardless of consent, but never clinical context"
  business-need rule is still deferred to the permission matrix.
- **State reached by a command, not a bare status change:** `ADJUDICATED` is reserved for the Phase-5 engine,
  exactly like `ASSIGNED` on a service request is reached only by assigning a user — a bare `PATCH /status`
  to it is refused.
- **Non-overlapping eligibility is what makes adjudication deterministic** — it guarantees `findCovering`
  returns at most one row, so the engine never has to disambiguate two plans on one service date.

### Failures and how we fixed them

- **`.gitignore` silently swallowed a whole backend package.** After building slice 5, `git add -A` staged only
  4 files — none of the 9 new `com.healthcloud.coverage` Java files. Symptom: `git status` showed the package
  missing though the build compiled (files were on disk); the commit would have failed CI.
  Root cause: `.gitignore` had a bare `coverage/` (meant for the frontend's Vitest coverage-report folder), and
  a bare directory name matches **any** directory of that name anywhere — including the Java package.
  Fix: scope it to `frontend/coverage/`, confirm with `git check-ignore -v`, `git add` the files, and
  `git commit --amend` (the commit hadn't been pushed). Lesson: **never use a bare directory name in
  `.gitignore`; scope it to a path.**
- **`@Valid` on a `List<>` container is deprecated (Hibernate Validator).** The build warned about
  `@Valid List<CreateClaimLineRequest>`. Fix: move the annotation to the type argument —
  `List<@Valid CreateClaimLineRequest>` — so each element is cascaded-validated.
- **A greedy `sed` in a live-check curl produced a misleading 404.** A shell one-liner used
  `sed -E 's/.*"id":"([0-9a-f-]{36})".*/\1/'`; the greedy `.*` matched the **last** id in the JSON (a claim
  line's id) instead of the claim header's, so a follow-up request hit a non-existent claim and returned a
  (correct) secure 404 — which briefly looked like a role bug. Root cause was the test harness, not the app; the
  automated integration tests (first-match extraction) proved the real 403s. Lesson: in JSON, extract the
  **first** match, and trust the automated test over an ad-hoc curl.
- No production-code defects surfaced this phase; every `./mvnw -B clean verify` was green
  (156 → 165 → 173 → 184 → 196 → 203 → 213 tests as the slices landed).

### Interview Q&A

#### 1. Beginner

**Q: What is the medical code catalog and why is it "global" instead of per-tenant?**
A: It's a lookup table of standard ICD-10-CM (diagnosis) and HCPCS/CPT (procedure) codes. These are public
national standards — the same for every organisation — so the table has no `organization_id`, no relationship
gate, and no consent. It's shared reference data (like the `role` table), read by any authenticated user.

**Q: What's the difference between a clinical summary and a claim?**
A: A clinical summary is *medical* context — a note about an encounter with a diagnosis code, and a free-text
narrative that is consent-controlled. A claim is *financial* — a header plus billed lines (procedure code +
amount) with no clinical narrative. Keeping them separate is what lets a claims reviewer see billing data
without unrestricted medical context.

**Q: What is a claim header vs a claim line?**
A: The header is the claim as a whole (patient, service date, status, total). Each line is one billed item —
a procedure code, units, and a charge amount. The header total is the sum of the lines, computed on the server.

**Q: Who can move a claim to ACCEPTED, and who can submit it?**
A: The submitter side (an assigned provider, a coordinator, or an admin) submits a DRAFT claim. A
**claims reviewer** (or admin) then accepts or rejects the submitted claim. Rejecting requires a reason.

**Q: What does a coverage plan hold, and what is patient eligibility?**
A: A coverage plan holds benefit parameters — deductible, coinsurance rate, copay, out-of-pocket max, plan type.
Patient eligibility links a specific patient to a plan for a date range (with a member id), so the system knows
what coverage was in effect on any given day.

#### 2. Intermediate

**Q: You now have three "shapes" of table. How do you decide which a new entity should be?**
A: Ask two questions. *Is it the same for every tenant?* If yes → global reference (no `organization_id`), like
`medical_code`. If no, it's tenant-owned. Then: *is it about a specific patient?* If yes → tenant-owned **and**
routed through `PatientAccessGuard` (clinical summaries, claims, eligibility). If no → tenant-owned but not
patient-scoped, gated only by role (coverage plans). Getting this wrong either over-restricts admin config or
leaks patient data.

**Q: Why are claims top-level (`/api/v1/claims`) but clinical summaries nested under the patient?**
A: A claims reviewer works a queue *across* patients, so claims need a top-level list that scopes to the
caller's accessible patients (`accessiblePatientIdsIfGated`) — the same idiom service requests use. Clinical
summaries are always read in the context of one patient, so nesting them under `/patients/{id}` is clearer.
Either way the patient id still goes through the one guard, so the access rule is identical.

**Q: How does the claim state machine stay testable and correct?**
A: The rules (legal moves, which role may make each move, when a reason is mandatory) live in a pure
`ClaimTransitions` class with no Spring or DB dependencies — fast unit tests cover every combination. The
service loads the claim, runs the checks in a fixed order, and writes the status change plus a history row in
one transaction. This mirrors `RequestTransitions`, so the pattern is consistent across the codebase.

**Q: Why compute the claim total on the backend instead of trusting the client?**
A: The total drives money decisions later (adjudication). Trusting a client-supplied total would let a caller
misstate what the lines add up to. The server sums the line charges itself, so the header is always internally
consistent with its lines.

**Q: How is a claim's procedure code validated, and why resolve the system from the code?**
A: On create, the service looks the code up in the catalog across the procedure systems (CPT then HCPCS); if
it's not an active procedure code — including if it's a diagnosis code — it's a 400. Resolving the system from
the code means the caller sends just the code string and can't mismatch the system, and the canonical spelling
from the catalog is what gets stored.

**Q: What makes patient eligibility "deterministic" for adjudication?**
A: The service forbids overlapping coverage periods for a patient (409 on overlap). That guarantees the
covering-date query returns at most one row, so when the engine asks "what coverage was in effect on the
service date?" there's never an ambiguous answer.

#### 3. Advanced

**Q: Walk through exactly what happens when a claims reviewer opens the claim queue.**
A: `ClaimService.list` derives the org from context, then calls `accessiblePatientIdsIfGated(caller, org)`.
A reviewer isn't provider-gated or patient-self-gated, so that returns `Optional.empty()` (broad) and the
query returns all the tenant's claims. A provider instead gets the set of their actively-assigned patient ids
and the list is filtered to those. Each row is a header-only summary (`ClaimSummaryDto`) — no line query — so
the list is a single DB round-trip. The reviewer never sees any clinical narrative because a claim doesn't
carry one.

**Q: Why is `ADJUDICATED` structurally legal from `ACCEPTED` but refused by the status endpoint?**
A: The transition table allows `ACCEPTED → ADJUDICATED` because it *is* a real lifecycle edge — but reaching it
must do more than flip a status (it produces adjudication outcomes and amounts). So the bare `PATCH /status`
guards against it explicitly and returns 409, reserving that move for the Phase-5 engine's dedicated command.
It's the same "a status owned by a command, not a bare change" principle as `ASSIGNED` on service requests.

**Q: How do the composite foreign keys make cross-tenant or dangling references structurally impossible?**
A: Each parent exposes `UNIQUE(id, organization_id)`. A child stores `organization_id` and FKs
`(parent_id, organization_id)` to that unique key — so a child can only attach to a parent in the *same* tenant;
there's no way to reference another tenant's row even with a bug in the service. Separately, coded fields FK the
global catalog's `(code_system, code)`, so a claim line or diagnosis can't point at a code that isn't in the
catalog. The database enforces both invariants regardless of application logic.

**Q: The eligibility overlap check runs in the service, not the database. What are the trade-offs, and how
would you harden it?**
A: In-service is simple and gives a clean domain error (409 with a message), but it's a check-then-act that
isn't concurrency-safe under two simultaneous enrolments. For the synthetic single-writer demo that's fine. To
harden it you'd add a Postgres exclusion constraint using a `daterange` and a GiST index
(`EXCLUDE USING gist (patient_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&)`), which makes
overlap impossible at the storage layer and turns a race into a constraint violation the service maps to 409 —
the same belt-and-suspenders approach used for the "one ACTIVE" partial unique indexes elsewhere.

**Q: Jackson serialised a `BigDecimal` total as `195.5` in one place and `195.50` in another. Why, and does it
matter?**
A: Jackson writes a `BigDecimal` using its scale. A value read back from `NUMERIC(12,2)` keeps scale 2
(`195.50`); an in-memory sum built from `new BigDecimal("150.00").add(new BigDecimal("45.50"))` is also scale 2,
but other construction paths can drop trailing zeros. It doesn't affect correctness — the stored value is exact
in `NUMERIC(12,2)` — but tests should assert on a tolerant substring (`"195.5"`) rather than exact text, which
is what we did.

**Q: If you had to make claim creation idempotent (a retried "submit claim" command), where would it hook in?**
A: The architecture already reserves an `Idempotency-Key` for retriable create-type commands (create request /
submit claim / start adjudication). Claim *creation* is the natural place: the controller would accept the key,
the service would record it with the resulting claim id in a dedupe table inside the same transaction as the
insert, and a retry with the same key returns the original claim instead of creating a second one. We didn't
build it this phase (not required for intake), but the aggregate-in-one-transaction shape means adding it later
doesn't disturb the create logic.

---

## Phase 5 — Basic Synthetic Claims-Adjudication Engine (slices 1–12) — 2026-09-15

### What we built
The MVP's finale: a **deterministic, explainable claims-adjudication engine** and the full UI around it. Given an
**ACCEPTED** claim, the engine finds the coverage in effect on the claim's service date, applies the plan's
benefit rules, and produces an **immutable, per-line breakdown** of how every dollar splits between the plan and
the member — the §60 "for any decision, show which plan applied and how every amount was computed" proof. Over
twelve slices we built the math (allowed → copay → deductible → coinsurance → out-of-pocket max → exclusions →
fee-schedule allowed amounts), the cross-claim **benefit accumulator** (deductible/OOP carried across a benefit
year under a row lock), **re-adjudication with immutable versioning**, and the React UI for the whole story
(claims work queue + detail, claim creation, coverage-plan admin, exclusions, fee schedule, eligibility
enrollment, and the adjudication breakdown + version history). **Reaching the end of Phase 5 = the demonstrable
MVP (Phase 0–5), engine and UI.**

Slice map (each: plan → build → automated tests → live check → docs → commit → push):

- **1** core engine — `AdjudicationCalculator` (pure), `adjudication` + `adjudication_line` tables, `POST
  /api/v1/claims/{id}/adjudicate` + `GET .../adjudication`, ACCEPTED→ADJUDICATED in one tx, `DENIED_NO_ELIGIBILITY`.
- **2** `benefit_accumulator` — deductible carried across claims under a `PESSIMISTIC_WRITE` row lock.
- **3** out-of-pocket max — the member's cost-sharing is capped; the excess shifts to the plan.
- **4** `plan_exclusion` — excluded procedures adjudicate `NOT_COVERED` without touching the deductible/OOP.
- **5** claims frontend — work queue + detail, lifecycle buttons, Adjudicate, breakdown card.
- **6** claim-creation form + reusable `MedicalCodePicker`.
- **7** coverage admin UI — plans list/create + exclusions card.
- **8** patient eligibility enrollment UI — a Coverage eligibility card on the patient page.
- **9** fee schedule — `allowed = min(charge, fee-schedule amount)` instead of `allowed = charge`.
- **10** fee-schedule admin UI.
- **11** re-adjudication versioning — a new immutable version, with accumulator reversal.
- **12** adjudication version history + Re-adjudicate button in the claims UI.

### How it works

**The pure calculator (the third pure-policy exemplar).** `backend/src/main/java/com/healthcloud/adjudication/
AdjudicationCalculator.java` has no Spring, no DB, no I/O — just the math, so it's exhaustively unit-testable. It
takes the plan's parameters, the remaining deductible and remaining OOP (carried in by the caller), and the
per-line charges + allowed amounts, and returns a `Computation` (per-line splits + totals). Per line, in order:

```
allowed        = min(feeScheduleAmount ?? charge, charge)   // slice 9
copay          = min(planCopay, allowed)
deductible     = min(remainingDeductible, allowed - copay)  // consumed across lines
coinsurance    = round((allowed - copay - deductible) * coinsuranceRate)   // HALF_UP, scale 2
grossMember    = copay + deductible + coinsurance
member         = min(grossMember, remainingOOP)             // slice 3; null OOP = no cap
oopMaxApplied  = grossMember - member                       // the excess that shifted to the plan
planPaid       = allowed - member
```

Money is `BigDecimal` at scale 2, `HALF_UP`; the coinsurance rate is `NUMERIC(5,4)` in `[0,1]`. The calculator is
pure, so the *service* is what loads data and decides covered-vs-excluded, denial, and accumulator updates.

**The service orchestration (`AdjudicationService.adjudicate`, one `@Transactional`).**
`backend/src/main/java/com/healthcloud/adjudication/AdjudicationService.java`:

1. authorize: role (`CLAIMS_REVIEWER`/`ORG_ADMIN`) → load claim in tenant → `PatientAccessGuard` (via the claim's
   patient → secure 404). Same layered pipeline as everywhere else (§21).
2. state gate: ACCEPTED → first adjudication (v1, advance to ADJUDICATED); ADJUDICATED → re-adjudicate (v+1,
   status unchanged); anything else → 409 `INVALID_STATE_TRANSITION`.
3. find coverage: `PatientEligibilityRepository.findCovering(org, patient, serviceDate)` → 0 or 1 row
   (periods are non-overlapping). No row → `DENIED_NO_ELIGIBILITY` (plan pays 0, member owes the charge).
4. partition lines into **covered** vs **excluded** (`plan_exclusion`); excluded → `NOT_COVERED`, skip the
   cost-sharing math so they don't consume the deductible/OOP.
5. lock the accumulator, compute the covered lines through the calculator (fee schedule resolves each line's
   `allowed`), then update the accumulator, and persist the immutable header + per-line rows.
6. on the first adjudication only, set the claim to ADJUDICATED and append a `claim_status_history` row.

**The benefit accumulator (financial row lock, §31).**
`benefit_accumulator` is one row per `(patient, coverage_plan, benefit_year)` holding `deductible_met` and
`out_of_pocket_met`. The engine reads-and-updates it **inside the adjudication transaction under a
`PESSIMISTIC_WRITE` lock** so concurrent adjudications for the same patient/plan/year can't lose an update. The
row is guaranteed to exist before the locked read via an **insert-if-absent** (native `ON CONFLICT DO NOTHING`):

```java
accumulators.insertIfAbsent(org, patientId, planId, benefitYear);          // ON CONFLICT DO NOTHING
BenefitAccumulator acc = accumulators.lockByKey(org, patientId, planId, benefitYear)  // @Lock(PESSIMISTIC_WRITE)
        .orElseThrow(...);
```

The calculator is fed `deductibleRemaining = plan.deductible - acc.deductibleMet` and (if the plan caps it)
`oopRemaining = plan.outOfPocketMax - acc.outOfPocketMet`. `benefit_year` is the claim's service-date calendar
year (MVP: plan year = calendar year).

**Fee schedule (slice 9).** `plan_fee_schedule` prices procedures per plan; the service builds a `code → allowed`
map for the covering plan and passes each covered line its resolved allowed. `LineCharge` gained an
`allowedAmount` (with a 2-arg convenience constructor `allowed = charge`, so the "no fee schedule" fallback and
all existing call sites keep working). `charge − allowed` is a provider write-off nobody pays (the in-network
model).

**Re-adjudication + immutable versioning (slice 11).** The schema always supported versions
(`adjudication_version`, `UNIQUE(org, claim_id, version)`), so no migration was needed. Re-adjudicating an
ADJUDICATED claim writes `version = priorMax + 1` and keeps every prior version; latest-version-wins. The subtle
part is the accumulator: the first adjudication already added this claim's deductible/OOP, so before recomputing
we **back out the prior version's contribution** (read from that version's own immutable line snapshot) and then
add the new one:

```java
// reversePriorContribution(): sum the prior version's COVERED lines
priorDeductible  = Σ line.deductibleAppliedAmount   (COVERED lines)
priorOutOfPocket = Σ line.memberResponsibility      (COVERED lines)
lockAccumulator(...).subtract(priorDeductible, priorOutOfPocket);   // clamped at 0
```

So re-adjudicating an **unchanged** claim yields identical amounts (proving no double-count), and a real change
(fee schedule, exclusion, or a retroactive enrollment flipping DENIED→covered) recomputes correctly. Reads:
`GET .../adjudication` returns the latest version; `GET .../adjudication/versions` lists all, newest first.

**The frontend (slices 5–8, 10, 12).** Feature folders `frontend/src/claims/` and `frontend/src/coverage/`,
each following the established pattern: a typed `api/client.ts` method, a TanStack Query hook (`useX`), and a
component. Highlights:

- `ClaimsPage` (work queue) + `ClaimDetailPage` (lines, timeline, lifecycle buttons from a client mirror of
  `ClaimTransitions`, the Adjudicate / Re-adjudicate engine commands, the breakdown card, and the version-history
  card).
- `MedicalCodePicker` — an MUI `Autocomplete` (freeSolo + debounced) searching the catalog, reused by claim
  creation, exclusions, and the fee schedule.
- `CoveragePlansPage` / `CoveragePlanDetailPage` (parameters + Exclusions card + Fee schedule card), and an
  Eligibility card on the patient detail page.

### Key points to remember

- **The engine command is not a bare status change.** A claim reaches ADJUDICATED only through
  `POST .../adjudicate` (which does the math + writes the record + advances status in one tx), exactly like
  ASSIGNED on a request reaches its state only through the assignment command. A plain `PATCH /status` to
  ADJUDICATED is refused. This keeps a status from ever being set without its accompanying record.
- **Pure policy class + thin service.** `AdjudicationCalculator` is the third exemplar after `ClaimTransitions`
  and `ConsentPolicy`. Keeping the math DB-free made the OOP/deductible/fee-schedule edge cases cheap to test
  (11+ calculator unit tests, no Spring context).
- **Two kinds of locking, on purpose.** Optimistic locking (`@Version` / `expectedVersion`) guards
  request/claim/consent edits; a **pessimistic row lock** guards the *financial accumulator*, because two
  adjudications racing on the same patient/plan/year must serialize, not just detect-and-retry. The source-of-truth
  calls this out explicitly ("row locks for financial accumulators").
- **Immutability + latest-wins** beats mutate-in-place for adjudications: every version is an auditable snapshot,
  and re-adjudication is just "append a new version," which is why the schema reserved `adjudication_version` from
  slice 1. `getByClaim` had to switch to `findFirst…OrderByAdjudicationVersionDesc` once >1 version can exist (a
  single-row finder throws `IncorrectResultSize` on the second version).
- **The accumulator reversal is the one genuinely tricky invariant.** Re-adjudication must not double-count; we
  reverse *this claim's* prior contribution and recompute. Honest limitation: it does **not** retroactively
  re-adjudicate *other* claims in the same benefit year (documented, not hidden).
- **Seed data must not fight the tests.** The accumulator integration test asserts exact 99213 amounts on the
  seeded PPO, so we deliberately seeded the demo fee-schedule entry on **80053** (not 99213). Lesson: shared
  seed data is a test fixture — changing it can break amount-asserting tests in other suites.
- **Frontend gates are convenience only.** Every Adjudicate/Re-adjudicate/Enroll/price button is role-gated in
  the UI to match the backend, but the backend re-authorizes every call — client mirrors (`transitions.ts`) are a
  UX nicety, never a security control.
- **`.gitignore` bare directory names are a trap** (re-confirmed this phase). A bare `coverage` line matched the
  new `frontend/src/coverage/` feature folder and silently excluded 5 source files from a commit. Anchor
  location-specific ignores (`/coverage/`) and run `git status --short` after adding a feature folder. Now a rule
  in CLAUDE.md.
- **RHF + coercing Zod needs three generics.** A schema using `z.coerce`/`z.preprocess` has different input and
  output types, so forms must be typed `useForm<Input, unknown, Output>` or `tsc` rejects the resolver.
- **Measured results (no unmeasured claims):** at the end of Phase 5, `./mvnw -B clean verify` → **258 backend
  tests pass**; frontend `npm run typecheck` + `npm test` → **60 tests pass** + `npm run build` OK. Live checks
  (db-reset + backend + curl/Vite) confirmed each slice end to end.

### Failures and how we fixed them

- **`.gitignore` swallowed the new feature folder (slice 7).** *Symptom:* `git status --short` showed only `M`
  files, no `A` for `src/coverage/`; the first commit would have broken CI (App.tsx imports uncommitted files).
  *Root cause:* a bare `coverage` line in `frontend/.gitignore` (meant for Vitest output) matched the source
  folder anywhere in the tree. *Fix:* anchored it to `/coverage/`, re-staged, amended the commit; recorded the
  durable rule in CLAUDE.md.
- **RHF resolver type error (slice 6).** *Symptom:* TS2322/TS2345 on `zodResolver`. *Root cause:* the coercing
  Zod schema's input ≠ output types. *Fix:* the 3-generic `useForm<Input, unknown, Output>` form; documented.
- **Async query races in frontend tests (slices 5, 7).** *Symptom:* `getByText(...)` ran before an async query
  resolved. *Fix:* `await screen.findByText(...)`.
- **`getByLabelText` ambiguity in the eligibility test (slice 8).** *Symptom:* "Found multiple elements" for
  "From"/"Effective from" — the care-team and consent forms on the same patient page also have date fields with
  those labels. *Fix:* gave the eligibility form distinct labels ("Coverage start"/"Coverage end").
- **Seeding a fee schedule on 99213 would have broken the accumulator test (slice 9).** *Caught before running:*
  the accumulator integration test asserts exact 99213 amounts on the seeded PPO. *Fix:* seeded the demo entry on
  80053 instead, which no amount-asserting test uses.
- **The "adjudicated only once → 409" test became wrong when re-adjudication landed (slice 11).** *Symptom:* it
  would fail because a second adjudicate now returns 200 (a new version). *Fix:* rewrote it to
  `re_adjudicating_an_adjudicated_claim_creates_a_new_version`, asserting v1 then v2 and status still ADJUDICATED;
  also switched a repository test off the removed single-row finder.
- **Transient Maven `MojoExecutionException`** on a subset test run (slice 4). *Fix:* re-ran; a clean
  `./mvnw -B clean verify` was green — a hiccup, not a real failure.

### Interview Q&A

#### 1. Beginner

**Q: What does "adjudicating a claim" mean here?**
A: Taking an accepted claim and computing, for each billed line, how much the insurance plan pays versus how much
the patient owes — applying the plan's copay, deductible, coinsurance, out-of-pocket max, exclusions, and
fee-schedule allowed amounts — and recording an explainable breakdown of every amount.

**Q: What are the possible outcomes of adjudication?**
A: `ADJUDICATED` (coverage was in effect on the service date and the split was computed) or
`DENIED_NO_ELIGIBILITY` (no coverage on that date, so the plan pays 0 and the member owes the full charge). Both
are recorded, explainable decisions. Individual lines are `COVERED` or `NOT_COVERED`.

**Q: Why is `AdjudicationCalculator` a "pure" class?**
A: It has no Spring, database, or I/O — it takes plain inputs and returns plain outputs. That makes the money math
deterministic and trivial to unit-test (every edge case without a database), and keeps the decision logic
separate from data loading, which lives in the service.

**Q: What's the difference between the Adjudicate and Re-adjudicate buttons?**
A: Adjudicate appears on an ACCEPTED claim (first run → version 1). Re-adjudicate appears on an already-ADJUDICATED
claim and runs the engine again, appending a new immutable version (2, 3, …) — useful after a fee-schedule,
exclusion, or coverage change. Both call the same backend command.

**Q: How does the deductible carry from one claim to the next?**
A: A `benefit_accumulator` row per patient/plan/year tracks how much deductible has been met. Each adjudication
reads how much remains, applies what it can, and adds its contribution back — so a later claim in the same year
sees less deductible remaining and the plan pays more.

#### 2. Intermediate

**Q: Why a pessimistic row lock for the accumulator when everything else uses optimistic locking?**
A: Optimistic locking detects a conflicting concurrent edit and makes one side retry — fine for a user editing a
request. But two claims for the same patient/plan/year adjudicating at once both read-modify-write the *same*
running total; with optimistic locking one would fail and need re-driving. A `PESSIMISTIC_WRITE` lock serializes
them so the second simply waits and reads the first's committed value — correct accumulation with no lost update
and no retry loop. The source-of-truth prescribes row locks specifically for financial accumulators.

**Q: Why is `insert-if-absent` (ON CONFLICT DO NOTHING) needed before the locked read?**
A: You can't lock a row that doesn't exist. On the first claim of a year there's no accumulator row yet; two
concurrent adjudications could both try to create it and one would hit the unique constraint. `ON CONFLICT DO
NOTHING` guarantees the row exists (idempotently, race-safe) so the subsequent `SELECT … FOR UPDATE` always finds
and locks exactly one row.

**Q: Why model the allowed amount as `min(charge, fee-schedule amount)` and where does the difference go?**
A: A plan never pays on more than was billed, and it caps recognition at its contracted fee-schedule amount. The
difference (`charge − allowed`) is a provider write-off that nobody pays — the standard in-network model. Every
downstream amount (copay/deductible/coinsurance/OOP/split) is computed off `allowed`, so once `allowed` is right
the whole computation is realistic. A procedure with no fee-schedule entry falls back to `allowed = charge`.

**Q: How does re-adjudication avoid double-counting the deductible?**
A: Before recomputing, the engine reverses the prior version's accumulator contribution — it sums that version's
own COVERED lines' `deductibleAppliedAmount` and `memberResponsibility` from the immutable line snapshot and
subtracts them from the accumulator (clamped at 0). Then it recomputes against the corrected remaining amounts and
adds the new contribution. Re-running an unchanged claim therefore produces identical numbers.

**Q: Why is the adjudication a dedicated command rather than a status transition to ADJUDICATED?**
A: Reaching ADJUDICATED requires doing real work — finding coverage, computing, writing the immutable record — all
atomically with the status change and history row. A bare `PATCH /status` couldn't produce the record, so we
forbid it and route the state change through the engine command (the same pattern as ASSIGNED via the assignment
command). This guarantees a status is never set without its accompanying record.

**Q: How is the §60 "explainability" requirement actually satisfied?**
A: The `adjudication` header records which plan and eligibility applied and the totals; each `adjudication_line`
records allowed/copay/deductible/coinsurance/oop-applied/plan-paid/member for that line. The values are an
immutable snapshot (codes and charges copied in), so the decision reads back self-contained and the UI shows the
full per-line breakdown — for any decision you can see which plan applied and how every amount was computed.

#### 3. Advanced

**Q: What are the honest limitations of the re-adjudication reversal, and how would you address them?**
A: The reversal only corrects *this* claim's contribution; other claims adjudicated later in the same benefit year
were computed against the old running total and are not retroactively recomputed. A fuller design would either
re-adjudicate every subsequent claim in the year in service-date order (expensive, and it changes historical
records) or model the accumulator as an event log of per-claim deltas so a claim's delta can be replaced and
downstream effects recomputed deterministically. For an MVP we chose the simpler, documented behavior.

**Q: Re-adjudication currently creates a new version on every call. What are the risks and how would you harden it?**
A: An accidental double-submit would create spurious versions. The source-of-truth reserves an `Idempotency-Key`
for retriable commands (create request / submit claim / start adjudication); adjudication is a natural fit — the
controller would accept the key, the service would record it with the resulting version inside the same
transaction, and a retry with the same key returns the existing version instead of appending another. We didn't
build it this phase.

**Q: Two reviewers re-adjudicate the same claim at the same time. What happens?**
A: Both compute `nextVersion = priorMax + 1` and try to insert that version; `UNIQUE(org, claim_id,
adjudication_version)` lets only one commit — the other fails the constraint and rolls back. The accumulator's row
lock also serializes their read-modify-write. So concurrency yields one new version, not two conflicting ones, and
no lost accumulator update. The loser can retry (which would then compute the next version).

**Q: If a plan is deleted or its parameters change after a claim was adjudicated, is the old adjudication still
correct to read back?**
A: Yes — the adjudication and its lines are an immutable snapshot; charges, codes, and computed amounts are copied
into `adjudication_line`, so reading version N reflects exactly what was decided then, independent of later plan
edits. Re-adjudication is the explicit, versioned way to apply new plan state, leaving the prior version intact
for audit.

**Q: Why compute the header `totalChargeAmount` and the split on the backend rather than trusting the claim's
stored total or the client?**
A: The backend is the only trusted source for money. Line charges are summed server-side (the client can't assert
a total), the allowed amount is resolved from server-side plan config (fee schedule), and the split is computed by
the server's calculator. Nothing financial is client-supplied — the frontend only displays what the backend
computed and stored.

**Q: How would you extend the engine to support diagnosis-driven rules or prior authorization without disturbing
this design?**
A: The pure-policy + thin-service split localizes change. Diagnosis rules would extend the service's line
partition (a line requiring prior auth without an approved authorization becomes a distinct non-covered outcome)
and possibly the calculator's inputs, while the calculator stays a pure function of its inputs. Prior auth itself
is a separate aggregate (a Phase 6 concern) the engine would *read*, exactly as it reads eligibility, exclusions,
and the fee schedule today.

---

## Phase 6 — Advanced Claims (slices 1–21) — 2026-09-16

> Backfill note: Phase 6 was built across several working sessions; this section documents the whole phase
> (prior authorization, referrals, appeals, anomaly signals, manual review, reprocessing, and provider network)
> from the code, migrations (`V25`–`V35`), and `docs/PROGRESS.md`. **Phase 6 is COMPLETE (slices 1–21).**

### What we built

Phase 6 adds the "advanced claims" surface on top of the Phase 5 adjudication engine. It is deliberately **more
of the same patterns, applied seven times**, so the value is in seeing one shape generalize — not in new
machinery. The seven areas:

- **Prior authorization** (slices 1–5) — pre-approve a *planned* procedure under a plan before it's rendered.
  New aggregate + the **3rd state machine**; wired into the engine so a covered line needing auth becomes
  `AUTH_REQUIRED`; UI queue/detail/decisions + request form + a plan admin card for which procedures require auth.
- **Referrals** (slices 6–7) — request a patient be seen by a specialty for a coded reason. **4th state machine**,
  decided by **CARE_COORDINATOR** (not the reviewer) — proving the pattern generalizes across roles.
- **Appeals** (slices 8–10) — dispute a *claim's* decision. **5th state machine**; a reason on **every**
  transition; an **OVERTURNED** appeal re-runs the engine in the **same transaction** as the overturn.
- **Claim anomaly signals** (slices 11–12) — an advisory fraud/waste/abuse scan. A new **detector** shape (emits
  findings, doesn't gate a transition); two deterministic heuristics; an Anomalies card + Scan button.
- **Claim manual review** (slices 13–14) — a review *case* a coordinator/reviewer opens and a reviewer resolves.
  **6th state machine**; at most one OPEN review per claim.
- **Reprocessing** (slices 15–16) — batch re-adjudicate a plan's claims after a config change. A **job record,
  not a state machine**, with a deliberate departure from the one-transaction rule.
- **Provider network** (slices 17–21) — a plan's in-network providers + a rendering provider on the claim + the
  engine marking `OUT_OF_NETWORK` + the full UI (admin card, chip, picker, directory read). Built as five small
  slices (config → claim field → engine → UI part 1 → picker) to keep each single-concern.

### How it works

**The state-machine pattern, generalized (prior auth, referral, appeal, claim review).**
Each of these is a top-level aggregate **gated by its patient** (like `claim`): every read routes through
`PatientAccessGuard` and the list scopes via `accessiblePatientIdsIfGated`, so a provider sees only assigned
patients' rows while a broad role (coordinator/admin/reviewer) sees the tenant's as a work queue; another tenant's
row is a secure 404. Each carries **only coded/claims-domain data** (no clinical narrative), so **none are consent
field-masked**. Each has a server-allocated human number (`PA-`/`REF-`/`APL-`/`MRV-XXXXXXXX`, unique per tenant),
and each has a **pure transition-policy class** — `PriorAuthTransitions`, `ReferralTransitions`,
`AppealTransitions`, `ClaimReviewTransitions` (the 3rd–6th machines after `RequestTransitions`/`ClaimTransitions`).
The service always checks in the same order: **exists → legal move → role → reason → optimistic `expectedVersion`**,
then writes the status change **and** a `*_status_history` row in one `@Transactional` (`null → initial` on
creation). The per-domain *variations* are the teaching points:

- **Who decides** differs on purpose: prior auth & appeal → `CLAIMS_REVIEWER`; referral → `CARE_COORDINATOR`
  (routing is coordination's call); manual review resolve → `CLAIMS_REVIEWER`, cancel → the opener roles.
- **When a reason is required** differs: claims/prior-auth/referral require a reason only to reject/deny/cancel;
  **appeal and claim review require a reason on _every_ transition** (an outcome or withdrawal always needs a
  rationale).
- **Entry preconditions** differ: an appeal's *submit* loads the claim, checks it is **appealable**
  (`ADJUDICATED`/`REJECTED` → else 400) and has **no open appeal** (→ 409); a manual review enforces **at most one
  OPEN review per claim** via a partial unique index `WHERE status='OPEN'`.

**Wiring prior auth into the engine (slice 2).** `plan_prior_auth_requirement` (migration `V26`, a near-twin of
`plan_exclusion`) lists procedures a plan requires auth for. The engine gained a new `LineOutcome.AUTH_REQUIRED`
(migration `V27` extends the `adjudication_line.outcome` CHECK). In the covered branch, a line whose procedure the
plan requires auth for — with **no APPROVED `prior_authorization` whose window covers the service date**
(`existsApprovedCovering(...)` is the hook) — becomes `AUTH_REQUIRED`: allowed 0, plan pays 0, member owes the
charge, and like an exclusion it **skips cost-sharing** (no deductible/OOP consumption). Approving a covering auth
and re-adjudicating flips it to COVERED.

**Overturn → re-adjudication in one transaction (slice 10).** When an appeal on an **ADJUDICATED** claim is
OVERTURNED, `AppealService` calls `AdjudicationService.adjudicate(claimId)` in the **same transaction** as the
overturn, appending a new immutable adjudication version under current coverage/config. The two commit or roll
back together. It works with no bean cycle because `AppealService` depends on `AdjudicationService` (one
direction), and the overturning caller is a `CLAIMS_REVIEWER`/`ORG_ADMIN` — exactly the roles the engine command
requires. Honest limitation: a **REJECTED** claim's overturn records the outcome only (REJECTED is terminal on the
claim machine; re-opening it is a later slice).

**The detector shape (anomaly, slice 11).** Anomaly detection is a *new* kind of pure policy: `ClaimAnomalyDetector`
**emits a list of `DetectedSignal`s** rather than gating a transition. Two deterministic, explainable heuristics:
`DUPLICATE_CLAIM` (HIGH — another claim for the same patient shares this service date and ≥1 procedure code) and
`HIGH_TOTAL_CHARGE` (MEDIUM — the backend-computed total exceeds a configurable threshold, default $5000, a
synthetic demo heuristic, **not** a measured fraud model). `ClaimAnomalyService` loads the surrounding facts and
applies the detector; rows (`claim_anomaly_signal`, migration `V30`) are immutable and PHI-free. A **rescan
replaces** the claim's signals (delete + insert in one tx), so scanning is idempotent. Detection is **purely
additive** — it never touches claim status or the adjudication math.

**The job-record shape (reprocessing, slice 15).** Reprocessing is **not** a state machine and **not** a decision
aggregate — it's a *job*. A `reprocessing_batch` (migration `V32`, scope = one `coveragePlan`, `RPB-XXXXXXXX`,
status + counts) owns one immutable `reprocessing_item` per claim (SUCCEEDED + new version / FAILED + PHI-free
message). It **orchestrates only** — it reuses the unchanged slice-11 re-adjudication path, changing no math. The
key engineering decision is a **deliberate departure from the one-transaction rule**: `createAndRun` is
`@Transactional(propagation = NOT_SUPPORTED)`, so it runs with **no surrounding transaction** and each
`adjudicate(claimId)` (a separate bean's `@Transactional` method) commits or rolls back on its own — one claim's
failure is caught and recorded as a FAILED item, never rolling back the batch or the other claims. Scope selection
uses `AdjudicationRepository.findDistinctClaimIdsByCoveragePlan` plus a "current adjudication is on this plan"
filter.

**Provider network, the five-slice arc (17–21).** This is the clearest example of *decomposing a feature into
single-concern slices*:

1. **Config (17):** `plan_network_provider` (migration `V33`) — the PROVIDERs in a plan's network. Same
   tenant-owned plan-config shape as `plan_prior_auth_requirement`, but the participant is a **provider
   (`app_user`)**, not a catalog code. Because `app_user` isn't tenant-keyed there's **no FK-with-org** on the
   provider — the service validates it is an **active same-tenant PROVIDER** via the identity repos (else 400, no
   existence leak), exactly as the assignment tables do.
2. **Claim field (18):** `claim.rendering_provider_id` (migration `V34`) — an optional header-level provider who
   rendered the service, validated the same way. Added a **delegating constructor** so existing callers compiled
   unchanged.
3. **Engine (19):** a new `LineOutcome.OUT_OF_NETWORK` (migration `V35`). When the covering plan **defines a
   network** and the claim's rendering provider is present but not in it, every non-excluded line becomes
   `OUT_OF_NETWORK` (allowed 0, plan pays 0, member owes, no cost-sharing). It is a **claim-level** determination
   (one header rendering provider). **Precedence: exclusion > out-of-network > auth requirement > covered.** A
   **null rendering provider** or a plan with **no network rows** imposes no penalty — so it's opt-in and
   backward-compatible (existing/null-provider claims are unaffected).
4. **UI part 1 (20):** the `OUT_OF_NETWORK` line chip (error) on the adjudication card + a Network providers admin
   card on the coverage-plan detail page.
5. **Picker + directory read (21):** a new read `GET /api/v1/providers`
   (`ProviderController`/`ProviderDirectoryService`/`ProviderDto{userId, fullName}`, in the `identity` package),
   gated to the **claim-create roles** so a PATIENT/CLAIMS_REVIEWER can't enumerate staff. The New-claim form got
   an optional Rendering-provider select; the claim detail header shows "rendered by \<name\>". No migration — a
   read over existing tables.

### Key points to remember

- **The same aggregate recipe scales.** Tenant-owned + patient-gated top-level resource, server-allocated number,
  a pure transition policy, one-tx (domain + history), optimistic locking, secure 404 cross-tenant, no consent
  masking for claims-domain data. Once you've built one (the claim), each new one is mostly the *variations*.
- **A status owned by a dedicated command, not a bare status change.** `AUTH_REQUIRED`/`OUT_OF_NETWORK` are engine
  outcomes, `ADJUDICATED` is reached only by the engine — mirroring `ASSIGNED` on a request. Bare `PATCH /status`
  to an engine-owned status is refused.
- **Additive engine outcomes are additive migrations.** Each new `LineOutcome` (`AUTH_REQUIRED` V27,
  `OUT_OF_NETWORK` V35) is a migration that drops + re-adds the `adjudication_line.outcome` CHECK constraint.
- **Deny-like outcomes skip cost-sharing.** NOT_COVERED, OUT_OF_NETWORK, and AUTH_REQUIRED all set allowed 0 and
  **do not consume** deductible/OOP — so they can't accidentally advance a member's accumulators.
- **The provider-validation check now lives in 4 places** (ProviderPatientAssignmentService,
  PlanNetworkProviderService, ClaimService, ProviderDirectoryService). Deliberately duplicated to keep slices
  small; flagged in the javadocs for a future shared `ProviderValidator`/directory extract.
- **Role-gated list vs object-gated resource.** `GET /api/v1/providers` is a role-gated *list*, so a disallowed
  role correctly gets a flat **403** (not a secure 404) — a 404 is only for object/relationship existence-sensitive
  lookups.
- **Frontend: gate a role-restricted query with `enabled`.** A component reachable by many roles must not fire a
  role-gated query unconditionally — pass an `enabled` flag off a role check (see the slice-21 fix below).
- **Seeded demo data drives every queue.** `DevDataSeeder` seeds one of each (a REQUESTED prior auth, a REQUESTED
  referral, a SUBMITTED appeal on a REJECTED claim, an OPEN review), a second provider (`provider2@`/Morgan,
  unassigned) and the PPO network = {Dana}, so a Morgan-rendered PPO claim demonstrates OUT_OF_NETWORK while the
  seeded/null-provider claims stay unaffected.

### Failures and how we fixed them

- **Adding a 2nd provider broke a seeder count test.** After seeding `provider2@`, `DevDataSeederTest`'s
  `each_org_has_five_members` failed (`expected 5 but was 6`). Fix: renamed to `each_org_has_six_members` and
  updated the assertions 5 → 6 (candidate-count tests use `>= 2`/`.get(0)`, so they were unaffected).
- **Changing the `Claim` constructor would have rippled to ~10 call sites.** Adding `renderingProviderId` as a 7th
  constructor arg would have touched every existing caller (seeder + repo tests). Fix: added a **delegating 6-arg
  constructor** (`this(..., null, createdBy)`) so existing callers compiled unchanged.
- **Making `renderingProviderId` a required frontend type field rippled to fixtures (slice 21).** Faithfully
  mirroring `ClaimDto`/`ClaimSummaryDto` (the backend always sends the field) made `renderingProviderId` required
  on the `Claim`/`ClaimSummary` TS types, breaking ~8 test fixtures. Fix: added `renderingProviderId: null` to each
  fixture — the honest mirror, at the cost of mechanical churn.
- **Code-review caught a doomed request for two roles (slice 21).** `ClaimDetailPage` called `useProviders()`
  unconditionally to resolve a name, but `GET /api/v1/providers` is gated to the claim-create roles — so a PATIENT
  or CLAIMS_REVIEWER viewer fired a request the backend 403s, retried 3× (the app's default `retry`). **Not a
  security hole** (the backend correctly denied it). Fix: gave `useProviders(enabled)` an `enabled` flag +
  exported `DIRECTORY_ROLES`, and `ClaimDetailPage` passes a role check (mirroring the existing
  `useProviderCandidates` pattern), falling back to "rendered by a provider" when the directory isn't readable.
- **Tooling/process snags (not code defects):** a `CLAIMS_REVIEWER` can't submit claims (submitter roles are
  PROVIDER/CARE_COORDINATOR/ORG_ADMIN), so a live claim-flow check had to use `admin@`; a Python `urllib` live-check
  script hung with no timeout (switched to `curl -m` + a cookie jar); the in-app browser **can't navigate
  localhost** in this environment, so UI slices were verified via RTL + backend integration tests instead of a
  live click-through; and `BigDecimal` JSON strips trailing zeros (`150.00 → 150.0`), so amount assertions use
  prefix substring matches.
- **Both review subagents run clean on the final slice.** The `security-reviewer` found no exploitable issues in
  slice 21; the `code-reviewer`'s one Major finding (the 403-noise above) was fixed in a follow-up commit.

### Interview Q&A

#### 1. Beginner

**Q: What is "adjudication" and what does prior authorization add to it?**
A: Adjudication turns an accepted claim into an explainable breakdown of who pays what (allowed amount, copay,
deductible, coinsurance, plan-paid vs member). Prior authorization is a separate step *before* a service is
rendered: some procedures must be pre-approved under the patient's plan. If a claim line's procedure requires auth
and there's no approved authorization covering the service date, the engine marks that line `AUTH_REQUIRED` — the
plan pays nothing until an auth is approved and the claim is re-adjudicated.

**Q: What's a "state machine" here, and which entities have one?**
A: A small set of allowed status transitions with rules about who can make each move. `service_request`, `claim`,
`prior_authorization`, `referral`, `appeal`, and `claim_review` each have one — six total. The allowed moves live
in a pure policy class (e.g. `AppealTransitions`) so they can be unit-tested without a database, and the service
enforces them.

**Q: What does a claim's "rendering provider" mean?**
A: The provider who actually performed the service. It's an optional field on the claim. If the covering plan
defines a network and the rendering provider isn't in it, the claim adjudicates `OUT_OF_NETWORK`.

**Q: What is reprocessing?**
A: Re-running adjudication for all of a plan's claims after a configuration change (say, a corrected fee schedule
or a new exclusion). It's a batch job that produces one result row per claim — succeeded (with a new adjudication
version) or failed (with a safe message).

#### 2. Intermediate

**Q: Six aggregates share one recipe. What is it, and what actually differs between them?**
A: The recipe: a tenant-owned, patient-gated top-level resource; a server-allocated human number; a pure
transition-policy class; a one-transaction write of the domain row + a history row; optimistic locking via
`expectedVersion`; secure 404 cross-tenant; and no consent masking (claims-domain data). What differs is the
*policy*: which roles can make which move (reviewer vs coordinator), when a reason is mandatory (some require it on
every transition, some only on deny/cancel), and the entry preconditions (an appeal needs an appealable claim with
no open appeal; a review allows at most one OPEN per claim). Keeping the shape identical and varying only the
policy is what makes six aggregates cheap to build and easy to reason about.

**Q: Why is an anomaly "detector" different from a transition "policy," even though both are pure classes?**
A: A transition policy *gates* a single state change — given (from, to, roles) it answers allowed/not. A detector
*produces findings* — given the surrounding facts it emits a list of signals with severities. It doesn't change
any state; anomaly detection is purely advisory and never touches claim status or the money math. Same "pure,
DB-free, unit-testable" philosophy, different output shape.

**Q: How does an overturned appeal re-adjudicate without risking a partial update or a bean cycle?**
A: The overturn and the re-adjudication run in the **same transaction** — `AppealService` calls
`AdjudicationService.adjudicate(...)` inside its overturn transaction, so both the appeal's new status/history and
the new adjudication version commit or roll back together. There's no bean cycle because the dependency is
one-directional (appeal → adjudication), and it's authorization-safe because the caller overturning is a
`CLAIMS_REVIEWER`/`ORG_ADMIN`, which is exactly what the engine command requires.

**Q: Why gate `GET /api/v1/providers` with a 403 for the wrong role, but use a 404 for a cross-tenant claim?**
A: They're different layers. The provider directory is a role-gated *list* — a disallowed role simply may not use
the feature, so a flat 403 is correct and leaks nothing per-resource. A cross-tenant claim lookup is an
object/relationship check where a 403 would *confirm the row exists* in another tenant; that must be a secure 404
so existence isn't leaked.

**Q: The frontend already gates the picker by role — why did the backend endpoint still need a role gate, and why
did the detail page still cause a bug?**
A: The backend is the only security boundary, so the endpoint must gate regardless of the UI. The bug was
different: the *detail page* (reachable by patients and reviewers) called the directory query unconditionally just
to resolve a name, so those roles fired a request the backend correctly 403'd — wasted calls and retries, not a
breach. The fix was to make the query `enabled` only for roles that may read it, matching an existing pattern in
the codebase.

#### 3. Advanced

**Q: Reprocessing deliberately breaks the "one transaction per state change" rule. Why is that correct here?**
A: The one-tx rule protects a *single* atomic state change (domain row + history + audit + outbox). A batch is not
one state change — it's N independent ones, and we specifically *want* partial success: if claim 7 fails, claims
1–6 and 8–N must still commit. So `createAndRun` uses `@Transactional(propagation = NOT_SUPPORTED)` to run outside
any transaction, and each per-claim `adjudicate(...)` is its own transaction on a separate bean. One failure is
caught and recorded as a FAILED item. The honest limitation is that it's synchronous and a crashed batch can be
left RUNNING with no recovery — the durable/recoverable version (outbox + worker) is Phase 8.

**Q: OUT_OF_NETWORK is claim-level and opt-in. Walk through the precedence and the backward-compatibility
guarantees.**
A: Precedence is **exclusion > out-of-network > auth requirement > covered**, evaluated per line but with OON
determined once at the claim level (there's a single header rendering provider). Backward compatibility comes from
two "no penalty" rules: a **null rendering provider** can't be proven out-of-network, and a plan with **no network
rows** imposes no restriction (opt-in). Together they guarantee that every pre-existing claim and every
null-provider claim adjudicates exactly as before — the feature only changes results for a claim that both names a
rendering provider *and* runs under a plan that defines a network the provider isn't in.

**Q: Why is the network's provider an unvalidated-by-FK reference while a plan exclusion's procedure is a real FK?**
A: A plan exclusion references the **global** `medical_code` catalog, which has no tenant key, so a composite FK is
natural and enforces integrity structurally. A network provider references `app_user`, which is **not
tenant-keyed** (a user can belong to an org via `organization_membership`), so there's no `(id, organization_id)`
to FK against without leaking cross-tenant existence. Instead the service validates "active same-tenant PROVIDER"
at write time via the identity repos and returns a 400 (not a leaky 404/403) for an ineligible user. It's the same
trade-off the assignment tables already made — structural integrity where the referenced table is tenant-keyed,
service-level validation where it isn't.

**Q: Adding an engine outcome (AUTH_REQUIRED, OUT_OF_NETWORK) means a migration that rewrites a CHECK constraint.
What are the risks and how do you keep it safe?**
A: The risk is that the new enum value must be allowed by the DB CHECK before any row can be written with it, and
Hibernate is `ddl-auto: validate` so it won't touch schema — Flyway owns it. Each addition is an **additive**
migration that drops and re-adds the `adjudication_line.outcome` CHECK with the new value included; existing rows
keep their values, and because the outcomes are deny-like (allowed 0, no cost-sharing) they don't perturb any
existing accumulator math. The enum stays `EnumType.STRING` so the persisted value is the readable name, and the
calculator/partition logic is what actually decides when the new outcome applies.

**Q: If you extended anomaly detection to run automatically at submit/adjudicate, what would you have to be
careful about?**
A: Today it's a manual reviewer-triggered scan and it's idempotent (a rescan replaces the prior signals). Making
it automatic means (1) keeping it strictly additive — it must never block or alter a submit/adjudication, only
annotate; (2) deciding *when* to (re)compute so signals don't go stale after a re-adjudication or a sibling claim
arriving; (3) not letting a detector failure fail the primary command (it would need to be out-of-band, e.g. an
event handler off the outbox in Phase 8); and (4) preserving the "no measured fraud claims" honesty — these are
deterministic demo heuristics, not a validated model, and that framing must survive automation.

---

## Phase 7 — Advanced Security & Governance: Audit Trail, Break-Glass & Retention (slices 1–8) — 2026-09-16

> Backfill note: this section was written from the committed Phase 7 code plus `CLAUDE.md`/`docs/PROGRESS.md`, not
> from a live build transcript (it was captured after the fact). The design, files, and decisions below are real and
> verified against the source; per-slice build errors from the original sessions are not reconstructed here — where a
> non-obvious decision exists we record it under "Key points" and "Failures" instead.

### What we built

The **security/governance** layer that makes the platform defensible, not just functional. Four capabilities:

1. **A security audit trail** (slices 1–3) — an append-only, immutable log of security-relevant actions, made
   **tamper-evident** with a per-organization HMAC hash chain, with an auditor-facing UI to read and verify it.
2. **Break-glass emergency access** (slices 4–5) — a HIPAA "break the glass" pattern: a provider can self-grant
   time-boxed access to a patient they're not assigned to, fully audited.
3. **Access review** (slices 6–7) — oversight of standing emergency access: list every live grant and revoke one
   early, with a UI.
4. **Data retention** (slice 8) — purge long-expired operational data on a policy, while the audit trail is kept
   permanently.

The theme is **accountability**: not "can this be done" but "can we prove who did what, detect if the record was
altered, grant emergency access without abandoning oversight, and hold operational data only as long as policy
allows." The AUDITOR role — seeded since Phase 1 but unused until now — finally gets a job.

### How it works

**Slice 1 — the security audit event log.** `backend/src/main/java/com/healthcloud/audit/`.
- `AuditEvent` (V36) — tenant-owned (`organization_id`), **append-only and immutable** (no `@Version`, no
  UPDATE/DELETE path), carrying only **PHI-free** metadata: a coded `action` (`AuditAction` enum), `resource_type` /
  `resource_id`, an `outcome` (`AuditOutcome` SUCCESS/DENIED), the request `correlation_id`, and a short `detail`.
- `AuditService.record(action, resourceType, resourceId, outcome, detail)` — called from **inside a domain service's
  own `@Transactional` method**, so the audit row commits atomically with the action it records (or both roll back).
  Like `OutboxService` later, it is deliberately **not** `@Transactional`. Tenant + actor come from the backend
  `UserContext`, never the client. First wired into `AdjudicationService.adjudicate` (a money decision) and
  `ConsentDirectiveService.revoke` (a privacy decision).
- `GET /api/v1/audit-events` — a role-gated read for **AUDITOR/ORG_ADMIN** (a disallowed role is a flat **403**, not
  a secure 404 — this is a role-gated list, not an object lookup).

**Slice 2 — tamper-evidence (the per-org HMAC hash chain).**
- Each event now also carries `sequence_no`, `prev_hash`, and `entry_hash` (V37). The fingerprint is
  `entry_hash = HMAC-SHA256(orgKey, canonical(event, prev_hash))`, so each entry commits to the entire history
  before it — modify/delete/reorder/insert any row and its fingerprint no longer matches, and the break cascades to
  every later row.
- `AuditHashChain` — a **pure, DB-free policy class** (like the state machines) holding the ONE canonical
  serialization + the HMAC, shared by writer and verifier so they compute identical hashes:

```java
// canonical() fixes field order, null handling, and a UTC micro-precision timestamp so a DB round-trip reproduces it.
// entry_hash = lowercase-hex HMAC-SHA256(orgKey, canonical(... , prevHash)); prevHash chains each entry to its predecessor.
public static String computeEntryHash(byte[] orgKey, String canonical) {
    return HexFormat.of().formatHex(hmacSha256(orgKey, canonical.getBytes(StandardCharsets.UTF_8)));
}
```

- `AuditSigningKeys` derives the **per-org key** as `HMAC(masterSecret, orgId)` from a master secret held in
  configuration **outside the database it protects** (`healthcloud.audit.hmac-secret`, env-overridable dev default;
  a real deployment uses a KMS/HSM in Phase 10). So tampering with `audit_event` alone cannot forge a valid
  fingerprint — you'd also need the secret.
- Appends **serialize per org** under a `PESSIMISTIC_WRITE`-locked `audit_chain_head` (V37) that holds the chain tip
  (`last_hash` + `next_sequence`), using the insert-if-absent-then-lock pattern borrowed from `benefit_accumulator`.
- `GET /api/v1/audit-events/verify` (AUDITOR/ORG_ADMIN) recomputes the chain in sequence order — position,
  `prev_hash` link, recomputed `entry_hash` — and cross-checks the head to catch truncation, returning
  `AuditChainVerificationDto{valid, entriesChecked, brokenAtSequence, reason}`.

**Slice 3 — the audit-trail UI.** `frontend/src/audit/` — an `/audit` page (nav gated AUDITOR/ORG_ADMIN) with a
recent-events table (When · Seq · Action chip · Resource · Outcome chip · Actor · Detail · **Fingerprint**) and a
**Verify integrity** button that renders a green "Chain intact — N verified" or red "Tampering detected at
sequence X — reason" alert. The verify is modeled as a mutation (a fresh server-side recompute per click); no crypto
runs in the browser.

**Slice 4 — break-glass emergency access (backend).** `backend/src/main/java/com/healthcloud/breakglass/`.
- `BreakGlassGrant` (V38) — tenant-owned, immutable at creation; a **PROVIDER** self-declares time-boxed access to a
  patient, recording a required `reason`; `expiresAt = now + healthcloud.break-glass.grant-duration-minutes`
  (default 60).
- `BreakGlassService.create` is PROVIDER-gated and loads the patient **directly by tenant** — deliberately **NOT**
  through `PatientAccessGuard`, because the guard would 404 the very patient break-glass exists to reach (a
  cross-tenant/unknown patient is still a secure 404 — break-glass never crosses tenants). The grant row + a
  `BREAK_GLASS_INVOKED` audit event are written in **one transaction**; the audit detail is PHI-free (grant id +
  expiry, never the free-text reason).
- The key integration: **`PatientAccessGuard` consults live grants.** `requireAccessibleInTenant` allows a
  provider-gated caller with no assignment if a live grant exists, and `accessiblePatientIdsIfGated` unions assigned
  + break-glass patient ids. Because *every* patient-scoped read routes through this one guard, break-glass thereby
  reaches the patient's **whole** record (requests/consent/documents/claims). Only the relationship layer is
  overridden; tenant isolation and consent/field-masking are untouched.

```java
// PatientAccessGuard: a provider with no assignment is still allowed if a live emergency grant exists.
if (isProviderGated(caller) && !isAssigned(...)
        && !hasActiveBreakGlass(organizationId, caller.userId(), patientId)) {
    throw new NotFoundException(); // secure 404
}
```

**Slice 5 — break-glass UI.** `frontend/src/breakglass/` — because a provider breaks glass to reach a patient they
*can't* see, the entry point is the **denied state**: `PatientDetailPage`'s error branch renders a `BreakGlassPanel`
(reason form + "Break glass") instead of the generic error when the patient query is a **404** and the caller is a
**PROVIDER**. On success it invalidates the patient query so the page reloads with access. A `/break-glass` page
lists the caller's live grants. Nav "Emergency access" gated to PROVIDER.

**Slice 6 — access review of break-glass (backend).** A grant gains **early revocation** — `revoked_at`/`revoked_by`
(V39); "live" = `expires_at > now AND revoked_at IS NULL`, and the guard + every active-grant query filter on both,
so a revocation cuts access off at once. `GET /api/v1/break-glass/all` (AUDITOR/ORG_ADMIN) lists every live grant
with the provider name resolved; `POST /api/v1/break-glass/{id}/revoke` (**ORG_ADMIN only** — an auditor is
read-only) ends a grant early (409 if not live, secure 404 cross-tenant) and writes a `BREAK_GLASS_REVOKED` audit
event in one transaction.

**Slice 7 — access-review UI.** `frontend/src/breakglass/AccessReviewPage.tsx` — an `/access-review` page listing
every live grant (provider · patient link · reason · window) with a **Revoke** button (inline Confirm/Cancel, no
`window.confirm`) shown only to ORG_ADMIN; an auditor sees it read-only. Nav gated AUDITOR/ORG_ADMIN.

**Slice 8 — data retention.** `backend/src/main/java/com/healthcloud/retention/`.
- `RetentionService.runBreakGlassPurge()` (ORG_ADMIN, tenant-scoped; `POST /api/v1/retention/break-glass/run`)
  deletes the caller's-tenant `break_glass_grant` rows that expired more than `healthcloud.retention.break-glass-days`
  (default 90) ago — removing the sensitive free-text reason once a grant is long expired — and records a
  `RETENTION_PURGED` audit event in the **same transaction**.

```java
OffsetDateTime cutoff = OffsetDateTime.now().minusDays(breakGlassRetentionDays);
long purged = grants.deleteByOrganizationIdAndExpiresAtBefore(organizationId, cutoff);
audit.record(AuditAction.RETENTION_PURGED, AuditService.RESOURCE_BREAK_GLASS_GRANT, null,
        AuditOutcome.SUCCESS, "Retention purge: removed " + purged + " ... older than " + breakGlassRetentionDays + " days");
```

- The audit trail is **never** purged (deleting a row would break the hash chain by design); the PHI-free
  `BREAK_GLASS_*` events survive to prove the emergency access happened. Retention added **no migration** — it only
  deletes existing operational rows.

### Key points to remember

- **`AuditService.record` is not `@Transactional`** — it joins the caller's transaction so the audit row commits
  with the domain change. This is the same "join the caller's tx" trick reused by `OutboxService` in Phase 8.
- **Tamper-evidence needs a secret held outside the protected data.** The per-org key derives from a master secret in
  config/KMS, never in the DB. Otherwise an attacker who can edit `audit_event` could also recompute valid hashes.
  The chain is HMAC (keyed), not a bare hash, for exactly this reason.
- **Each entry chains to the previous** (`prev_hash` folded into the canonical form), so a single altered field
  cascades — the verifier detects the earliest break. The head cross-check catches truncation of the newest rows.
- **Per-org append serialization** uses a `PESSIMISTIC_WRITE` row lock on `audit_chain_head` (insert-if-absent then
  lock) — the `benefit_accumulator` pattern — so concurrent audited actions in one org can't produce two events with
  the same `sequence_no` or a forked chain.
- **Canonical serialization must be deterministic across a DB round-trip.** The timestamp is stored/fingerprinted as
  a micros-truncated UTC instant, field order and null handling are fixed, and the separator can't occur in the
  coded fields — so the verifier reproduces the writer's bytes exactly.
- **Break-glass overrides ONLY the relationship layer**, and only because `PatientAccessGuard` is the single choke
  point every patient-scoped read passes through. Tenant isolation and consent/field-masking still apply. The guard
  depends on the grant *repository* (not the services), so there's no bean cycle.
- **Role-gated list vs object lookup.** A disallowed role on `GET /audit-events` or `/break-glass/all` is a flat
  **403** (the resource class isn't hidden); an unreachable *specific* patient/claim stays a secure **404**. Know
  which is which.
- **Revocability changed the "live" definition.** After slice 6, "live" means `expires_at > now AND revoked_at IS
  NULL` everywhere — the guard's checks and every active-grant query. Miss one and a revoked grant would still grant
  access.
- **Audit is permanent; operational data is on a retention policy.** Never purge audit rows (it breaks the chain).
  Retention removes the sensitive break-glass `reason` once long-expired, but the PHI-free audit events proving the
  access happened remain.
- **`RESOURCE_*` are coded strings, not an enum.** A new audited resource type doesn't need a schema/enum change; the
  `AuditAction` enum names the event, the resource type is a short constant.

### Failures and how we fixed them

Per the backfill note above, the original per-slice build errors from these sessions aren't reconstructed here. The
non-obvious decisions worth recording (each a trap we designed around, verified in the committed code):

- **Never store the audit key in the audited database.** Early instinct is a per-org key column; that would let
  anyone who can edit `audit_event` forge fingerprints. The key derives from an out-of-DB master secret instead.
- **Don't validate break-glass through `PatientAccessGuard`.** Doing so would 404 the exact patient the feature
  exists to reach. `BreakGlassService.create` loads the patient directly by `(org, id)` — still a secure 404
  cross-tenant, so no existence leak.
- **When revocation was added, "live" had to change in every place at once** (guard + all queries), or a revoked
  grant would keep working. This is why `isLive()`/the `revoked_at IS NULL` predicate live in shared spots.
- **Retention must not touch the audit trail.** Purging audit rows would break the hash chain — so retention is
  scoped strictly to operational `break_glass_grant` rows, and the purge is itself audited.

### Interview Q&A

#### 1. Beginner

**Q: What is a security audit trail and what makes this one trustworthy?**
A: An append-only log of security-relevant actions (who did what, to which resource, with what outcome). This one is
immutable (no update/delete path) and **tamper-evident** — each entry is fingerprinted with an HMAC that chains to
the previous entry, so any later change is detectable.

**Q: What is "break the glass"?**
A: A HIPAA pattern for emergencies: a clinician who normally couldn't access a patient's record can override the
access control to reach it *now*, on the condition that the override is recorded and reviewable. Here a PROVIDER
self-grants time-boxed access with a required reason, and it's audited.

**Q: Why is break-glass not approval-gated?**
A: Because it's for emergencies — waiting for an approval defeats the purpose. It's made safe *after the fact*: every
invocation is audited, grants expire automatically, and an admin can review and revoke them.

**Q: Why keep the audit trail forever but purge break-glass grants?**
A: The audit trail is the accountability record — deleting from it would break the tamper-evidence chain. A
break-glass grant is operational data that holds a sensitive free-text reason; once it's long expired and reviewed,
policy says remove it. The audit events proving the access happened stay.

#### 2. Intermediate

**Q: Why HMAC instead of a plain SHA-256 hash for the chain?**
A: A plain hash is unkeyed — anyone who edits a row could recompute a valid hash for the whole chain. HMAC is keyed;
without the per-org secret you can't produce a valid fingerprint. The secret lives outside the database it protects,
so compromising the DB isn't enough to forge the log.

**Q: How does the chain detect deletion, reordering, or insertion — not just field edits?**
A: Each entry includes the previous entry's hash and a monotonic `sequence_no` in what it signs. The verifier walks
in sequence order checking position, the `prev_hash` link, and the recomputed `entry_hash`; a missing/reordered/
inserted row breaks the sequence or the link. A separate head cross-check (stored tip vs recomputed tip) catches
truncation of the most recent rows.

**Q: Break-glass has to reach a patient the access guard would deny. How is that done without opening a hole?**
A: `BreakGlassService.create` loads the patient directly by `(organizationId, id)` rather than through
`PatientAccessGuard`. Tenant scoping still applies (cross-tenant is a secure 404), so nothing leaks across tenants;
only the relationship layer is bypassed, and only for the caller who created a live grant. The guard then honors that
grant for subsequent reads.

**Q: Why does `AuditService.record` deliberately avoid `@Transactional`?**
A: So it joins the caller's transaction. The audit event must commit atomically with the action it records — if it
opened its own transaction, the log and the action could diverge on a failure. The same design is reused by the
Phase 8 outbox writer.

#### 3. Advanced

**Q: Two audited actions happen concurrently in the same org. How do you prevent a forked chain or duplicate
sequence numbers?**
A: Appends serialize per org via a `PESSIMISTIC_WRITE` lock on the org's `audit_chain_head` row (insert-if-absent,
then lock), so only one writer advances the tip at a time within a transaction. The next writer blocks until the
first commits, then reads the new tip. It's the same row-lock pattern used for financial accumulators.

**Q: What are the limits of this tamper-evidence, and how would production harden it?**
A: It detects tampering by *anyone who lacks the key*; a holder of the master secret (or someone who can rewrite both
the rows and re-derive keys) could forge it. Production would keep the secret in a KMS/HSM (Phase 10), rotate keys,
optionally anchor periodic chain heads to an external notarization/WORM store, and ship audit events off-box so a
DB-level actor can't both edit and re-sign. The canonical form must also stay frozen — changing serialization would
invalidate historical verification.

**Q: A revoked grant must stop working immediately. How is "immediately" actually guaranteed?**
A: There's no cached authorization — `PatientAccessGuard` checks live grants on *every* patient-scoped read, and
"live" is `expires_at > now AND revoked_at IS NULL` evaluated at check time. Revocation sets `revoked_at` in a
committed transaction, so the very next read excludes it. The cost is a per-read grant check; the benefit is no stale
access window.

**Q: Retention deletes rows — how do you keep that from being an accountability hole?**
A: Retention is scoped to *operational* data only (`break_glass_grant`), never the audit trail, and only to rows past
a policy window (cutoff strictly in the past, so live/recent grants are untouched). The delete itself is audited as
`RETENTION_PURGED` in the same transaction, with a PHI-free detail (count + window). So the sensitive reason is gone,
but the permanent audit record shows that emergency access occurred and that a purge ran.

---

## Phase 8 — Event-Driven Architecture: Transactional Outbox + Kafka (slices 1–7) — 2026-09-17

### What we built

An **event-driven pipeline** that lets one domain action (a claim being adjudicated) trigger loosely-coupled
downstream work **reliably**, without the classic "dual-write" bug. The full chain, end to end:

```
adjudicate a claim
  → write an outbox_event row IN THE SAME DB transaction   (slice 1)
  → a relay publishes committed rows to Kafka after commit  (slice 2)
  → an idempotent consumer reacts, building a notification  (slice 3)
  → failures retry, then go to a dead-letter topic (DLT)    (slice 4)
  → a drainer copies DLT records into a queryable table     (slice 5)
  → an admin replays a fixed record back onto the topic     (slice 6)
  → a browser page to inspect + replay dead letters         (slice 7)
```

The single problem the whole phase exists to solve: **you cannot atomically write to your database AND publish to
Kafka.** They are two different systems with two different transactions. If you write the DB row then publish, a
crash in between loses the event; if you publish then write, a crash loses the DB change (or double-publishes). The
**transactional outbox** pattern removes the dual write: you only ever write to your own database (the domain row +
an `outbox_event` row, in one transaction), and a separate **relay** turns committed outbox rows into Kafka messages
afterwards. Everything else in the phase (idempotency, retry, DLT, drain, replay) is the machinery that makes that
"publish afterwards, at least once" guarantee safe to build on.

This was our first messaging/asynchronous work — every prior phase was synchronous request/response.

### How it works

**Slice 1 — the transactional-outbox foundation (write side).**
`backend/src/main/java/com/healthcloud/outbox/`:
- `OutboxEvent` — an immutable entity: `organization_id`, `aggregate_type`, `aggregate_id`, `event_type`,
  `payload` (JSON `TEXT`), `occurred_at`, `correlation_id`, and a nullable `published_at` (null = not yet
  published). `markPublished()` stamps it.
- `V40__outbox_event.sql` — the table plus a **partial index** `ix_outbox_unpublished ON (occurred_at) WHERE
  published_at IS NULL`. The relay only ever queries unpublished rows, so a partial index keeps that scan cheap even
  as the published rows pile up.
- `OutboxService.record(aggregateType, aggregateId, eventType, payload)` — the writer. Crucially it is **NOT
  `@Transactional`**: it is called from *inside* a domain service's own transaction so the outbox row joins that
  transaction and commits atomically with the domain change. This is the exact same trick as `AuditService.record`.

```java
// OutboxService — joins the caller's transaction; must not open its own.
public void record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
    UUID organizationId = userContext.requireOrganizationId();
    String json = objectMapper.writeValueAsString(payload); // Jackson 3: unchecked JacksonException
    events.save(new OutboxEvent(organizationId, aggregateType, aggregateId, eventType, json,
            CorrelationId.current()));
}
```

- First emitter: `AdjudicationService.adjudicate` now calls `outbox.record(AGGREGATE_CLAIM, claim.getId(),
  "claim.adjudicated", ClaimAdjudicatedEvent.from(claim, savedHeader))` right next to the existing
  `audit.record(...)`. `ClaimAdjudicatedEvent` is a **PHI-free, minimum-necessary** record: claim id/number,
  adjudication version, outcome, and the money split — deliberately **no** patient identifier or clinical narrative
  (rule 5). A consumer that needs more must re-read the claim through the authorized API.

**Slice 2 — the outbox relay + Kafka (publish side).**
- `docker-compose.yml` gained a `kafka` service (`apache/kafka:3.9.0`, **KRaft** mode — no ZooKeeper — on port 9092).
- `OutboxRelay.publishPending()` (`@Transactional`) reads one bounded page of pending rows oldest-first
  (`findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0, batchSize))`), sends each to Kafka synchronously,
  and stamps `published_at`. The topic is the `event_type`, the key is the `aggregate_id` (so all events for one
  claim keep partition order), the value is the JSON payload, and event metadata rides in headers
  (`eventId`, `eventType`, `aggregateType`, `organizationId`, `correlationId`).
- `OutboxRelayScheduler` — a `@Scheduled(fixedDelayString = "${healthcloud.outbox.relay.poll-interval-ms}")` poller,
  `@ConditionalOnProperty("healthcloud.outbox.relay.enabled", matchIfMissing = true)`. `@EnableScheduling` was added
  to `HealthcloudApplication`.
- **Publish happens after commit**, and a send failure stops the batch (the row stays pending and is retried next
  poll). Delivery is therefore **at-least-once**, and the relay assumes a **single instance** (multiple instances
  would need `SELECT … FOR UPDATE SKIP LOCKED` to avoid double-publishing the same row).

**Slice 3 — the idempotent consumer (read side).**
- `ClaimAdjudicatedConsumer` — a `@KafkaListener(topics = "claim.adjudicated", groupId =
  "claim-adjudication-notifier", autoStartup = "${healthcloud.kafka.consumers.enabled:true}")`. It builds a PHI-free
  `claim_adjudication_notification` (V41) **purely from the event** (payload + headers) and never re-reads the claim
  — real loose coupling.
- **Idempotency** (because delivery is at-least-once): it skips an event it has already recorded
  (`existsByEventId`), and `UNIQUE(event_id)` is the backstop — a `DataIntegrityViolationException` from a
  redelivery race is caught and treated as "already processed".

**Slice 4 — retry/backoff + a dead-letter topic.**
- `KafkaConsumerErrorConfig` exposes a single `DefaultErrorHandler` bean (Boot auto-applies it to the listener
  factory):

```java
DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
        (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1)); // -1 = broker picks the partition
FixedBackOff backOff = new FixedBackOff(backoffMs, Math.max(0, maxAttempts - 1)); // 2nd arg = RETRIES, not attempts
DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class); // structural → straight to DLT
```

- A transient failure retries a bounded number of times, then the record is republished to `<topic>.DLT`
  (`claim.adjudicated.DLT`). **Structural failures** (a bad/missing header → `IllegalArgumentException`, a malformed
  payload → `JacksonException`) can never succeed on retry, so they bypass retries and go straight to the DLT — a
  poison record never blocks the partition.

**Slice 5 — dead-letter drain + inspection.**
- `DeadLetterDrainer` — a `@KafkaListener` on `claim.adjudicated.DLT` that copies each failed record into a
  `dead_letter_event` table (V42): original topic/key/payload, the `eventId`/`organizationId` app headers, and
  Spring's `kafka_dlt-*` metadata (`KafkaHeaders.DLT_*`: original topic, exception class, exception message). This
  turns "what's stuck on a Kafka topic" into an **ordinary DB read**. Idempotent via
  `UNIQUE(dlt_topic, dlt_partition, dlt_offset)`.
- `DeadLetterService.listForTenant()` + `GET /api/v1/dead-letter-events` — **ORG_ADMIN**, tenant-scoped (a role-gated
  list → a disallowed role is a flat 403). `organization_id` is nullable, so a fully-unattributable poison record is
  not listable by a tenant admin.

**Slice 6 — DLT replay.**
- `DeadLetterReplayService.replay(id)` re-drives a stored record back onto its **source topic** once the bug is
  fixed. It runs `@Transactional(propagation = NOT_SUPPORTED)` — **no ambient DB transaction around the Kafka send**
  — then delegates the DB write to a *separate* bean so `@Transactional` is actually honoured:

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public DeadLetterEventDto replay(UUID id) {
    userContext.requireAnyRole(REPLAY_ROLES);                 // ORG_ADMIN
    UUID org = userContext.requireOrganizationId();
    DeadLetterEvent e = deadLetters.findByIdAndOrganizationId(id, org).orElseThrow(NotFoundException::new); // 404
    if (e.isReplayed()) throw new ConflictException("...");   // 409
    publishToSourceTopic(e);                                  // publish FIRST
    return deadLetterService.finalizeReplay(id);              // THEN mark + audit, atomically
}
```

- `DeadLetterService.finalizeReplay` (`@Transactional`) stamps `replayed_at`/`replayed_by` (V43, a one-way
  lifecycle stamp — no `@Version`, like break-glass revoke) **and** writes a `DEAD_LETTER_REPLAYED` audit event in
  one transaction.
- **Publish-first, then mark**: if the process dies after the send but before the mark, a retry just re-publishes —
  and the idempotent consumer dedupes the redelivery. That same idempotency makes a rare concurrent double-click
  harmless.

**Slice 7 — the dead-letter / replay UI (frontend).**
- `frontend/src/deadletter/` — `DeadLetterEventsPage` (`/dead-letters`) lists the tenant's dead letters (When ·
  Source topic · Event ID · Failure · Payload · Status) via `useDeadLetterEvents`, with a **Replay** button (inline
  Confirm/Cancel, mirroring the Access-review Revoke) that calls `useReplayDeadLetter` and invalidates the list so
  the row flips to a green **Replayed** chip. Nav gated to **ORG_ADMIN only** (matching the backend list gate —
  narrower than the AUDITOR+ORG_ADMIN audit nav, so an auditor never lands on a 403 page).

**Test wiring shared by the phase.** `KafkaTestcontainersConfiguration` provides a real broker
(`@ServiceConnection ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0")`), imported **only** by the Kafka tests
so the rest of the suite stays broker-free. `src/test/resources/application-local.yml` disables the relay scheduler
and the `@KafkaListener` consumers across the suite; a Kafka test either drives the path directly
(`relay.publishPending()`, publish via `KafkaTemplate`) or re-enables consumers with
`@SpringBootTest(properties = "healthcloud.kafka.consumers.enabled=true")`. The full suite ends green at **430
backend tests** and **151 frontend tests**.

### Key points to remember

- **The dual-write problem is the reason for everything.** DB and Kafka can't share a transaction. The outbox means
  you only write to the DB; publishing is a separate, retryable step against committed rows.
- **`OutboxService` and `AuditService` are deliberately NOT `@Transactional`.** They must join the caller's
  transaction so the extra row commits atomically with the domain change. Annotating them would open a nested/new
  transaction and break atomicity. This is the physical realization of the §31.6 "domain change + status history +
  audit event + outbox event, all in one transaction" pattern.
- **At-least-once delivery ⇒ consumers must be idempotent.** The dedupe key is the outbox `event_id`, enforced by a
  `UNIQUE` constraint (pre-check + caught `DataIntegrityViolationException` backstop). Never assume exactly-once.
- **topic = event_type, key = aggregate_id.** Keying by aggregate keeps per-aggregate ordering within a partition.
- **Publish-then-mark (relay) and publish-then-mark (replay)** both choose "risk a duplicate, never lose an event".
  Losing is worse than duplicating precisely *because* consumers are idempotent.
- **Kafka send cannot be inside a DB transaction.** The relay's `@Transactional` only wraps the `published_at`
  stamps; replay uses `NOT_SUPPORTED` and does the send with no ambient tx, then a *separate bean* does the
  transactional DB write. (Self-invoking a `@Transactional` method on the same bean would NOT start a transaction —
  Spring proxies are bypassed on self-calls — which is exactly why replay delegates to `DeadLetterService`.)
- **Structural vs transient failures.** Bad header/payload = non-retryable ⇒ straight to DLT. Everything else gets a
  bounded `FixedBackOff` then DLT. `FixedBackOff`'s second argument is the number of **retries**, so total attempts
  = retries + 1.
- **Boot 4 modularized auto-config.** The raw `spring-kafka` library brings **no** `KafkaTemplate` bean; you need the
  starter `spring-boot-starter-kafka`. And Boot's auto-configured `KafkaTemplate<?,?>` does **not** satisfy a
  `KafkaTemplate<String,String>` injection point (wildcard vs specific generics) — inject it **raw**
  (`@SuppressWarnings("rawtypes")`), as `OutboxRelay` and `DeadLetterReplayService` do.
- **Testcontainers Kafka.** Testcontainers 2.0.x's `org.testcontainers.kafka.KafkaContainer` (apache/kafka)
  mis-computes `advertised.listeners` on this host; use `ConfluentKafkaContainer` (`confluentinc/cp-kafka`) instead.
  Read the broker address from `container.getBootstrapServers()`, **not** the `spring.kafka.bootstrap-servers`
  property — `@ServiceConnection` wires a `ConnectionDetails` bean and leaves that property at its default.
- **PHI-free payloads (rule 5).** Event payloads carry coded/claims data only — no clinical narrative, no patient
  identifiers beyond the aggregate id. Same discipline as audit details and dead-letter rows.
- **No broker connection at startup.** There are no `@KafkaListener`/`KafkaAdmin` topic beans forcing a connection;
  only the relay (when enabled) and the consumers (when enabled) connect. That is what lets the broker-free tests
  run without Kafka.
- **`replayed_at`/`replayed_by` is a one-way stamp with no `@Version`** (the break-glass-revoke precedent); a
  double-replay is guarded by a 409 pre-check and made harmless by consumer idempotency.

### Failures and how we fixed them

1. **Testcontainers Kafka artifact — version missing.** `org.testcontainers:kafka` failed to resolve. TC 2.0.x uses
   the `testcontainers-` prefix → the correct artifact is `org.testcontainers:testcontainers-kafka`.
2. **No `KafkaTemplate` bean — context failed to load for all tests.** Symptom: "No qualifying bean of type
   `KafkaTemplate<String,String>`". Root cause: Boot 4 modular auto-config — the raw `spring-kafka` library brings no
   Kafka auto-configuration. Fix: use `spring-boot-starter-kafka`; and inject the template **raw**, because Boot's
   `KafkaTemplate<?,?>` bean does not match a `KafkaTemplate<String,String>` injection point.
3. **apache/kafka container exited (code 1).** `org.testcontainers.kafka.KafkaContainer` with `apache/kafka:3.9.0`
   errored: "advertised.listeners cannot use the nonroutable meta-address 0.0.0.0" (the image runs fine
   standalone). Fix: switch the test broker to `ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0")`.
4. **Wrong bootstrap address in tests.** `@Value("${spring.kafka.bootstrap-servers}")` resolved to the *default*,
   not the container, because `@ServiceConnection` wires a `ConnectionDetails` bean rather than setting that
   property. Fix: autowire the container and use `container.getBootstrapServers()`.
5. **DLT poison message never arrived (slice 4 test timed out).** Two fixes: an explicit
   `DeadLetterPublishingRecoverer` destination resolver (`record.topic() + ".DLT"`, partition `-1`), and the DLT
   test consumer set `ConsumerConfig.METADATA_MAX_AGE_CONFIG = 1000` so it re-discovers the newly-created DLT topic
   quickly.
6. **Slice-5 assertion mismatch.** We asserted the dead-letter body contained "jackson", but the recorded
   `exceptionType` was Spring's wrapper `ListenerExecutionFailedException` (the Jackson parse detail is in the
   `exceptionMessage`). Fix: assert `"ListenerExecutionFailedException"` and `"Unrecognized token"`.
7. **Slices 6–7 built clean (no failing builds).** The two pitfalls we designed around up-front: (a) the
   self-invocation transaction trap — replay delegates the `@Transactional` DB write to a separate bean rather than
   calling a `@Transactional` method on itself; (b) the actor id accessor — `UserContext` exposes `userId()` (not
   `id()`), confirmed before wiring `finalizeReplay`.

### Interview Q&A

#### 1. Beginner

**Q: What is the dual-write problem?**
A: When a single operation needs to update two systems that don't share a transaction — here a PostgreSQL row and a
Kafka message. If you do them as two separate steps, any crash in between leaves them inconsistent: the DB commits
but the event is never published, or the event is published but the DB rolls back. There's no atomic "write to both".

**Q: What is the transactional outbox pattern?**
A: Instead of publishing to Kafka directly, the domain transaction writes an extra `outbox_event` row in the *same*
database transaction as the domain change. A separate process (the relay) later reads committed outbox rows and
publishes them to Kafka. You only ever write to one system transactionally, so there's no dual write.

**Q: Why is `OutboxService.record` not `@Transactional`?**
A: Because it must run *inside* the caller's transaction. If it opened its own transaction, the outbox row could
commit or roll back independently of the domain change, reintroducing the very inconsistency we're avoiding. It's the
same design as `AuditService.record`.

**Q: What does the relay do?**
A: A scheduled poller (`OutboxRelay`) selects unpublished outbox rows oldest-first, publishes each to Kafka (topic =
event type, key = aggregate id, value = JSON payload), and stamps `published_at`. It runs after the domain
transaction has committed.

**Q: What is a dead-letter topic (DLT)?**
A: A separate Kafka topic where messages that repeatedly fail processing are parked, so a single bad ("poison")
message doesn't block the partition forever. Here it's `claim.adjudicated.DLT`.

#### 2. Intermediate

**Q: The relay publishes then stamps `published_at`. What if it crashes in between?**
A: The row stays unpublished, so the next poll re-publishes it — the event is delivered again. That's **at-least-once**
delivery. We accept duplicates because the alternative (mark-then-publish) risks *losing* an event, which is worse,
and because consumers are idempotent.

**Q: How do you make the consumer idempotent?**
A: Every event carries the outbox `event_id` in a header. The consumer skips an event it has already recorded
(`existsByEventId`) and relies on a `UNIQUE(event_id)` constraint as the backstop — a redelivery race throws
`DataIntegrityViolationException`, which we catch and treat as "already processed".

**Q: Why key Kafka messages by `aggregate_id`?**
A: Kafka only guarantees ordering within a partition, and the partition is chosen by the key. Keying by the
aggregate (the claim id) keeps all events for one claim in order relative to each other, while still spreading
different claims across partitions for throughput.

**Q: How does retry/DLT distinguish transient from structural failures?**
A: The `DefaultErrorHandler` retries with a `FixedBackOff`, then routes to the DLT. But
`addNotRetryableExceptions(IllegalArgumentException, JacksonException)` marks structural failures (bad header,
malformed payload) as non-retryable — they can never succeed on retry, so they go straight to the DLT instead of
wasting the retry budget and blocking the partition.

**Q: Why does replay publish first and then mark the record replayed?**
A: If we marked first and the publish failed, the record would look replayed but nothing was re-driven — a silent
loss. Publishing first means a failure leaves the record un-stamped so the admin can retry; a crash after the send
just re-publishes, which the idempotent consumer dedupes. It's the same at-least-once philosophy as the relay.

**Q: Why is the DLT drained into a database table?**
A: Inspecting or replaying messages by consuming a Kafka topic over REST is awkward and stateful. Draining the DLT
into `dead_letter_event` turns "what failed" into an ordinary tenant/role-gated DB read, and gives replay a stable
record to act on and stamp.

#### 3. Advanced

**Q: Your relay assumes a single instance. What breaks if you run two, and how would you fix it?**
A: Two relays would both select the same pending rows and double-publish them. The fix is `SELECT … FOR UPDATE SKIP
LOCKED` on the batch so each instance claims a disjoint set of rows, or a leader-election/sharded approach. We
documented this as a known limitation rather than building it for the MVP.

**Q: Replay runs `@Transactional(NOT_SUPPORTED)` and delegates the DB write to another bean. Why not just annotate a
private method?**
A: Spring's transaction management is proxy-based; a self-invocation (calling another method on `this`) bypasses the
proxy, so a `@Transactional` annotation on a same-bean method would be ignored and the "mark + audit" wouldn't be
atomic. Delegating to a *separate* bean (`DeadLetterService.finalizeReplay`) goes through a proxy, so the transaction
actually starts. `NOT_SUPPORTED` is used on the outer method because a Kafka send must not sit inside a DB
transaction (the DB tx would be held open across a network call, and the send can't be rolled back anyway).

**Q: The outbox guarantees at-least-once. Could you achieve exactly-once?**
A: Not end-to-end without more machinery. You can get *effectively*-once by pairing at-least-once delivery with
idempotent consumers keyed on the event id — which is what we did. True exactly-once would need Kafka transactions
(the transactional producer + read-process-write) and consumer-side transactional state, which is far heavier and
still degrades to effectively-once at the edges. Idempotency is the pragmatic answer.

**Q: How do you keep the test suite fast when the app needs Kafka?**
A: The broker is expensive to start, so `KafkaTestcontainersConfiguration` (a `ConfluentKafkaContainer` with
`@ServiceConnection`) is imported *only* by the handful of Kafka tests. The rest of the suite disables the relay
scheduler and the `@KafkaListener` consumers via `src/test/resources/application-local.yml`, and the app makes no
broker connection at startup, so those tests never touch Kafka. Kafka tests re-enable consumers per-class via
`@SpringBootTest(properties = ...)` or drive the relay/consumer code directly.

**Q: A payload schema will evolve. How is that handled here, and what would you add for production?**
A: Today the consumer deserializes `ClaimAdjudicatedEvent` with Jackson and a malformed/unknown payload is a
non-retryable `JacksonException` → DLT (fail safe, no partition block). For production you'd add a schema registry
(e.g. Avro/Protobuf with compatibility rules), version the event type, and make consumers tolerant of unknown
fields. We also deliberately kept payloads minimum-necessary and PHI-free, which limits the blast radius of a schema
or leak problem.

**Q: What ordering guarantees does a downstream consumer actually get, and where could it be surprised?**
A: Ordering holds only per partition, i.e. per aggregate id (per claim). Across claims there is no ordering. A
consumer is also re-delivered on at-least-once, so it can see the same event twice and (after a DLT replay) out of
its original time order. That's why the notification consumer is idempotent and builds state purely from the event
rather than assuming "this is the first/only time I've seen this".

---

## Phase 9 — Search, Reporting & Accessibility (slices 1–10) — 2026-09-17

### What we built

The **usability** layer over everything the earlier phases built: instead of returning whole unbounded lists and
leaving the browser to cope, every work queue is now **server-side paginated, filterable, sortable, and
searchable**, the patient list can be **exported to CSV without leaking masked fields**, and the whole SPA got a
**WCAG 2.2 AA-aligned accessibility pass**. Three sub-themes across ten slices:

```
slices 1–5  pagination + filtering + sorting across ALL EIGHT work queues (backend + UI)
slices 6–8  free-text search across all eight queues
slice  9    CSV export with masking (the reporting piece)
slice  10   accessibility pass (skip link, landmarks, headings, an axe test gate)
```

The eight work queues: claims, prior-authorizations, referrals, appeals, claim-reviews, reprocessing, audit,
dead-letters. The unifying idea of the whole phase is **do the work in the right place**: push paging/filtering/
search into SQL (not in-memory over a fetched list), and make the export and the accessibility structure reuse the
*same* trusted read paths and components rather than re-implementing them.

### How it works

**Slices 1–5 — server-side pagination + filtering + sorting.**
A new reusable package `backend/src/main/java/com/healthcloud/common/`:
- `PageResponse<T>` — a stable, framework-agnostic page envelope we own:
  `{ content, page, size, totalElements, totalPages, first, last }`, built via `PageResponse.of(Page<E>, mapper)` /
  `PageResponse.empty(pageable)`. We deliberately do **not** serialize Spring Data's `PageImpl` (its JSON shape is
  unstable across versions and leaks internals).
- `PageRequests.toPageable(page, size, sort, allowedSortFields, defaultSort)` — a **pure** helper that clamps `size`
  to 1..100 and `page` to ≥ 0, and **allowlists the sort field**. An unknown field or bad direction becomes a clean
  `400 VALIDATION_FAILED`, never a `PropertyReferenceException` 500 or an arbitrary-column sort.

Each queue's controller takes `page`/`size`/`sort` (+ its own filter, e.g. `status`, `action`); the repository
pushes filtering into SQL with `@Query` finders — `searchAll` for broad roles and, for a patient-gated queue,
`searchForPatients(org, patientIds, filter, pageable)` (and `searchForClaim(...)` for claim-scoped queues):

```java
@Query("""
    select c from Claim c
     where c.organizationId = :org
       and (:status is null or c.status = :status)
    """)
Page<Claim> searchAll(UUID org, ClaimStatus status, Pageable pageable);
```

Crucially, **authorization is unchanged by paging**: the §21 patient gate / role gate / tenant scope run exactly as
before, and a gated caller with an empty accessible-id set short-circuits to `PageResponse.empty(pageable)` (no DB
round trip). Rollout order: slice 1 the foundation + claims (SQL), slices 2–3 the claims + prior-auth UIs, slice 4
referrals/appeals/claim-reviews, slice 5 reprocessing/audit/dead-letters (audit also gained a real `action` filter
+ paging, replacing an old 200-row cap). The claims list kept a one-line `api.listClaims()` **compatibility shim**
(fetch one large page, return `.content`) because non-queue callers (name resolution, `<select>` options) still
need the whole array.

Frontend: each queue page holds `page`/`size`/`sort`(+filter) in `useState`, calls `api.listXxxPage(params)`
returning the envelope, renders `data.content` plus MUI `<TablePagination>` and `<TableSortLabel>` on the
server-sortable columns only. The query key carries the params (so a change refetches) but stays prefixed with the
base key (so a create/decision invalidation still catches it); `placeholderData: keepPreviousData` avoids a
loading-flash on page change. Any sort/size/filter change resets to page 0.

**Slices 6–8 — free-text search.**
A second pure helper, `common/SearchTerms.likeContains(raw)`: a blank/whitespace box returns `null` (= "no
filter"), otherwise it escapes the SQL `LIKE` wildcards (`\ % _`) and wraps the term as `%term%`:

```java
String escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
return "%" + escaped + "%";  // paired in the query with escape '\'
```

The service normalizes `q` with this helper and threads it into the finder as one more optional in-SQL clause:

```sql
and (:q is null or lower(c.claimNumber) like lower(cast(:q as string)) escape '\')
```

We **only search synthetic, PHI-free identifiers** — each queue's business number (claim/auth/referral/appeal/
review/batch), never patient names (rule 5 + "no sensitive data in query strings"). The two non-numbered queues
search their id fields instead: audit matches `correlationId` OR `resourceId`, dead-letters `eventId` OR
`messageKey`; because `resourceId`/`eventId` are **UUID columns**, they're cast to text so a partial-id paste
matches. On the frontend each page holds the raw box in one `useState` and a **300 ms debounced** copy in another
(a `setTimeout` in a `useEffect`), passing the debounced value as `q` so we query once typing settles.

**Slice 9 — CSV export with masking (reporting).**
A third pure helper, `common/Csv`: `field(raw)` RFC 4180-quotes a value containing a comma/quote/line-break
(doubling embedded quotes) **and defuses a leading formula trigger** (`= + - @` → prefixed with `'`) so opening the
file in Excel/Sheets can't run a **CSV-injection** formula; `row(cells)` joins fielded cells with a `\r\n`
terminator. The endpoint `GET /api/v1/patients/export.csv` (`produces="text/csv"`, `Content-Disposition:
attachment`) calls `PatientService.exportCsvForCurrentTenant()`, which **reuses `listForCurrentTenant()`** — the
exact same read the JSON API uses:

```java
public String exportCsvForCurrentTenant() {
    StringBuilder csv = new StringBuilder(Csv.row(CSV_HEADER));
    for (PatientDto p : listForCurrentTenant()) {            // inherits tenant scope + gate + masking
        csv.append(Csv.row(List.of(
            p.id().toString(), p.medicalRecordNumber(), p.fullName(),
            p.dateOfBirth() == null ? "" : p.dateOfBirth().toString(), // masked DOB is null → blank cell
            p.status().name())));
    }
    return csv.toString();
}
```

That single choice is the whole security point: the export inherits the tenant scope, the relationship gate (a
provider exports only assigned patients), and consent field masking — a masked `dateOfBirth` is already `null` in
the DTO, so it serializes as a **blank cell**. There is no second, unmasked "read raw columns for export" path
(§23.3: the backend is the only trusted masker). Columns are PHI-minimal. The frontend adds an **Export CSV**
button that fetches the blob and object-URL-saves `patients.csv`, mirroring the existing document download.

**Slice 10 — WCAG 2.2 AA-aligned accessibility pass.**
- Test foundation: `frontend/src/test/axe.ts` → `expectNoAxeViolations(container)` runs `axe-core` over rendered
  markup with the WCAG 2.0/2.1/2.2 A+AA rule tags. The `color-contrast` rule is **disabled** in the helper because
  it reads computed pixels via a canvas that jsdom doesn't implement — contrast is a browser check, not a jsdom one.
- App shell (`layout/AppLayout.tsx`, one file every page inherits): a **skip-to-content link** (first focusable,
  hidden until `:focus`, `href="#main"`) for WCAG 2.4.1 Bypass Blocks; a `<nav aria-label="Primary">` landmark; a
  focusable `<main id="main" tabIndex={-1}>` target; and the brand demoted from `<h6>` to `component="div"` so it's
  no longer a heading.
- Headings: a shared `components/PageHeading.tsx` (`variant="h5" component="h1"`) is now every route's single
  top-level title (26 pages converted), so each page has exactly one `<h1>` and a correct heading order.
- Verified **in the real browser** (logged in as `admin@northcare.example.org`): the accessibility tree showed the
  skip link → `#main`, the Primary `navigation` and `main` landmarks, the brand as plain text, and a single
  `heading level 1`; pressing Tab revealed the skip link; MUI's default theme contrast reads fine on the core
  screens.

### Key points to remember

- **Three tiny pure helpers carry the phase** — `PageRequests`, `SearchTerms`, `Csv` — all DB-free and
  unit-tested, in the same spirit as the state-machine/consent/anomaly policy classes. Decision/format logic lives
  in a pure class; a thin service applies it.
- **`cast(:q as string)` is mandatory** in the search `@Query`. A nullable `String` JPQL parameter used in
  `lower(:q)` is inferred by Postgres/Hibernate as `bytea`, and `lower(bytea)` throws a 500 at runtime. This was a
  real bug caught by Testcontainers (see Failures) and is now the standard pattern; UUID columns are also cast to
  text so partial-id search works.
- **We own the JSON page shape** (`PageResponse`) rather than exposing `PageImpl`, so the contract is stable.
- **Paging never widens access.** The authorization pipeline is untouched; an empty accessible-id set
  short-circuits to an empty page with no query. We proved the cross-tenant/relationship-gate tests still pass
  through the paged path.
- **Export = reuse the masked read, never a back-door read.** The CSV endpoint calls the same service method the
  JSON API uses, so masking/scoping are inherited, not re-implemented. This is the single most important idea of
  slice 9.
- **CSV injection is a real export concern.** A cell beginning with `= + - @` can execute as a formula in a
  spreadsheet; we defuse it with a leading `'`. Cheap, and it fits the security theme.
- **Search only PHI-free identifiers.** Business numbers and correlation/resource/event ids — never patient names,
  which would put sensitive data into query strings and logs.
- **axe under jsdom is a partial signal.** It cannot check color contrast (no rendering engine); that's a browser
  check. So we call the result WCAG 2.2 **AA-aligned**, not certified (rules 2–3). A full page-by-page audit + a
  Playwright + axe-core E2E gate (per the §29 test stack) are documented follow-ups.
- **One `<h1>` per page, via a shared component.** `PageHeading` keeps the `h5` visual size but renders semantic
  `<h1>`; new pages must use it, not a bare `Typography variant="h5"`.
- **Frontend search UX:** debounce (300 ms) the box and reset to page 0 on change; keep `keepPreviousData` so the
  table doesn't flash to a spinner on every keystroke/page.

### Failures and how we fixed them

1. **`function lower(bytea) does not exist` (slice 6, backend, 8 failures).** Symptom: every claims search test
   500'd. Root cause: a nullable `String` JPQL param in `lower(:q)` is inferred as `bytea` by Postgres. Fix: cast
   the param — `lower(cast(:q as string))` — and, for UUID columns, cast the column too. This is exactly why we test
   against real Postgres via Testcontainers; a mock would have passed and shipped the bug.
2. **Reprocessing search test timed out (slice 7, frontend).** Symptom: `findByText('RPB-...')` never resolved.
   Root cause: that test file's `beforeEach` sets no default `listReprocessingBatches` mock or user (each test sets
   its own), so the new search test rendered an empty/denied page. Fix: add `mockUser(['CLAIMS_REVIEWER'])` +
   `listReprocessingBatches.mockResolvedValue(pageOf([BATCH]))` at the top of the new test.
3. **`cannot find symbol assertFalse(boolean, String)` (slice 8, backend, compile failure).** Root cause: the new
   dead-letter search test used `assertFalse` but the class hadn't imported it. Fix: add
   `import static org.junit.jupiter.api.Assertions.assertFalse;`.
4. **Two `PatientDetailPage.test.tsx` timeouts under the full suite (slice 8, frontend).** Confirmed **pre-existing
   flakiness unrelated to the slice** (that file was untouched): it passes 18/18 in isolation, only timing out
   under parallel load. Documented, not "fixed" — it isn't a real break.
5. **`TS6133: 'Typography' is declared but never read` (slice 10, frontend).** After converting `NotFoundPage`'s
   title to `PageHeading`, that file no longer used `Typography`. Fix: drop it from the import. (Only that one file
   tripped it; the others still use `Typography` elsewhere.)
6. **axe threw canvas errors under jsdom (slice 10).** Symptom: noisy `HTMLCanvasElement.prototype.getContext not
   implemented` stderr during the a11y tests (tests still passed). Root cause: axe's `color-contrast` rule reads
   pixels via canvas, which jsdom lacks. Fix: disable that rule in the helper (`rules: { 'color-contrast': {
   enabled: false } }`) — it can't run meaningfully in jsdom anyway; contrast is a browser check.
7. **A background `./mvnw … | grep …` reported success on a real failure (process gotcha, carried from earlier).**
   Piping Maven through `grep` masks Maven's exit code (grep exits 0). Fix: redirect to a log file and capture
   `EXIT=$?` separately, and read the actual log/surefire report rather than trusting the pipeline's exit line.

Measured results this phase (observed, not estimated): backend test count grew across the phase to **492**
(pagination/search/CSV added repo + API + `CsvTest` unit tests); frontend to **181** (paged-queue UI tests, search
tests, the CSV export-button test, and 3 accessibility tests). All green at each slice's `verify`.

### Interview Q&A

#### 1. Beginner

**Q: What is server-side pagination and why not just return the whole list?**
A: The server returns one page at a time (`page`/`size`) plus totals, instead of every row. It keeps responses
small and fast as data grows, bounds memory and payload, and lets the database do the filtering/sorting/counting
efficiently. Returning everything doesn't scale and pushes work onto the browser.

**Q: What does the `PageResponse` envelope contain and why did you make your own?**
A: `content` (the rows) plus `page`, `size`, `totalElements`, `totalPages`, `first`, `last`. We own it so the JSON
contract is stable — Spring Data's `PageImpl` serializes in a shape that changes between versions and exposes
internals we don't want as a public API.

**Q: How does the free-text search box avoid hammering the server on every keystroke?**
A: It's **debounced** — we keep the raw text in one state and a copy that updates only ~300 ms after typing stops,
and we send that debounced value. So one request per pause, not per key.

**Q: What is "CSV export with masking" protecting against?**
A: A masked field (like a consent-restricted date of birth) must not leak through the export. If it shows as
"Restricted" on screen, the CSV cell must be blank too. We guarantee that by exporting the same already-masked data
the API returns.

**Q: What's a skip link and who benefits?**
A: A "Skip to main content" link that's the first thing you reach with the keyboard; it jumps focus past the long
navigation straight to the page content. Keyboard and screen-reader users benefit — they don't have to tab through
~14 nav items on every page (WCAG 2.4.1).

#### 2. Intermediate

**Q: Why is filtering pushed into SQL instead of filtering a fetched list in Java?**
A: In-memory filtering still fetches every row (defeating pagination), computes wrong totals, and doesn't scale.
Pushing `where`/`order by`/`limit`/`offset` into SQL lets the database use indexes and return the exact page plus a
correct `totalElements`. We replaced the claims queue's old in-memory `.filter` with `@Query` finders for exactly
this reason.

**Q: How does pagination interact with your authorization model?**
A: It doesn't change it. The §21 layers (tenant → role → patient/relationship gate) run as before. Broad roles use
`searchAll`; gated callers get their accessible patient-id set and use `searchForPatients`, and if that set is empty
we return `PageResponse.empty` with no query at all. We kept the cross-tenant and relationship-gate tests green
through the paged path to prove access didn't widen.

**Q: Why `cast(:q as string)` in the search query, and why only search identifiers?**
A: A nullable `String` bind parameter used in `lower(:q)` is inferred by Postgres as `bytea`, and `lower(bytea)`
fails at runtime — the cast pins it to text (UUID columns are cast too, for partial matches). We only search
synthetic, PHI-free identifiers (claim/auth/... numbers, correlation/resource/event ids) because searching patient
names would put sensitive data into query strings and server logs, violating our no-PHI-in-logs rule.

**Q: Walk through how the CSV export guarantees masking without duplicating logic.**
A: The endpoint calls `PatientService.exportCsvForCurrentTenant()`, which calls the same `listForCurrentTenant()`
the JSON list uses. That method already applies tenant scoping, the relationship gate, and consent masking, so a
masked DOB is already `null` in the DTO. The exporter just formats those DTOs, so a masked field becomes a blank
cell. There's no separate "read raw columns" path that could bypass masking — the single trusted read is reused.

**Q: What is CSV/formula injection and how did you handle it?**
A: If a cell's value starts with `=`, `+`, `-`, or `@`, a spreadsheet may interpret it as a formula (which can leak
data or run actions). Our `Csv.field` prefixes such a value with a single quote so it's shown literally. We also do
RFC 4180 quoting for commas/quotes/newlines. It's relevant because one exported column (the patient name) is free
text.

**Q: What can and can't axe-core verify in your test setup, and how do you stay honest about it?**
A: Under jsdom, axe checks structural rules (labels, roles, landmarks, heading order, names) but **cannot** check
color contrast — there's no rendering engine, so we disable that rule and verify contrast in a real browser. That's
why we describe the result as WCAG 2.2 **AA-aligned** (automated A/AA + keyboard/landmark/heading + in-browser
contrast on core screens), not "certified". A full audit and a Playwright+axe E2E gate are noted follow-ups.

#### 3. Advanced

**Q: Your outbox relay assumed a single instance; does the paginated read side have any similar concurrency or
correctness caveats?**
A: The reads are stateless and safe to scale. The subtler issue is **pagination consistency under concurrent
writes** — offset paging over a changing table can skip or repeat a row between pages (a row inserted/deleted
shifts offsets). For a work queue that's acceptable; if it mattered we'd move to **keyset/seek pagination** (order
by a stable key and page with `where (sort_key) < :lastSeen`) which is also faster at deep offsets. `totalElements`
is also a point-in-time snapshot.

**Q: How would you extend the CSV export to the work queues, and what changes vs the patient export?**
A: The `Csv` helper is already reusable. Each queue would add an `export.csv` endpoint that calls its existing
paged service read (ideally honoring the current filters/search) and formats PHI-free columns. The difference:
those queues aren't consent field-masked (they carry coded, PHI-free data), so the masking concern is mostly
"don't add a column the API withholds" (e.g. never resolve and emit a patient name). For large exports you'd stream
(`StreamingResponseBody` + a cursor/keyset) instead of building the whole string in memory — our honest current
limitation.

**Q: The sort field is allowlisted. What concretely goes wrong without that, and what's the failure mode you
prevented?**
A: If you pass the client's `sort` straight to Spring Data, an unknown property throws `PropertyReferenceException`
→ a 500, and worse, a caller could order by *any* mapped column — a mild information-disclosure/enumeration and
performance foot-gun (sorting by an unindexed column). `PageRequests` allowlists the field and validates the
direction, turning bad input into a clean 400 and bounding what can be sorted. Same philosophy as parameter
binding: never trust client strings as query structure.

**Q: A screen reader announces your nav items as "buttons," not "links." Is that a WCAG failure, and how would you
improve it?**
A: It's not a hard AA failure — the controls are focusable and have accessible names, and they do perform an action
(navigation via the router). But semantically they should be links (they change the URL/location), so a more
correct implementation uses router `Link`/anchor elements so assistive tech announces "link" and users get
open-in-new-tab, right-click, etc. It's a refinement we'd fold into the documented accessibility follow-up, along
with target-size (WCAG 2.2 2.5.8) and a full contrast/focus sweep.

**Q: Why keep a non-paged `useClaims()` array hook alongside the paged one? Isn't that duplication a smell?**
A: It's a deliberate compatibility shim. The claims queue itself uses the paged hook, but several *other* features
(appeals/reviews/reprocessing name-resolution and `<select>` option lists) need the full set of claims, not one
page. Rather than force those callers to page through everything, `api.listClaims()` fetches one large page and
returns `.content`. The alternative — a dedicated lightweight "list ids/numbers" endpoint — would be cleaner long
term; the shim was the smaller, lower-risk step while paging rolled out.

---

## Phase 10 — Cloud Deployment on AWS: containerize the app + CI images + Terraform skeleton (slices 1–5) — 2026-09-17

### What we built
The **local-only, $0** foundation of the cloud phase, before any AWS account was touched: production container images
for both the backend and frontend, a CI pipeline that builds and publishes those images on every push, and a Terraform
skeleton that establishes the IaC conventions while declaring **no resources**. Nothing here costs money or needs AWS —
it's the artifacts and scaffolding that the later slices (6–15) actually deploy.

### How it works
- **Slice 1 — containerize the backend.** A multi-stage `backend/Dockerfile`: stage 1 builds the jar on a full JDK 25,
  stage 2 runs it on a slim JRE as a **non-root** `spring` user, with an `/actuator/health` HEALTHCHECK. Tests are
  skipped in the image build (they need Testcontainers/Docker and already gate in CI). An **opt-in `backend` service**
  in `docker-compose.yml` behind a `full` profile so the everyday `up -d postgres kafka` is unchanged. This is the exact
  artifact ECS Fargate runs later.
- **Slice 2 — publish the backend image in CI.** A `backend-image` job in `.github/workflows/ci.yml` (`needs: backend`,
  so only a tested image ships) builds the image via `docker/build-push-action` and **pushes it to GHCR**
  (`ghcr.io/nikhil-oggu/healthcloud-backend`, tags `sha-<short>` immutable + `latest`) **only on push to `main`** — PRs
  build but don't push. Auth is the automatic `GITHUB_TOKEN` (`packages: write`), so there are no secrets to manage.
- **Slice 3 — containerize the frontend.** A multi-stage `frontend/Dockerfile`: build the SPA with Node, then serve it
  from a **non-root nginx** that also **reverse-proxies `/api` + `/actuator` to the backend same-origin** — the
  production mirror of the Vite dev proxy, so the session + CSRF cookies stay first-party (no CORS, no token in JS).
  `default.conf.template` holds the SPA deep-link fallback + the `${BACKEND_UPSTREAM}`-templated proxy. Now the whole
  app runs as containers locally (frontend → backend → postgres).
- **Slice 4 — publish the frontend image in CI.** A `frontend-image` job mirrors slice 2 (GHCR, same tags, push only on
  `main`); the two image jobs use distinct GHA cache scopes so they don't evict each other. Both deployable images now
  build + publish automatically.
- **Slice 5 — Terraform skeleton.** `infrastructure/terraform/` establishes the IaC baseline: version pins (Terraform
  ≥1.9, AWS provider ~>6.0), the `aws` provider with **`default_tags`**, region/project/environment variables, a
  `name_prefix` + `common_tags` locals, outputs, and a `backend.tf` documenting local-state-now / S3-later. It
  **declares NO resources**, so `fmt`/`validate`/`plan` all run with **no AWS account, no credentials, and $0**.

### Key points
- Everything in slices 1–5 is **local-only and free** — the deliberate "get the artifacts + conventions right before
  spending a cent" phase. The first real AWS resource is slice 6 (the S3 state bucket).
- **Non-root containers + a slim runtime** are defense-in-depth and smaller images; the healthcheck is the same signal
  ECS/ALB use later.
- **Immutable `sha-<short>` tags** are what a deploy pins to; `latest` is the moving convenience tag.
- CI publishes to **GHCR**, but ECS later pulls from **ECR** — a distinction that matters in slices 9/14 (we push to
  ECR by hand with crane; CI's GHCR images aren't what the cluster runs).

### Interview Q&A
- **Q (beginner): Why put the app in a container at all?** A: A container bundles the app with the exact runtime it
  needs, so it runs identically on a laptop, in CI, and on AWS. It's the unit ECS Fargate schedules — "build once, run
  anywhere" instead of configuring a server by hand.
- **Q (intermediate): Why a multi-stage Dockerfile?** A: The build stage needs the full JDK and Maven (or Node) and
  produces a lot of intermediate junk; the runtime stage needs only the compiled artifact and a slim JRE (or nginx).
  Multi-stage keeps the final image small and free of build tooling — smaller attack surface, faster pulls.
- **Q (intermediate): Why does the frontend image run nginx that proxies to the backend, instead of the SPA calling the
  API cross-origin?** A: Same-origin. If nginx serves the SPA and forwards `/api` to the backend, the browser sees one
  origin, so the HttpOnly session cookie and CSRF cookie are first-party — no CORS, and no auth token ever touches
  JavaScript. It's the production mirror of the Vite dev proxy.
- **Q (advanced): Your CI only pushes images on `main`, not on PRs. Why build them on PRs at all then?** A: Building on
  a PR proves the Dockerfile still works and the image assembles before merge, catching breakage early — but publishing
  a `latest`/`sha` image from unreviewed code would pollute the registry and could be deployed by mistake. So PRs build
  (verify) and only `main` publishes (release).
- **Q (advanced): The Terraform skeleton declares no resources. What's the point of committing it?** A: It locks in the
  conventions every later resource inherits — provider version, automatic tagging via `default_tags`, the naming prefix,
  the remote-state plan — and proves `fmt`/`validate`/`plan` are green, all with zero cost and no AWS account. It makes
  the first resource-adding slice a small, low-risk diff instead of a big-bang setup.

---

## Phase 10 — Cloud Deployment on AWS: account setup, remote state, VPC, RDS, ECR (slices 6–9) — 2026-09-19

### What we built
This session took HealthCloud from "container images + a resource-free Terraform skeleton" to **real AWS
infrastructure**, one small verified slice at a time, on a brand-new AWS account using free credits:

- **AWS account onboarding** — a new account (Free Plan, $100 credits), a dedicated IAM user for Terraform, a
  `$5` budget alarm, and the AWS CLI configured locally.
- **Slice 6 — Terraform remote state (S3):** a one-time `bootstrap/` config created an encrypted, versioned S3
  bucket, and the main config switched from local state to that S3 backend (with S3-native locking).
- **Slice 7 — VPC & networking:** a VPC with public + private subnets across 2 AZs, an internet gateway, and
  route tables — deliberately **no NAT Gateway** to save cost.
- **Slice 8 — RDS PostgreSQL:** a managed Postgres 17 database in the private subnets, encrypted, not publicly
  accessible, with its master password managed in Secrets Manager.
- **Slice 9 — ECR (in progress):** two container registries were created; pushing the images hit a real-world
  Docker wall (documented below) and the push method is still undecided.

Everything followed a strict **two-gate cost rule**: (1) write Terraform + `plan` ($0, nothing created), shown
for approval; (2) explicit go-ahead before `terraform apply`.

### How it works

**Remote state bootstrap (the chicken-and-egg).** Terraform can't store its state *in* a bucket that doesn't
exist yet. So a small standalone `infrastructure/terraform/bootstrap/` config with its **own local state**
creates the bucket first; then the main config points at it:

```hcl
# infrastructure/terraform/backend.tf
terraform {
  backend "s3" {
    bucket       = "healthcloud-tfstate-927747714796"
    key          = "healthcloud/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true   # S3-native locking (Terraform >= 1.10) — no DynamoDB table needed
  }
}
```
`terraform init -migrate-state` moved the existing state into S3.

**Networking, cost-first.** The key decision is *where compute lives*:

- **Public subnets** host the future ALB + Fargate — tasks get a public IP and reach the internet directly.
- **Private subnets** host RDS — no outbound internet needed.
- Because compute is in public subnets, there is **no NAT Gateway** (which would cost ~$32/mo). The private
  route table is local-only (no `0.0.0.0/0`), which *is* the "no egress" guarantee.

**RDS, private + secret-managed.** The database sits in the private subnets via a DB subnet group, is not
publicly accessible, and its password is never in code or state:

```hcl
resource "aws_db_instance" "main" {
  engine                      = "postgres"
  engine_version              = "17"            # prefix-matches latest 17.x (no drift)
  instance_class              = "db.t4g.micro"
  storage_encrypted           = true
  publicly_accessible         = false
  manage_master_user_password = true            # password lives in Secrets Manager
  # ... backups off, skip_final_snapshot, deletion_protection=false -> clean teardown
}
```
The app will later read `db_master_secret_arn` from Secrets Manager via IAM.

**ECR.** Two repositories (`healthcloud-dev-backend`, `healthcloud-dev-frontend`) with `scan_on_push`, a
"keep last 5 images" lifecycle policy, and `force_delete = true` so `terraform destroy` removes them cleanly.

### Key points to remember

- **The new AWS Free Plan model:** free-tier usage is free; usage *above* free tier **draws from the $100
  credit balance**, and the card isn't charged while credits cover it. So paid services (Fargate/RDS/ALB) are
  *not blocked* — they consume credits. An account can auto-upgrade to a paid plan if it uses certain services
  (e.g. AWS Organizations); we avoid those.
- **New accounts take time to activate.** For ~1 hour, every S3 call failed with
  `NotSignedUp: Your account is not signed up for the S3 service`. This is account activation lag (AWS says up
  to 24h), **not** a code/credentials/permissions problem. It cleared when AWS emailed "account is now active."
- **Cost discipline is a design input, not an afterthought:** no NAT Gateway, `db.t4g.micro`, single-AZ, backups
  off, `skip_final_snapshot` — all chosen so the stack is cheap and **`destroy`-able on demand** (apply → capture
  evidence → destroy → ~$0).
- **Never put a DB password in Terraform.** `manage_master_user_password = true` keeps it in Secrets Manager,
  out of both the `.tf` and the state file.
- **S3-native locking** (`use_lockfile = true`) replaced the old S3+DynamoDB pattern on Terraform ≥ 1.10 — one
  fewer resource.
- **Secrets:** we never pasted AWS keys into the chat; the human ran `aws configure` privately, and verification
  used `aws sts get-caller-identity` (shows account/ARN, never the secret).
- **MSK is deliberately out of scope for AWS** — managed Kafka bills ~continuously and would dominate cost. The
  event-driven system stays proven locally (Phase 8); the cloud deploy is web app + DB + auth.

### Failures and how we fixed them

- **AWS CLI wouldn't run (Homebrew build broken).** Symptom: `aws` crashed with
  `dlopen(... pyexpat ...): Symbol not found: _XML_SetAllocTrackerActivationThreshold`. Root cause: the Homebrew
  `awscli`'s bundled Python linked against the wrong `expat`. Fix: uninstall the brew version and install the
  **official AWS CLI v2 pkg** (self-contained), which worked immediately.
- **`aws configure` credential mismatch.** After several re-runs, `aws sts get-caller-identity` returned
  `InvalidClientTokenId`. Root cause: an access-key ID and secret from *different* keys got mixed. Fix: re-enter
  both values from the **same** downloaded `.csv`.
- **S3 apply failed: `NotSignedUp`.** Not our bug — new-account activation lag (see Key points). Fix: wait for
  the "account is now active" email, then re-run `terraform apply` (idempotent — nothing had been created).
- **`docker push` to ECR kept timing out.** Symptom: `net/http: timeout awaiting response headers` on one large
  layer. Root cause: the backend image is **823 MB**, and Docker splits upload bandwidth across parallel layers,
  so the biggest layer never finished within the HTTP timeout on a home uplink. Attempted fix
  (`max-concurrent-uploads: 1` in `~/.docker/daemon.json`) **didn't stick** — Docker Desktop stripped the key on
  restart.
- **Docker Desktop wedged: `Docker.raw is held by another process / disk image in use`.** Root cause: a quick
  quit-and-reopen left the old VM holding the disk image, so the new VM couldn't start; kill-and-reopen cycles
  didn't clear it. Fix in progress: a **Mac restart** (guaranteed to release the lock), or Docker Desktop →
  Troubleshoot → Clean/Purge data. **This is why slice 9's image push is unfinished.**
- **The bigger lesson:** the image push is bottlenecked on a home upload link. The robust alternative is to push
  from **CI** (fast runner network) via **GitHub OIDC** — no local Docker needed. Decision still open.

### Interview Q&A

#### 1. Beginner

**Q: What is Terraform "state" and why store it remotely in S3?**
A: State is Terraform's record of what it has created and how config maps to real resources. Stored locally
it's a fragile file on one laptop; in S3 it's durable, shared, encrypted, and versioned, and with locking two
runs can't corrupt it. We used an S3 backend with S3-native locking.

**Q: Why does the database go in a "private" subnet and the load balancer in a "public" one?**
A: Public subnets have a route to the internet gateway, so things that must be reachable from the internet
(the ALB, and our Fargate tasks pulling images) live there. The database should never be reachable from the
internet, so it lives in a private subnet with no internet route — only things inside the VPC can talk to it.

**Q: What is a NAT Gateway and why did we skip it?**
A: A NAT Gateway lets resources in *private* subnets make *outbound* internet connections (e.g. to pull an
image) without being publicly reachable. It costs ~$32/mo. We skipped it by putting our compute in public
subnets (so it has its own egress) and keeping only the database — which needs no egress — private.

**Q: Where is the database password stored?**
A: In AWS Secrets Manager, created and managed by RDS (`manage_master_user_password = true`). It is never in
our Terraform code or state file; the application will read it at runtime via its IAM role.

#### 2. Intermediate

**Q: Explain the remote-state "bootstrap" chicken-and-egg and how you solved it.**
A: The main config wants to store its state in an S3 bucket, but that bucket doesn't exist until Terraform
creates it — and Terraform can't store the state of *creating* the bucket in the bucket itself. We solved it
with a tiny separate `bootstrap/` config that uses **local** state to create the bucket, then pointed the main
config's `backend "s3"` at that bucket and ran `terraform init -migrate-state`.

**Q: Your CI builds `linux/amd64` images for GHCR, but you were about to build `arm64` locally for ECR. Why,
and what breaks if the architecture is wrong?**
A: The dev Mac is Apple Silicon, so a native `docker build` produces arm64 — fast, and Fargate can run arm64
(Graviton, ~20% cheaper). If we instead push from CI (amd64 runners), the images are amd64 and Fargate must be
configured x86_64. The rule: the Fargate task's `cpu_architecture` must match the image architecture, or the
container won't start (exec format error).

**Q: Why is `skip_final_snapshot = true` and `backup_retention_period = 0` acceptable here but not in
production?**
A: This is a synthetic-data demo we intentionally `destroy` and recreate to control cost, so a final snapshot
and automated backups would just add storage and slow teardown. In production you'd keep backups and a final
snapshot (and multi-AZ, deletion protection) because the data is irreplaceable — those are the very features
Phase 11 (backup/restore drills) is about.

**Q: How did you keep this whole session at (near) $0 despite creating real resources?**
A: Free-tier-eligible/free resources (VPC, subnets, S3 state, `db.t4g.micro`), no NAT Gateway, the Free Plan's
credits absorbing any overage, a `$5` budget alarm as a tripwire, and an on-demand model (destroy when idle).

#### 3. Advanced

**Q: `docker push` kept timing out on a large layer. Walk through the root cause and the range of fixes.**
A: The image was 823 MB and Docker uploads layers in parallel, splitting the uplink; the largest blob's PUT
never returned headers before the client timeout on a slow home connection. Fixes, cheapest first: (1) serialize
uploads (`max-concurrent-uploads: 1`) so the big layer gets full bandwidth — but Docker Desktop stripped that
setting; (2) use `crane`, which streams with retries instead of a hard timeout; (3) shrink the image (slimmer
base/JRE) so no single layer is huge; (4) **push from CI** where the runner has a fast, stable uplink — the most
robust, and what real teams do. The deeper lesson: pushing large artifacts from a developer laptop is the wrong
place; image publishing belongs in CI.

**Q: If you push to ECR from GitHub Actions, how do you authenticate without long-lived AWS keys, and what are
the risks?**
A: Use GitHub OIDC: create an IAM OIDC identity provider for `token.actions.githubusercontent.com` and an IAM
role whose trust policy is scoped to your specific repo (ideally branch/ref), granting only ECR-push
permissions. GitHub Actions exchanges its OIDC token for short-lived AWS credentials — no stored secrets. Risks:
if the trust policy is too broad (wildcard repo/ref) or the permissions aren't least-privilege, any workflow in
that repo (or a malicious PR) could assume the role. Mitigate by pinning the repo/ref and ECR-only actions.

**Q: A brand-new account returned `NotSignedUp` for S3 for about an hour. How do you tell that apart from a real
permissions or configuration bug, and what would you do in an automated pipeline?**
A: `NotSignedUp` specifically means the *service isn't activated for the account* — distinct from `AccessDenied`
(IAM policy), `InvalidClientTokenId` (bad creds), or a region/name error. Confirm creds work with
`aws sts get-caller-identity` (it succeeded), confirm the IAM policy allows the action, then treat `NotSignedUp`
as transient activation lag. In a pipeline you'd add a bounded retry-with-backoff around the first calls on a
fresh account, or gate the pipeline until an activation check passes — never hard-fail on the first attempt.

**Q: The Docker VM wedged on "disk image in use." What actually happened at the OS level, and why is a restart
the reliable fix?**
A: Docker Desktop runs a Linux VM backed by a single disk image file (`Docker.raw`) via the macOS Virtualization
framework. Quitting and immediately relaunching started a new VM before the old process released its exclusive
handle on `Docker.raw`, so the new VM's open failed and Desktop latched into an error state. Even after killing
processes (and `lsof` showing the file free), the app's state machine stayed wedged. A reboot guarantees every
process and file handle is gone and Desktop starts from a clean slate — hence the reliable fix. A non-reboot
alternative is Docker Desktop's "Clean / Purge data," which rebuilds the VM disk.

## Phase 10 — Cloud Deployment on AWS: ECS Fargate + ALB — the app goes LIVE (slice 10) — 2026-09-19

### What we built
The pushed images from slice 9 are now a **running, reachable web app on the internet**, behind an Application Load
Balancer, backed by the slice-8 RDS database. This is the first slice that costs money per hour, so it is run
**on-demand**: `terraform apply` → prove it works + capture evidence → `terraform destroy` back to ~$0. The Terraform
lives in `infrastructure/terraform/ecs.tf` + `alb.tf`, with a one-line tighten to `rds.tf` and a new `app_url` output.

### How it works
- **One task, two containers, localhost.** Instead of two services (two Fargate charges), a single ECS **task
  definition** holds both containers — the backend (Spring) and the frontend (nginx) — and they talk over
  `localhost`, exactly like the local docker-compose setup. This is the cheapest shape and reuses both images
  **unchanged**, setting only environment variables.
- **The port trick.** In Fargate `awsvpc` networking, all containers in a task share one network namespace (one
  localhost, one set of ports), so two containers can't both bind 8080. The nginx image is hard-wired to 8080, so we
  moved the **backend to 8081** via the `SERVER_PORT` env var (Spring honours it) and pointed nginx at it with
  `BACKEND_UPSTREAM=http://localhost:8081`. The ALB sends traffic to the frontend on 8080; nginx reverse-proxies
  `/api` + `/actuator` to the backend on 8081 — same-origin, so the session + CSRF cookies stay first-party.
- **ARM64 is mandatory.** The images were built on an Apple-Silicon Mac (`linux/arm64`), so the task definition sets
  `runtime_platform { cpu_architecture = "ARM64" }`. A mismatch means the container silently won't start. Graviton is
  also ~20% cheaper.
- **The DB password is never in code or state.** The RDS master password lives in Secrets Manager (slice 8). ECS
  injects it at container start via the container `secrets` block — `valueFrom = "<secretArn>:password::"` pulls just
  that JSON key of the secret into the `HEALTHCLOUD_DB_PASSWORD` env var. The **execution role** (the role the ECS
  *agent* uses, distinct from the app's own **task role**) carries the AWS-managed `AmazonECSTaskExecutionRolePolicy`
  (ECR pull + CloudWatch logs) plus a tiny inline policy granting `secretsmanager:GetSecretValue` on that one secret.
- **Networking + security groups.** Tasks run in the **public subnets** with `assign_public_ip = true` — because
  there's no NAT Gateway (slice-7 cost decision), a task needs its own public IP to reach ECR and Secrets Manager. The
  **app security group** allows port 8080 only from the **ALB security group**; the **RDS security group** was
  tightened to allow 5432 only from the app SG (was VPC-wide). The ALB itself allows HTTP :80 from anywhere.
- **Demoable profile.** Cognito (real auth) isn't wired yet, so to have a working, clickable site the backend runs
  the **`local` Spring profile** — which enables the dev-login stand-in and seeds synthetic demo data. Kafka is turned
  off (`HEALTHCLOUD_OUTBOX_RELAY_ENABLED=false`, `HEALTHCLOUD_KAFKA_CONSUMERS_ENABLED=false`) because there's no MSK
  on AWS. Once Cognito lands we flip to the default (production-shape) profile.

### Key points
- `terraform apply` = **13 added, 1 changed (RDS SG in-place), 0 destroyed**. The task was healthy in ~60 s.
- Verified live at the ALB URL: `/actuator/health` UP with `db` UP (proves the whole chain ALB → nginx → backend →
  RDS); the SPA is served; `dev-login` + `/me` work over plain HTTP (the session cookie isn't forced `Secure`); the
  seeded patients read is **relationship-gated** (the provider sees only assigned patients) and **consent-masked**
  (`dateOfBirth` blanked) — the app's whole security model runs intact in the cloud.
- Cost ~$0.08–0.10/hr (ALB + Fargate ARM 1 vCPU/2 GB + a few public IPv4s + RDS), from account credits — the card is
  not charged. Return to ~$0 with `terraform destroy` (the S3 state bucket is kept).

### Interview Q&A
- **Q (beginner): What does the load balancer actually do here?** A: The ALB is the single public entry point. It
  gets a stable DNS name, listens on port 80, health-checks the task, and forwards requests to the container. Without
  it you'd expose the task's ephemeral public IP directly — no health checking, no stable address, no place to later
  add HTTPS.
- **Q (intermediate): Why one task with two containers instead of two services?** A: Cost and simplicity. Two
  services means two Fargate tasks (roughly double the compute bill) plus service discovery so the frontend can find
  the backend. Co-locating them in one task lets them talk over `localhost` — identical to the compose setup — and
  keeps the same-origin cookie design. The trade-off is they scale together; for a portfolio demo that's fine.
- **Q (intermediate): How does the app get the database password without it being in the repo or Terraform state?**
  A: RDS stores the master password in Secrets Manager. The ECS task definition references the secret's ARN in its
  `secrets` block; the ECS agent (using the execution role's `GetSecretValue` permission) fetches it at container
  start and injects it as an environment variable. The password never appears in the `.tf` files, the state file, or
  the container image.
- **Q (advanced): You built the images on an M-series Mac. What breaks on Fargate if you ignore that, and why?** A:
  The images are `linux/arm64`. Fargate defaults to x86_64; if the task's `runtime_platform` doesn't say `ARM64`, the
  scheduler places the task on x86 hardware and the arm64 binaries can't execute — the container fails to start with
  an exec-format error. Setting `cpu_architecture = "ARM64"` runs it on Graviton, which also costs less.
- **Q (advanced): The site is HTTP-only and runs a dev-login. Is that acceptable?** A: For an on-demand, synthetic-
  data demo that's stood up and torn down, yes — but it's explicitly temporary. HTTPS needs an ACM certificate and a
  domain, which is deferred to the CloudFront/custom-domain slice, and real auth (Cognito OIDC, which itself requires
  HTTPS redirect URIs) replaces the dev-login stand-in in a later slice. The honest framing — "demoable now, hardened
  later" — matters; you never present a dev-login HTTP deployment as production-ready.

## Phase 10 — Cloud Deployment on AWS: Amazon Cognito (real-auth infrastructure) (slice 11) — 2026-09-19

### What we built
The first step of replacing the local **dev-login stand-in** (email-only, no password) with **real authentication**
via **Amazon Cognito** (ADR-004: Cognito + a Spring Boot BFF). Because real auth spans infrastructure, the backend,
and the frontend, we sliced it: **slice 11 is the Cognito infrastructure only** — a user pool, an app client, a hosted
login page, and two synthetic users — stood up in Terraform (`infrastructure/terraform/cognito.tf`) and proven, with
the app-code wiring deferred to the next slices. Cost: **$0** (Cognito's free tier covers 50k monthly active users).

### How it works
- **User pool** = the directory of users. We set email as the sign-in name (`username_attributes = ["email"]`) so
  Cognito users line up with the app's existing user emails, auto-verify email, and set MFA to **OPTIONAL** (TOTP
  available but not forced — simpler demo login; enforcing MFA is a documented follow-up).
- **App client** = the application's registration with the pool. It's a **confidential client**
  (`generate_secret = true`) because our backend is a server-side **BFF (Backend-For-Frontend)** that holds the
  session — the browser never sees tokens. It uses the OIDC **authorization-code** flow with `openid`/`email`/`profile`
  scopes. The callback URL is Spring Security's default (`/login/oauth2/code/cognito`) on `http://localhost` for now.
- **Hosted UI domain** = Cognito's own free login page (`https://<prefix>.auth.<region>.amazoncognito.com`). No custom
  domain or certificate needed; the prefix carries the account id to be globally unique.
- **Synthetic users** = two `aws_cognito_user` records (`provider@`, `admin@northcare.example.org`) matching seeded
  app users, so the next slice's login maps straight onto existing roles. Crucially, **no password is in Terraform** —
  passwords would be stored in the state file — so the users are created in `FORCE_CHANGE_PASSWORD` and a permanent
  synthetic password is set afterward with `aws cognito-idp admin-set-user-password --permanent`.

### Key points
- Applied **via `-target`** (only the Cognito resources), because the ALB/ECS from slice 10 were deliberately torn
  down but are still in the config — a plain `terraform apply` would recreate them and restart the hourly bill. This
  is a good example of using `-target` for a real, specific reason (not routine use).
- Verified without any app: `describe-user-pool` (pool + MFA config), `list-users` (both users present), the OIDC
  discovery document (`/.well-known/openid-configuration`) resolves its authorize + token endpoints, and the hosted
  login page renders over HTTPS in the browser.
- The client **secret** is a real output (`sensitive = true`) — it lives in the private encrypted state and will be
  handed to the backend via config/Secrets Manager, never committed.

### Interview Q&A
- **Q (beginner): What is a Cognito user pool, in one sentence?** A: A managed directory of users plus the sign-up /
  sign-in machinery (password storage, email verification, MFA, a hosted login page) so the app doesn't build or store
  any of that itself.
- **Q (intermediate): Why a confidential client with a secret instead of a public client?** A: Our backend is a BFF —
  a server-side app that completes the OAuth code exchange and keeps the session in an HttpOnly cookie, so the browser
  never handles tokens. A confidential client (with a secret only the server knows) is the correct, more secure shape
  for that. A public client + PKCE is for cases where the client can't keep a secret, like a pure SPA doing the flow
  itself.
- **Q (intermediate): Why is there no password in your Terraform, and how do the demo users get one?** A: Anything in
  a Terraform resource ends up in the state file, so putting a password there would persist a credential in state. We
  create the user records without a password (invite suppressed) and set a permanent synthetic password out-of-band
  with the AWS CLI, keeping every credential out of code and state.
- **Q (advanced): You applied with `-target`. Why, and what's the risk of `-target` normally?** A: Slice 10's ALB and
  ECS service are still described in the config but were destroyed to stop billing, so a normal apply would recreate
  them. Targeting just the Cognito resources avoids that. The general risk of `-target` is that it applies a partial
  plan — the config and state can drift and outputs may not fully refresh — so it's for exceptional situations, which
  this is; routine changes should be a full plan/apply.
- **Q (advanced): Why can Cognito use `http://localhost` now but the deployed app will need HTTPS?** A: Cognito only
  allows non-HTTPS redirect URLs for `localhost` (a dev convenience). Any real hostname (like the ALB or a domain)
  must use HTTPS, so deploying the Cognito-backed app forces us to add an ACM certificate + an HTTPS listener (or
  CloudFront) — which is exactly why that work is bundled into the deploy slice.

## Phase 10 — Cloud Deployment on AWS: Spring Security OAuth2 BFF (real Cognito login) (slice 12) — 2026-09-19

### What we built
The backend now authenticates real users against the slice-11 Cognito pool using the **BFF (Backend-For-Frontend)**
pattern — the server completes the OIDC flow and keeps the session in an HttpOnly cookie; the browser never handles
tokens. This is backend-only and proven locally against the real pool; the frontend button and the HTTPS deploy are
later slices. The dev-login stand-in is kept for offline work. Cost: **$0** (no AWS changes).

### How it works
- **The email join key (why this was low-risk).** `UserContextFilter` already resolved the caller by
  `resolveByEmail(authentication.getName())`, and dev-login set the principal name to the user's email. By configuring
  the Cognito provider with `user-name-attribute: email`, the OIDC principal's name is *also* the email — so the
  entire authorization stack (roles, tenant, the §21 access gate) keeps working unchanged. Cognito only establishes an
  email-keyed session; **roles are still read from the database**, never from Cognito claims (rule 4: the backend is
  the authorization source of truth).
- **Conditional wiring.** `spring-boot-starter-oauth2-client` is added, but `SecurityConfig` only calls
  `.oauth2Login(...)` when a `ClientRegistrationRepository` bean exists (injected as an `ObjectProvider` and
  null-checked). So when Cognito isn't configured — offline dev, CI — the app boots on dev-login alone and nothing
  changes. The Cognito config lives in a `cognito`-profile file activated with `SPRING_PROFILES_ACTIVE=local,cognito`.
- **Provisioning gate.** `CognitoOidcUserService` (extends Spring's `OidcUserService`) runs after Cognito
  authenticates and rejects the login (throws `OAuth2AuthenticationException`) unless an ACTIVE `AppUser` exists for
  the token's email — so a valid Cognito identity with no app account never gets a session.
- **Secret hygiene.** Only the client secret is sensitive; it's `${COGNITO_CLIENT_SECRET}` from the environment and
  never committed. The pool/client IDs and issuer are non-secret and env-overridable, with the dev pool's values as
  defaults.

### Key points
- Verified without a full login: 3 unit tests for the provisioning gate, and — running against the real pool —
  `GET /oauth2/authorization/cognito` returns a **302 to Cognito's authorize endpoint** with `response_type=code`,
  the right scopes and redirect URI, and **PKCE** (`code_challenge`, S256). That proves the client registration and
  flow initiation end to end. dev-login still works (regression check).
- The interactive round-trip (typing the password at Cognito) is a manual step: it needs the demo users to have a
  password set (`admin-set-user-password --permanent`), and entering a password to authenticate is something the
  human does, not the assistant.

### Interview Q&A
- **Q (beginner): What is the BFF pattern?** A: A Backend-For-Frontend is a server-side app that sits between the SPA
  and the identity provider: it performs the OAuth login, exchanges the code for tokens, and keeps the session in a
  secure HttpOnly cookie. The browser never sees or stores tokens, which removes a big class of XSS token-theft risks.
- **Q (intermediate): You added Cognito but CI and offline dev still pass with no Cognito config. How?** A: The
  OAuth2 login is wired only when a `ClientRegistrationRepository` bean is present (checked via an `ObjectProvider`).
  With no client configured, Spring doesn't create that bean, so `.oauth2Login(...)` is never added and the app runs on
  the existing dev-login path — builds and tests are untouched.
- **Q (intermediate): Cognito authenticated the user — where do their roles and tenant come from?** A: From our
  database, by email. Cognito only proves *who* the user is; the app looks that email up in `AppUser` /
  `OrganizationMembership` / `UserRole` to decide *what they can do*. Keeping authorization server-side and
  DB-derived is a core rule — we never trust an external IdP's claims for authorization.
- **Q (advanced): Why set `user-name-attribute: email`, and what would break otherwise?** A: Our whole request
  pipeline resolves the current user by `authentication.getName()` treated as an email. By default an OIDC user's name
  is the `sub` (an opaque UUID), so `resolveByEmail(sub)` would find no user and every request would fail authz.
  Setting the name attribute to `email` makes the principal name the email, so the existing filter works unchanged —
  a one-line change instead of rewriting the identity resolution.
- **Q (advanced): A real Cognito user logs in but has no app account. What happens, and why do it that way?** A:
  `CognitoOidcUserService` throws `OAuth2AuthenticationException`, so the login fails and no session is created —
  rather than letting them authenticate and then hit 403s on every call. Failing at login is cleaner, avoids orphan
  sessions, and makes "who may use this app" an explicit provisioning decision in our own user table.

## Phase 10 — Cloud Deployment on AWS: frontend "Sign in with Cognito" (slice 13) — 2026-09-19

### What we built
The SPA's browser entry point to the slice-12 Cognito BFF flow: a **"Sign in with Cognito"** button on the login
page. The existing dev-login dropdown is kept but only in dev builds. Frontend code plus a $0 Cognito client tweak;
proven end-to-end locally (all the way to the real Cognito login page). The deployed version is slice 14.

### How it works
- **The button is a full-page link, not a fetch.** It renders as `<Button component="a"
  href="/oauth2/authorization/cognito">`, so clicking it navigates the whole browser to the BFF endpoint, which
  responds with a 302 to Cognito's hosted login on another origin. A `fetch`/XHR can't follow a cross-origin login
  redirect — the page itself must navigate.
- **Dev proxy.** In dev the SPA is on :5173 and the backend on :8080, so `vite.config.ts` proxies `/oauth2` and
  `/login/oauth2` to :8080. It's scoped to `/login/oauth2`, not all of `/login`, because `/login` is the SPA's own
  React Router route — proxying all of it would hijack the app's login page. `changeOrigin:false` keeps the Host as
  localhost so the session cookie stays first-party.
- **The :5173 callback.** Because the flow runs through the :5173 proxy, the backend computes the OAuth callback with
  the :5173 host, so Cognito's app client had to be told to accept `http://localhost:5173/login/oauth2/code/cognito`.
  We added it (and the matching logout URL) in `cognito.tf`. In production this disappears — the SPA and backend are
  one origin behind the load balancer.
- **Dev-login gating.** The dev-login dropdown is wrapped in `import.meta.env.DEV`, which Vite sets to `true` for
  `npm run dev` and `false` for `npm run build`. So local dev keeps both sign-in options; the deployed bundle shows
  only "Sign in with Cognito".

### Key points
- Verified: typecheck + 184 unit tests + build all green, plus a real browser run — clicking the button redirected
  SPA → vite proxy → backend → the actual Cognito hosted login page, with
  `redirect_uri=http://localhost:5173/login/oauth2/code/cognito`. Completing the sign-in (the password) is a human
  step; the assistant doesn't authenticate.
- Applied the Cognito client change with `-target` so the deliberately torn-down ALB/ECS (still in the config) were
  not recreated.

### Interview Q&A
- **Q (beginner): Why is the login button a link instead of a button that calls an API?** A: Logging in with Cognito
  means the browser must actually travel to Cognito's login page and back. That's a top-level navigation, so the
  control is an anchor that changes `window.location`. An API call (fetch) stays on the page and can't carry the user
  through a cross-site login redirect.
- **Q (intermediate): Why proxy only `/login/oauth2` and not `/login`?** A: `/login` is the SPA's own route (our
  React login page). If the dev server proxied all of `/login` to the backend, visiting the app's login page would
  hit the backend instead of the SPA. Scoping the proxy to `/login/oauth2` (the OAuth callback) and `/oauth2` (the
  authorization request) forwards exactly the OIDC endpoints and nothing else.
- **Q (intermediate): Why did Cognito need a second callback URL for local dev?** A: The OAuth callback URL must be
  pre-registered with Cognito, and it's derived from the host the backend sees. Running the flow through the :5173 dev
  proxy makes that host `localhost:5173`, so Cognito had to accept `http://localhost:5173/login/oauth2/code/cognito`
  in addition to the :8080 one. Production uses a single origin, so it needs only its own HTTPS callback.
- **Q (advanced): How do you keep the dev-only login out of production without a runtime flag?** A: The dropdown is
  wrapped in `import.meta.env.DEV`, a Vite build-time constant — `true` under `npm run dev`, `false` under
  `npm run build`. Vite tree-shakes the dead branch out of the production bundle entirely, so the dev-login UI isn't
  just hidden, it isn't shipped.

## Phase 10 — Cloud Deployment on AWS: redeploy the Cognito-capable app + OAuth proxy (slice 14) — 2026-09-19

### What we built
Redeployed the *current* app (with the slice-12/13 Cognito code) to AWS and wired the pieces the deployed app needs
for OIDC: nginx proxying the OAuth endpoints, and the backend's Cognito config with its secret injected from Secrets
Manager. Proven on HTTP; the HTTPS layer (CloudFront) that lets login actually complete is slice 15.

### How it works
- **The images were stale.** ECR still held the slice-9 images (pre-Cognito). CI publishes images to GHCR, but ECS
  pulls from ECR, and we push to ECR by hand with `crane`. So the first step was rebuilding both images (arm64) with
  the current code and re-pushing — the backend jar now contains `application-cognito.yml` and the oauth2-client
  dependency; the frontend contains the "Sign in with Cognito" button and the new nginx config.
- **nginx OAuth proxy.** In production the SPA and backend are one origin behind nginx, so nginx must forward the
  OAuth endpoints to the backend. We added `location /oauth2/` and `location /login/oauth2/` (scoped, not all of
  `/login`) — the production mirror of the Vite dev proxy from slice 13.
- **Secret injection.** The Cognito client secret goes into Secrets Manager and is injected into the task as
  `COGNITO_CLIENT_SECRET`, exactly like the RDS password — never a plaintext value in the task definition. The ECS
  execution role's policy now lists both secret ARNs.
- **Forwarded headers.** `SERVER_FORWARD_HEADERS_STRATEGY=framework` makes Spring honor the `X-Forwarded-*` headers
  the ALB adds, so the OIDC redirect URI is built from the *external* host (the ALB/CloudFront), not the container's
  localhost. You can see it worked: the 302's redirect_uri was the ALB hostname, not `localhost`.

### Key points
- `terraform apply` = 7 added, 2 destroyed (the task definition and IAM policy are immutable-ish, so a change
  replaces them with new revisions; the ALB/ECS were recreated from the torn-down state).
- Verified: `GET /oauth2/authorization/cognito` through the deployed nginx returns a 302 to Cognito with PKCE, and the
  deployed login page shows only "Sign in with Cognito" (the production build drops the dev-login UI). Login can't
  finish yet because the callback is the ALB's HTTP URL, which Cognito won't accept — that's the HTTPS slice.
- This restarts the hourly meter; on-demand teardown still applies.

### Interview Q&A
- **Q (beginner): Why did you have to rebuild the images before deploying auth?** A: The images already in the
  registry were built before the Cognito code existed. A container ships a fixed snapshot of the app, so new code only
  reaches production when you rebuild the image and push it — otherwise the cluster keeps running the old snapshot.
- **Q (intermediate): Why does nginx need `/oauth2` and `/login/oauth2` locations?** A: The browser talks only to the
  frontend origin; nginx reverse-proxies API-ish paths to the backend. The OAuth authorization request and the
  Cognito callback are backend endpoints, so nginx must forward those two paths too — otherwise they'd fall through
  to the SPA's catch-all and 404 (or serve index.html) instead of reaching Spring Security.
- **Q (intermediate): How does the app get the Cognito client secret without it being in the image or task def?** A:
  It's stored in Secrets Manager, and the task definition references the secret's ARN in its `secrets` block; the ECS
  agent (via the execution role) fetches it at container start and injects it as an env var. The secret never appears
  in the image, the task definition JSON, or the git repo.
- **Q (advanced): Why set `SERVER_FORWARD_HEADERS_STRATEGY`, and how did you confirm it mattered?** A: Behind a load
  balancer the container sees the request as HTTP on its own hostname, but the public URL is different (and will be
  HTTPS via CloudFront). Spring needs to trust the `X-Forwarded-Proto`/`Host` headers the proxy sets to build correct
  external URLs — including the OIDC redirect URI. We confirmed it by inspecting the 302: the redirect_uri used the
  ALB's external hostname, not the container's localhost, which only happens when forwarded headers are honored.
- **Q (advanced): The deployed login still can't complete a Cognito sign-in. Why, and what's the fix?** A: The
  computed callback is `http://<alb-dns>/login/oauth2/code/cognito`, and Cognito refuses non-HTTPS callbacks for
  non-localhost hosts (and this one isn't registered). The fix is to put HTTPS in front — CloudFront gives a free
  trusted `https://…cloudfront.net` endpoint — then register that HTTPS callback and pin the redirect URI to it.

## Phase 10 — Cloud Deployment on AWS: CloudFront HTTPS, the live Cognito login (slice 15) — 2026-09-19

### What we built
The finish line of the Cognito arc: a **CloudFront distribution in front of the ALB** giving the app a free, trusted
`https://<id>.cloudfront.net` URL, so the real Cognito login completes over HTTPS on the cloud. Terraform-only — no
image rebuild. Slices 11–15 together take the app from "dev-login stand-in" to "real OIDC login on a live HTTPS URL."

### How it works
- **Why CloudFront and not ACM-on-the-ALB.** Cognito refuses non-HTTPS callbacks, and ACM won't issue a certificate
  for the ALB's AWS-owned `*.elb.amazonaws.com` hostname. CloudFront hands out a free trusted cert for its own
  `*.cloudfront.net` domain, so it's the no-domain path to HTTPS. Origin = the ALB over HTTP; viewers are forced to
  HTTPS (`redirect-to-https`).
- **Caching disabled, everything forwarded.** The app is a dynamic BFF that sets a session cookie, so we attach the
  managed `CachingDisabled` cache policy and the `AllViewer` origin-request policy — every header, cookie, and query
  string (including the OAuth `code` and `state`) is forwarded to the origin and nothing is cached.
- **Pinning the redirect URI (the key trick).** Between CloudFront and the ALB the hop is plain HTTP, so the ALB tells
  the backend `X-Forwarded-Proto: http`. Left alone, Spring would build an `http://…/login/oauth2/code/cognito`
  redirect URI, which Cognito rejects. Instead of fighting the header chain, we pin the value: the task sets
  `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_COGNITO_REDIRECTURI=https://<cloudfront>/login/oauth2/code/cognito`.
  Spring's relaxed binding maps that env var onto the hyphenated property `…cognito.redirect-uri`, overriding the
  YAML default with **no image rebuild**. That exact URL is also registered as a callback on the Cognito client.
- **Rolling-deploy gotcha.** Changing the task definition triggers a rolling ECS deploy: the new task starts while the
  old one keeps serving until it's healthy and drained. Right after apply, the old task (without the pinned env) still
  answered — so the redirect URI briefly showed `http`. It flipped to `https` only once the deployment reached
  `rolloutState=COMPLETED`. Lesson: verify a deploy after it completes, not the instant apply returns.

### Key points
- `terraform apply` = 2 added, 2 changed, 1 destroyed — CloudFront created (~3 min), the Cognito client updated in
  place, and the ECS service rolled to the new task-def revision. The ALB/ECS were not recreated (incremental change).
- Verified over HTTPS: `/actuator/health` UP through CloudFront→ALB→nginx→backend→RDS; `/oauth2/authorization/cognito`
  → 302 to Cognito with the pinned `https://…cloudfront…` redirect URI; and in the browser the app loads with a valid
  cert and "Sign in with Cognito" reaches the Cognito hosted login. The password step is the human's.
- Honest follow-ups: logout is still local-only (no RP-initiated Cognito logout); the ALB is directly reachable on
  HTTP (a hardening step would restrict it to CloudFront's origin IP ranges); and the deploy runs the `local,cognito`
  profile so seeded synthetic users back the logins (a real deployment would flip to the default profile and provision
  users without the seeder).

### Interview Q&A
- **Q (beginner): Why put CloudFront in front of a load balancer you already have?** A: To get HTTPS. The load
  balancer only had an HTTP listener and its AWS hostname can't get a certificate, but Cognito (and good practice)
  needs HTTPS. CloudFront provides a free trusted certificate on its own domain and forwards requests to the load
  balancer, so the public URL becomes HTTPS with no domain purchase.
- **Q (intermediate): Why disable caching on a CDN?** A: A CDN's job is usually to cache, but this app is dynamic and
  per-user — it sets session cookies and returns personalized data. Caching would serve one user's response to
  another. We use CloudFront purely as an HTTPS front door, so we disable caching and forward everything to the
  origin. (Static assets could be cached later as an optimization.)
- **Q (intermediate): The login redirect came back as http right after deploy, then became https. Why?** A: ECS does
  a rolling deploy — the old task keeps serving until the new one is healthy. The old task didn't have the pinned
  redirect-uri env var, so it computed http from the forwarded headers. Once the deployment reached COMPLETED and the
  new task took over, the pinned https value appeared. It's a reminder to check deploys after they finish rolling.
- **Q (advanced): Why pin the redirect URI instead of fixing the forwarded headers?** A: Behind CloudFront→ALB the
  origin hop is HTTP, so the backend legitimately sees `X-Forwarded-Proto: http`; making Spring believe it's HTTPS
  would mean injecting/trusting a proto header through two proxies, which is fragile. Pinning the redirect URI to the
  known external HTTPS URL is deterministic — it doesn't depend on any header surviving the chain — and it's a single
  env var with no rebuild. The redirect URI must match on the auth request, the token exchange, and the Cognito
  client registration, and pinning guarantees all three agree.
- **Q (advanced): What are the security caveats of this exact setup?** A: Three, all documented: logout only clears
  the app session (the Cognito session persists until it expires), the ALB is still reachable directly over HTTP so
  someone could bypass CloudFront/HTTPS (fix: restrict the ALB security group to CloudFront's origin prefix list or
  require a shared secret header), and the deployment authenticates against seeded synthetic users under the
  `local,cognito` profile rather than a real provisioning flow. None are blockers for a synthetic demo, but each is a
  real hardening item before anything resembling production.

---

## Phase 10 COMPLETE — Cloud Deployment & CI/CD: the whole arc (slices 1–15) — 2026-09-19

### The journey in one view
Phase 10 took HealthCloud from a locally-run app to a **multi-tenant healthcare platform running on real AWS with real
OIDC authentication over HTTPS** — built as fifteen small, individually-verified slices, each applied and (for the
paid ones) torn down cost-consciously. The order was deliberate: get the **artifacts** right (containers), then the
**pipeline** (CI images), then the **scaffolding** (Terraform + remote state), then **infrastructure bottom-up**
(network → database → registry → compute), then **authentication** (Cognito), then **HTTPS**.

- **1–2 Backend:** containerize (multi-stage, non-root, healthcheck) → publish to GHCR in CI.
- **3–4 Frontend:** containerize (non-root nginx, same-origin API proxy) → publish to GHCR in CI.
- **5 Terraform skeleton:** conventions + `default_tags`, no resources, $0.
- **6 Remote state:** an S3 backend (bucket created by a `bootstrap/` config), S3-native locking, no DynamoDB — the
  first real AWS resource.
- **7 VPC:** 2 public + 2 private subnets across 2 AZs, **no NAT Gateway** (~$32/mo saved).
- **8 RDS:** managed Postgres 17, private, encrypted, master password in Secrets Manager.
- **9 ECR:** two repos; images built **arm64** and pushed with **crane** (docker push timed out on a home uplink).
- **10 ECS Fargate + ALB:** the app goes **live** — one task, two containers over localhost, ARM64, DB secret injected.
- **11 Cognito user pool:** the identity provider (confidential client, hosted UI, synthetic users).
- **12 Backend BFF:** Spring Security OAuth2 maps a Cognito login → app user by email, proven locally.
- **13 Frontend button:** "Sign in with Cognito" (dev-login hidden in production builds).
- **14 Redeploy Cognito-capable app:** rebuilt/re-pushed both images, nginx OAuth proxy, Cognito secret in Secrets
  Manager — OAuth proxy proven over HTTP.
- **15 CloudFront HTTPS:** free trusted cert in front of the ALB → **the real Cognito login completes over HTTPS**.

### The cross-cutting lessons (the ones worth remembering)
- **One small verified slice at a time.** Fifteen slices, each with its own plan → build → verify → commit → (apply) →
  destroy loop, is what kept a large, multi-service cloud migration debuggable. Every slice had a concrete proof.
- **Two-gate cost discipline.** Nothing touched AWS without an explicit go-ahead, and paid resources were always
  plan-first (`$0`) then apply-second, run **on-demand** (apply → capture evidence → `destroy` back to ~$0). The whole
  phase ran on free credits with the card never charged.
- **Cost-conscious architecture is a design skill.** No NAT Gateway, no MSK, single-AZ db.t4g.micro, ARM64/Graviton,
  CloudFront's free cert instead of a bought domain, `PriceClass_100` — each was a deliberate, defensible trade-off.
- **Secrets never in code or state.** Both the RDS password and the Cognito client secret live in Secrets Manager and
  are injected into the task at runtime; nothing sensitive is committed or in the task definition.
- **The backend stays the authorization boundary.** Cognito supplies *identity*; roles, tenant, and the §21 access
  gate are still derived from the database by email — an external IdP never dictates what a user can do.
- **Know your proxies.** ARM64 vs x86 images, `docker push` vs crane on a slow link, forwarded headers behind an ALB,
  pinning the OIDC redirect_uri behind CloudFront, and verifying a deploy only after `rolloutState=COMPLETED` — each
  was a concrete gotcha with a concrete fix, now documented.

### The headline interview answer
- **Q: Walk me through how you deployed this to AWS.** A: I containerized both services and had CI publish them, then
  built the infrastructure as code in Terraform with remote state — a VPC (no NAT, for cost), a private encrypted RDS
  Postgres, an ECR registry, and ECS Fargate behind an Application Load Balancer running the app as a single ARM64
  task. Then I added real authentication: a Cognito user pool with a Spring Boot BFF (backend-for-frontend) that maps a
  Cognito login onto the app's own users and roles, a "Sign in with Cognito" button in the SPA, and CloudFront in front
  of the ALB to provide HTTPS (which Cognito requires) with a free certificate. Every step was a small verified slice,
  secrets went to Secrets Manager, and I ran it on-demand — stand it up, prove it, tear it down to ~$0 — all on free
  credits. **Phase 10 COMPLETE.**

---

## Auth-hardening: closing the dev-login bypass on the deployed app — 2026-09-19

### What we built
After Phase 10 was marked complete, I ran the project's two review subagents (`code-reviewer` and
`security-reviewer`) in parallel over the Phase 10 diff. Both independently, with high confidence,
flagged the same standout: the deployed app ran the Spring profile `local,cognito`, and the `local`
profile keeps the local dev-login endpoint (`POST /api/v1/dev-login`) alive. That endpoint is
`permitAll`, CSRF-exempt, and takes an email with **no password** — so anyone on the internet could
`POST /api/v1/dev-login?email=admin@northcare.example.org` and receive a fully authenticated
ORG_ADMIN session, **bypassing Cognito entirely**. (The app was torn down at the time, so nothing was
actually exposed — but it was a real hole on every `apply`.)

This slice fixed that plus two smaller items the reviews raised: the ALB was reachable directly over
plain HTTP (bypassing CloudFront's HTTPS), and the Cognito app client enabled two direct auth flows
the BFF never uses.

### How it works
The core insight: the deploy needs the **synthetic seed** (so a Cognito login maps to a real
`AppUser` row in the DB) but must **not** expose dev-login. Those two things were both bolted onto the
one `local` profile. The fix separates them.

**1. Split "seed" from "dev-login" via profiles.**

- The seeder now runs under either `local` or a new `demo` profile:
  ```java
  // backend/src/main/java/com/healthcloud/devdata/DevDataSeeder.java
  @Profile({"local", "demo"})
  public class DevDataSeeder implements ApplicationRunner { ... }
  ```
- `DevLoginController` is left untouched at `@Profile("local")`, so it simply does not exist as a bean
  under `demo`.
- `SecurityConfig` used to *unconditionally* allow and CSRF-exempt `/api/v1/dev-login`. That was the
  actual hole — even with the controller gone, an unconditional `permitAll` is a latent risk. It's now
  gated to the same `local` profile as the controller:
  ```java
  // backend/src/main/java/com/healthcloud/auth/SecurityConfig.java
  boolean devLoginEnabled = environment.acceptsProfiles(Profiles.of("local"));
  http.authorizeHttpRequests(auth -> {
      auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
      if (devLoginEnabled) {
          auth.requestMatchers("/api/v1/dev-login").permitAll();
      }
      auth.requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
          .anyRequest().authenticated();
  })
  .csrf(csrf -> {
      csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
          .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
      if (devLoginEnabled) {
          csrf.ignoringRequestMatchers("/api/v1/dev-login");
      }
  });
  ```
- The deployed ECS task flips its profile:
  ```hcl
  # infrastructure/terraform/ecs.tf
  { name = "SPRING_PROFILES_ACTIVE", value = "demo,cognito" }   # was "local,cognito"
  ```

Net effect: the deployed app seeds its synthetic users, offers **only** the Cognito login, and a
`POST /api/v1/dev-login` is hard-denied — it can never mint a session.

**2. Lock the ALB to CloudFront.** The ALB security group's port-80 ingress used `0.0.0.0/0`. It now
uses AWS's managed CloudFront prefix list, so only CloudFront (which forces HTTPS) can reach the ALB:
```hcl
# infrastructure/terraform/alb.tf
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}
# ingress on :80:
prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]  # was cidr_blocks = ["0.0.0.0/0"]
```

**3. Trim Cognito auth flows.** The app client's `explicit_auth_flows` dropped to just
`ALLOW_REFRESH_TOKEN_AUTH`; `ALLOW_USER_PASSWORD_AUTH` and `ALLOW_USER_SRP_AUTH` were removed. The BFF
only uses the hosted-UI authorization-code flow (which needs neither), and `admin-set-user-password`
(how the synthetic passwords are set) is an admin API unaffected by these flags.

**Verification** — a new integration test boots under the deploy shape (`demo`) and proves the fix:
```java
// backend/src/test/java/com/healthcloud/auth/DeployProfileNoDevLoginTest.java
@SpringBootTest(webEnvironment = RANDOM_PORT,
    properties = {"healthcloud.outbox.relay.enabled=false",
                  "healthcloud.kafka.consumers.enabled=false"})
@ActiveProfiles("demo")
class DeployProfileNoDevLoginTest {
  // 1) DevDataSeeder bean present, DevLoginController bean absent
  // 2) POST /api/v1/dev-login -> denied (401/403), never 200, no SESSION cookie
}
```
Full `./mvnw verify` passed (497 tests); `terraform fmt`/`validate` clean.

### Key points to remember
- **The frontend hiding a button is not security.** The SPA already hid dev-login in prod builds
  (`import.meta.env.DEV`), but the *backend* still accepted the POST. HealthCloud's own rule 4 —
  "the backend is the only security boundary" — is exactly what the reviewers held the code to.
- **A profile can carry more than one concern, and that's a trap.** `local` meant *both* "seed demo
  data" *and* "enable the password-less login." Bundling them forced the deploy to take the bypass to
  get the seed. Splitting concerns onto separate profiles (`demo` = seed only) is the fix.
- **Gate the security rule and the bean together.** The controller was already `@Profile("local")`,
  but the `SecurityConfig` `permitAll`/CSRF-exemption was unconditional. Keeping both keyed to the
  same profile means they can't drift out of sync.
- **Why the endpoint returns 403, not 401, off `local`.** With the CSRF exemption gone, a token-less
  POST is rejected by the `CsrfFilter` (403) *before* the authorization filter would return 401. Both
  are hard denials; the security property that matters is "never a 200 that sets a SESSION cookie," so
  the test accepts either and asserts no session cookie.
- **Test profile config only loads under `local`.** `src/test/resources/application-local.yml`
  (which disables the Kafka relay/consumers for the suite) is profile-scoped, so a `@ActiveProfiles("demo")`
  test doesn't get it — I disabled Kafka via `@SpringBootTest(properties = ...)` instead, or the app
  would try to reach a broker at startup.
- **Terraform changes are staged, not applied.** The app is torn down (~$0). `fmt`/`validate` prove the
  config is valid; the SG and Cognito-client changes take effect on the next `apply`.
- **The ALB lock isn't airtight.** The CloudFront prefix list admits *any* AWS account's CloudFront
  distribution. Fully closing it needs a per-distribution secret header (origin-verify) that the ALB
  listener checks — noted as a follow-up, not built.

### Failures and how we fixed them
- **The test first failed on 401 vs 403.** My initial assertion expected `401` from
  `POST /api/v1/dev-login`; the app returned `403`. Root cause: dev-login is no longer CSRF-exempt
  under `demo`, so the token-less POST is a CSRF denial (403) before it reaches the authorization
  layer. Fix: assert the response is a denial (401 or 403), never 200, and that no `SESSION` cookie is
  set — testing the real security property instead of a specific status code.
- **`mvn verify` failed with a duplicate-class error.** Symptom:
  `com/healthcloud/TestcontainersConfiguration 2 (wrong name: ...)` and BUILD FAILURE. Root cause: a
  stray `TestcontainersConfiguration 2.class` under `backend/target/` (the known " 2" duplicate-file
  gotcha, likely from a file-sync copy) — not a code problem. Fix: `./mvnw clean verify`, which passed
  (497 tests).
- **A `tail`-piped exit code masked the first failure.** I ran `./mvnw verify | tail -60`; the shell
  reported exit 0 (from `tail`), but Maven had actually failed. Lesson: capture the real exit code
  (`; echo EXIT=$?` on the maven command, not the pipe) or grep the output for `BUILD FAILURE`.

### Interview Q&A

#### 1. Beginner

**Q: What was the security vulnerability?**
A: The deployed app kept a development-only login endpoint (`/api/v1/dev-login`) publicly reachable.
It accepted just an email address — no password, no MFA — and returned an authenticated session,
including an admin one. So anyone could impersonate any user and bypass the real login (Cognito).

**Q: What is a Spring "profile" and how did it cause this?**
A: A profile is a named set of beans/config Spring activates at runtime (via
`SPRING_PROFILES_ACTIVE`). The `local` profile bundled two things — seeding demo data *and* enabling
dev-login. The deploy needed the seed, so it turned on `local`, which dragged in the dev-login bypass.

**Q: How did you fix it at a high level?**
A: I split the two concerns. Seeding now also runs under a new `demo` profile; dev-login stays only
under `local`. The deployed app runs `demo,cognito` — it gets the seed and Cognito, but not the
bypass.

**Q: Why isn't hiding the button in the frontend enough?**
A: The browser is untrusted. Even with no button, an attacker can POST directly to the endpoint. Only
a backend check actually stops it — the frontend can only improve UX.

#### 2. Intermediate

**Q: Why gate the `SecurityConfig` rule on the profile too, when the controller was already
`@Profile("local")`?**
A: Defense in depth and preventing drift. An unconditional `permitAll` + CSRF-exemption for a path is
a latent hole even if today no controller serves it — a future controller on that path would inherit
it. Keying the security rule to the same profile as the controller keeps them consistent.

**Q: Why does the endpoint return 403 rather than 401 on the deployed profile?**
A: Filter order. Once dev-login is no longer in the CSRF `ignoringRequestMatchers`, a token-less POST
is rejected by the `CsrfFilter` (403) before the authorization filter (which would give 401) runs.
Both are denials; the test asserts "denied and no session," not a specific code.

**Q: Why keep seeding on the deployed app at all — isn't seeded data itself a smell?**
A: The whole project is synthetic-data-only, so seeded users aren't sensitive. And the Cognito login
maps an authenticated identity (by email) onto an existing `AppUser` for roles/tenant; without seeded
users there'd be nothing to map to. So the seed is required for the Cognito demo — it's the *login
bypass*, not the seed, that was the problem.

**Q: Why trim the Cognito `explicit_auth_flows`?**
A: `ALLOW_USER_PASSWORD_AUTH`/`ALLOW_USER_SRP_AUTH` let a caller trade a username+password directly
for tokens via the Cognito API. The BFF only uses the hosted-UI authorization-code flow plus refresh,
so those flows are unused attack surface. Removing them is least-privilege; setting passwords still
works because `admin-set-user-password` is a separate admin API.

#### 3. Advanced

**Q: Does locking the ALB SG to the CloudFront prefix list fully prevent origin bypass?**
A: No. The managed prefix list covers *all* CloudFront IP ranges, so another AWS account's
distribution could still hit the ALB. To fully close it, CloudFront should add a secret custom header
and the ALB listener rule should require it (origin-verify), or use VPC origins. We documented that as
a follow-up rather than building it this slice.

**Q: How would you test a profile-conditional security rule without a full app boot?**
A: You can slice-test the `SecurityFilterChain` with a `@WebMvcTest`/`MockMvc` under different
`@ActiveProfiles`, or unit-test the boolean decision. We chose a `RANDOM_PORT` integration test under
`demo` because it also proves bean wiring (seeder present, controller absent) and the real HTTP
denial, end to end — highest confidence for a security fix.

**Q: The deployed app's SESSION cookie isn't forced `Secure`. Is that safe now?**
A: It's acceptable because the only viewer-facing hop is CloudFront, which is `redirect-to-https`, so
the browser always talks HTTPS. The CloudFront→ALB hop is HTTP inside AWS. Forcing `Secure` would be
strictly better defense-in-depth; the practical exposure is closed by the HTTPS-only viewer protocol
plus the ALB now being unreachable except through CloudFront.

**Q: Why did the `demo`-profile test need explicit Kafka-disabling properties?**
A: The suite-wide `application-local.yml` that stops the outbox relay and Kafka consumers only loads
under the `local` profile. A `demo` test doesn't inherit it, so without overriding
`healthcloud.outbox.relay.enabled=false` and `healthcloud.kafka.consumers.enabled=false` the app would
try to connect to a broker at startup and the test context would fail to load.

---

## UI/Design track — the "Care Constellation" redesign (slices 1–9) — 2026-09-19

### What we built
Up to this point the app was **plain default Material UI** — `main.tsx` called `createTheme()` with
zero customization, so every page was stock-blue MUI. The goal for this track was a **distinctive,
cohesive, memorable design** a recruiter would pause on, while staying simple and highly readable
(data-dense healthcare screens). We designed an original identity — **"Care Constellation"** (a
network/constellation motif, teal + indigo, a dark bespoke "hero" surface on entry pages and a clean
light app underneath) — and rolled it out in nine small verified slices:

1. **Design-system foundation** — the theme, fonts, brand mark, and the animated constellation canvas.
2. **App shell** — a grouped, role-gated sidebar (permanent on desktop, drawer on mobile).
3. **Login hero** — a dark, animated Constellation hero (the deployed URL's first impression).
4. **Dashboard** — a hero band + real role-gated "at a glance" counts + a role-aware launchpad.
5. **Work-queue & table polish** — soft tinted status chips + refined tables + a shared empty state.
6. **Detail-page finish** — a unified back-link across all detail pages.
7. **Native-select fix** — the garbled "Select a patient" dropdown label overlap.
8. **Depth & color pass** — a subtle canvas wash, richer card shadows, a live dashboard.
9. **Light / Dark / System theme mode** — device-default + a toggle + a full dark scheme.

Slices 1–6 established the identity; slices 7–9 were driven by **direct user feedback** on screenshots
(a real dropdown bug, "too plain white," and "give viewers light/dark/system").

### How it works

**The single styling source is the MUI theme** (`frontend/src/theme/index.ts`). Nothing is styled with
per-page CSS; instead we set palette tokens, typography, and **global component overrides**, so one
edit restyles every page at once. Identity tokens: teal primary `#0d9488`, indigo accent `#4f46e5`,
soft `#f6f8fb` background, **Space Grotesk** headings / **Inter** body / **IBM Plex Mono** for
codes·IDs·money (exported as `MONO`), radius 12.

**The hybrid light/dark idea.** The app itself is light and readable, but the *entry surfaces* (login,
dashboard hero band) are a bespoke **dark** "Constellation" world. Those dark tokens are exported
separately as `constellation` (bg `#070b18`, teal `#5eead4`, indigo `#7c9cff`, a gradient) and used
directly by the hero components — they are **not** the global palette. This is why, when we later added
full dark mode (slice 9), the hero surfaces needed no change: they were always dark by design.

**The animated background** (`components/ConstellationBackground.tsx`) is a `<canvas>` that draws nodes
+ connecting lines. It is `aria-hidden`, resize-aware, renders **static under
`prefers-reduced-motion`**, and **bails cleanly in jsdom** (`if (!canvas || !parent || !ctx) return`)
so tests don't crash on the unimplemented canvas API.

**The shell** (`layout/AppLayout.tsx`) is a grouped sidebar (Care / Claims & coverage / Governance),
permanent on desktop and a temporary drawer + hamburger on mobile. The desktop/mobile decision uses:
```tsx
const isDesktop = useMediaQuery(theme.breakpoints.up('md'), { defaultMatches: true })
```
`defaultMatches: true` is important: jsdom has no `matchMedia`, so without it the permanent sidebar
wouldn't render in tests and the accessibility test's single `<nav aria-label="Primary">` landmark
assertion would fail.

**The dashboard** (`pages/HomePage.tsx`) shows **real, role-gated counts** — never fabricated numbers
(project rule 2). It fans out over the paged endpoints with `useQueries`, asking each for `size: 1` and
reading `totalElements`:
```tsx
count: () => api.listClaimsPage({ size: 1 }).then((p) => p.totalElements)
```
A dash (`—`) is shown while loading or on error, so we never invent a value.

**Soft status chips (slice 5, global).** A single `MuiChip` override turns every filled colored chip
across all eight queues *and* every detail page into a soft-tinted chip (light-tint background + strong
text) instead of a solid fill.

**Depth pass (slice 8).** All theme-level so the whole app lifts at once: a fixed radial **canvas
wash** on the `body` (`MuiCssBaseline`), a soft layered **card shadow**, and a faint **zebra** on even
table rows (the `<thead>` row is the sole child of its section → `nth-of-type(1)` → stays untinted).
The dashboard stat cards additionally got a brand-gradient top accent, a teal number, and a hover lift.

**Theme mode (slice 9) — the big one.** We used MUI 9's built-in multi-scheme support rather than a
hand-rolled context:
```ts
createTheme({
  cssVariables: { colorSchemeSelector: 'class' },
  colorSchemes: { light: { palette: {…} }, dark: { palette: {…} } },
  …
})
```
- `main.tsx` sets `<ThemeProvider theme={theme} defaultMode="system">` — **System follows the device's
  `prefers-color-scheme`** automatically, and an explicit choice is persisted in `localStorage`
  (`mui-mode`) by MUI.
- `components/ThemeToggle.tsx` is a Light/Dark/System `ToggleButtonGroup` backed by `useColorScheme()`
  in the sidebar footer. It switches instantly (CSS variables, no reload).
- The **dark scheme echoes the hero** (deep navy `#070b18` surfaces, brighter teal `#2dd4bf` / indigo
  `#818cf8`) so the whole app feels like the Constellation world in dark.
- Scheme-varying overrides use **theme vars** + **`theme.applyStyles('dark', …)`** so they adapt per
  scheme. The soft chip tint uses the CSS-variable channel token so it follows the active scheme without
  running `alpha()` on a variable string:
  ```ts
  backgroundColor: `rgba(var(--mui-palette-${color}-mainChannel) / 0.14)`,
  color: `var(--mui-palette-${color}-dark)`,
  ...theme.applyStyles('dark', {
    backgroundColor: `rgba(var(--mui-palette-${color}-mainChannel) / 0.22)`,
    color: `var(--mui-palette-${color}-light)`,
  }),
  ```
- **No light-flash:** a tiny inline script in `index.html` runs *before* React mounts and sets the
  `<html>` color-scheme class from stored mode / device, matching MUI's `class` selector:
  ```html
  <script>(function(){try{var m=localStorage.getItem('mui-mode')||'system';
    var d=m==='dark'||(m==='system'&&matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.classList.add(d?'dark':'light')}catch(e){}})()</script>
  ```

**The dropdown fix (slice 7).** The bug: `<TextField select slotProps={{ select: { native: true } }}>`
left the floating label sitting mid-field, overlapping the option text (garbled letters), because a
native `<select>` always shows text but MUI didn't shrink the label. The fix — the MUI-recommended
pattern for native selects — is one flag on every such field:
```tsx
slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
```

### Key points to remember
- **Style through the theme, never per-page CSS.** Global component overrides (`MuiChip`,
  `MuiTableCell`, `MuiCard`, `MuiCssBaseline`, `MuiAppBar`) restyle the whole app from one file — this
  is what made a large multi-page redesign safe and fast.
- **Separate the always-dark hero tokens from the app palette.** Exporting `constellation` as its own
  object (not the theme palette) meant the hero surfaces were mode-independent, so adding dark mode
  later touched only the *app* surfaces.
- **For dark mode in MUI 9, use `cssVariables` + `colorSchemes`, not a manual context.** You get
  system-default, localStorage persistence, and instant (re-render-free) switching for free. Manual
  `useState` + two `createTheme`s + `matchMedia` is more code and easy to get subtly wrong.
- **Write scheme-aware overrides with `theme.applyStyles('dark', …)` and CSS-var channel tokens
  (`--mui-palette-<color>-mainChannel`), never a hardcoded hex.** `alpha(theme.vars.palette.x.main, …)`
  does **not** work under CSS variables because the value is a `var(...)` *string*; the `mainChannel`
  token (space-separated RGB) exists precisely so you can do `rgba(var(--…-mainChannel) / 0.14)`.
- **`useColorScheme()` returns `mode: undefined` without a CSS-vars provider** (e.g. in a unit test that
  doesn't wrap in `ThemeProvider`). Guarding `if (!mode) return null` in `ThemeToggle` means the toggle
  renders nothing in tests — so none of the 187 tests needed changing.
- **Prevent the dark-mode flash with a pre-hydration inline script**, not a React effect (an effect runs
  after first paint — too late).
- **A native `<select>` always needs `inputLabel: { shrink: true }`** — its box is never empty, so the
  label must always be shrunk.
- **Real numbers only (rule 2).** The dashboard reads `totalElements` from `size:1` queries and shows a
  dash while loading/erroring — no invented counts.
- **jsdom gotchas to design around:** no `matchMedia` (→ `defaultMatches:true` for the sidebar; the
  color-scheme hook returns `undefined`), and no canvas 2D context (→ the constellation canvas bails).
- **Accessibility held throughout:** the axe-core gate (`expectNoAxeViolations`), one `<h1>` per page
  (`PageHeading`), the skip link, and the `<nav aria-label="Primary">` landmark all stayed green across
  every slice, and dark mode was checked for AA contrast in the browser (axe can't check contrast in
  jsdom).

### Failures and how we fixed them
- **Slice 2 — `primaryTypographyProps` doesn't exist in MUI 9.** `ListItemText` moved to
  `slotProps={{ primary: { sx: {…} } }}`; also a `fontSize` passed as a typography prop was invalid and
  had to be nested under `sx`.
- **Slice 2 — axe "list: `<ul>` must only directly contain `<li>`."** A bare `ListItemButton` renders a
  `<div>` as a direct child of the nav `<ul>`. Fixed by wrapping each in
  `<ListItem disablePadding><ListItemButton …/></ListItem>`.
- **Slice 5/6 — unused imports after mechanical rollouts** (`Typography`, `Link`/`RouterLink`) broke the
  `noUnusedLocals` typecheck; removed them.
- **Slice 9 — `theme.vars` is possibly `undefined` in the `MuiCssBaseline` callback.** The base `Theme`
  type marks `vars` optional (component-slot callbacks infer a non-optional theme, but CssBaseline's
  didn't). Rather than a non-null assertion, we used a literal light value + `theme.applyStyles('dark',
  {...})` for the dark background, avoiding `theme.vars` in that one spot. Also had to annotate the
  callback param (`({ theme }: { theme: Theme })`) because it was otherwise implicitly `any`.
- **Slice 7 — the fix was inconsistent to begin with.** 5 of 21 native selects (ones added in later
  feature slices) already had the shrink flag; the mechanical `replace_all` of the exact string only
  touched the 16 that lacked it, leaving the good ones alone.
- **Depth-pass console noise (slice 8).** The browser console showed stale 401/500 errors, which for a
  moment looked alarming — but the current page's API calls were all 200, and CSS changes can't cause a
  500. They were leftovers from before login and from a mid-QA Postgres restart earlier in the session.
  Lesson: check whether error logs are *current* (look at the latest network requests) before reacting.
- **No test breakage overall.** Every slice kept typecheck + 187 tests + build green and CI green after
  each push — the theme-first approach plus the `!mode` guard meant the big theme changes didn't ripple
  into the test suite.

### Interview Q&A

#### 1. Beginner

**Q: What is a design system, and where does it live in this app?**
A: A single source of styling decisions (colors, fonts, spacing, component looks) so the UI is
consistent and changeable from one place. Here it's the MUI theme in `frontend/src/theme/index.ts`,
which sets palette tokens, typography, and global component overrides. Pages don't carry their own CSS.

**Q: How does the app decide light vs dark on first visit?**
A: `defaultMode="system"` makes it follow the visitor's device setting (`prefers-color-scheme`). If they
later pick Light or Dark from the toggle, that choice is saved in `localStorage` and used next time.

**Q: What was the "garbled dropdown" bug?**
A: The native `<select>` fields showed the field label sitting on top of the option text. A native
select always shows text in its box, but MUI left the label un-shrunk, so they overlapped. Adding
`inputLabel: { shrink: true }` pins the label above the box.

**Q: Why show a dash instead of a number on the dashboard sometimes?**
A: The counts are real, fetched from the backend. While a request is loading or if it errors, we show
`—` rather than guessing — the project forbids stating numbers we haven't actually measured.

#### 2. Intermediate

**Q: Why use MUI's `colorSchemes` + CSS variables instead of a custom dark-mode context?**
A: MUI's built-in support gives system-default detection, localStorage persistence, and instant
switching (it swaps CSS variables rather than re-rendering the tree with a new theme object) for very
little code. A hand-rolled context would re-implement all of that and is easy to get subtly wrong
(flash, persistence, missed surfaces).

**Q: How do you write a component override that differs between light and dark?**
A: With `theme.applyStyles('dark', { …dark styles… })`, which scopes those styles under the dark
selector, and by referencing theme variables (`theme.vars.palette.…`) for values that already differ
per scheme. You avoid hardcoded hexes so both schemes stay correct from one definition.

**Q: Why can't you use `alpha(theme.vars.palette.primary.main, 0.12)` under CSS variables?**
A: Because with CSS variables the palette value is a string like `var(--mui-palette-primary-main)`, not
a color `alpha()` can parse. MUI generates a `mainChannel` token (space-separated RGB channels) for
exactly this, so you write `rgba(var(--mui-palette-primary-mainChannel) / 0.12)`.

**Q: How did you keep 187 tests passing through a full theme rewrite?**
A: The changes were almost entirely at the theme/override level (behavior unchanged), and the one new
interactive piece — `ThemeToggle` — guards `if (!mode) return null`. Tests don't wrap components in a
CSS-vars provider, so `useColorScheme` returns `undefined` there and the toggle renders nothing,
leaving existing assertions untouched.

**Q: Why is the animated background safe for accessibility and tests?**
A: It's `aria-hidden`, renders static under `prefers-reduced-motion`, and returns early if there's no
canvas/parent/2D context — which is the case in jsdom — so it neither distracts users who opt out nor
crashes the test environment.

#### 3. Advanced

**Q: How do you prevent a light-then-dark flash in a client-rendered SPA?**
A: You must set the color scheme on `<html>` *before* the app's JS renders. A React effect runs after
first paint, so it flashes. The fix is a tiny synchronous inline script in `index.html` that reads the
stored mode (or the device preference) and adds the matching class to `<html>` immediately, mirroring
MUI's storage key and `class` selector so MUI then agrees with it on mount.

**Q: The dark scheme reuses the hero's colors. Why did that make the feature cheaper to build?**
A: Because the entry surfaces (login, dashboard hero) were built from a *separate* `constellation`
token object, not the theme palette — they were always dark. So adding dark mode only had to define a
dark palette + adapt the *app* surfaces (sidebar, cards, tables, wash, chips); the hero surfaces
needed zero changes and blend seamlessly into the dark app.

**Q: You verified system-mode "follows the device." How, without changing your OS setting?**
A: In the browser pane I set the toggle to System, then emulated `prefers-color-scheme` as light and as
dark; the app switched each way. I also proved persistence (set Dark, reload, still dark, no flash) and
that `localStorage['mui-mode']` and the `<html>` class matched. Contrast was eyeballed on core screens
because axe can't evaluate contrast under jsdom.

**Q: What's the risk of styling everything through global overrides, and how did it show up?**
A: A global override can have unintended reach. The zebra stripe is a good example: `nth-of-type(even)`
on `MuiTableRow` could have tinted a `<thead>` row — but the head row is the only child of its
`<thead>`, so it's `nth-of-type(1)` (odd) and stays clean. You have to reason about selector scope
across *every* table in the app, not just the queue you're looking at. The payoff is that one line
improved all eight queues plus every detail-page table at once.

**Q: If you revisited this, what would you refactor?**
A: Extract a `NativeSelectField` wrapper so a native select can't forget the `shrink` flag (a recurring
footgun), and do a full page-by-page dark-mode sweep of the less-trafficked screens to catch any stray
hardcoded color that the global overrides didn't cover — both noted as follow-ups.

---

## Phase 11 — Observability & Recovery (slices 1–7) — 2026-09-20

### What we built

The instrumentation and recovery layer that makes HealthCloud *operable*: you can see what it's doing, get
told when something's wrong, and recover the data if it's lost. Seven slices:

1. **Metrics foundation** — Micrometer + a Prometheus registry expose `/actuator/prometheus`, plus the first
   domain counter (`healthcloud_adjudications_total`).
2. **Dashboards** — a local Prometheus + Grafana stack (docker-compose `observability` profile) with an
   auto-provisioned "HealthCloud Overview" dashboard; HTTP latency histograms enabled so p95 is computable.
3. **Distributed tracing** — Micrometer Tracing + OpenTelemetry export spans over OTLP to a local Jaeger; a
   custom `adjudicate-claim` span nests under the HTTP span; trace ids appear in logs.
4. **Health & readiness probes + a custom health indicator** — a liveness/readiness/root-health split, plus an
   `OutboxHealthIndicator` that surfaces the relay backlog.
5. **Alert rules** — five Prometheus alerting rules over real metrics, and a gauge that makes the outbox backlog
   alertable.
6. **Backup & restore drill** — a `pg_dump` script and an automated restore *drill* that proves a backup is
   usable.
7. **Runbooks** — operational playbooks (`docs/runbooks/`) tying it all together, with each alert linking to its
   response section.

Everything is local-first and **$0** — no AWS observability was wired (a documented follow-up), consistent with
the project's AWS-cost boundary.

### How it works

**Slice 1 — metrics.** `spring-boot-starter-actuator` provides the metrics autoconfiguration; adding
`io.micrometer:micrometer-registry-prometheus` (runtime scope) makes Boot expose `/actuator/prometheus` with
auto-instrumented JVM / HTTP / HikariCP metrics. `application.yml` adds `prometheus,metrics` to the actuator
exposure and a common label `management.metrics.tags.application: healthcloud`. The first *domain* metric lives in
`AdjudicationService`: a counter incremented only when the transaction actually commits —

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() {
        meterRegistry.counter("healthcloud.adjudications", "outcome", outcome, "type", type).increment();
    }
});
```

Naming is `healthcloud.adjudications` **without** `.total` — the Prometheus registry appends `_total` itself.
`SecurityConfig` permits `/actuator/prometheus` without a session **only under the `local` profile** (so a local
scraper works); the deployed `demo,cognito` app keeps it authenticated (asserted by `DeployProfileNoDevLoginTest`).

**Slice 2 — dashboards.** A `docker compose --profile observability up -d prometheus grafana jaeger` stack.
`infrastructure/observability/prometheus.yml` scrapes `host.docker.internal:8080/actuator/prometheus` (works
whether the app runs on the host or as a container). Grafana is provisioned from files
(`grafana/provisioning/datasources` + `dashboards`) with a pinned datasource uid `prometheus` and the
`healthcloud-overview.json` dashboard. To make p95 latency computable we enabled request histograms:
`management.metrics.distribution.percentiles-histogram.http.server.requests: true` (this publishes
`http_server_requests_seconds_bucket`, which `histogram_quantile(0.95, …)` needs).

**Slice 3 — tracing.** In Boot 4 tracing is **opt-in** and modularized. We depend on the Boot module
`spring-boot-micrometer-tracing-opentelemetry` **plus an explicit `io.micrometer:micrometer-tracing-bridge-otel`**
(compile scope) + `opentelemetry-exporter-otlp` + `org.aspectj:aspectjweaver` (for `@Observed`; Boot 4 dropped
`spring-boot-starter-aop`). Config: `management.tracing.sampling.probability: 1.0` locally, the OTLP endpoint at
`management.opentelemetry.tracing.export.otlp.endpoint`, and the log pattern carries the ids:
`"%5p [cid=%X{correlationId:-} trace=%X{traceId:-}/%X{spanId:-}]"`. A custom span is one annotation plus the aspect
bean:

```java
@Observed(name = "healthcloud.adjudicate", contextualName = "adjudicate-claim")
public Adjudication adjudicate(...) { ... }
// ObservabilityConfig registers: new ObservedAspect(observationRegistry)
```

Kafka `template`/`listener` `observation-enabled: true` propagates the trace context through Kafka headers.

**Slice 4 — probes.** `management.endpoint.health.probes.enabled: true` exposes `/actuator/health/liveness` and
`/actuator/health/readiness`. The key config is a health *group*:

```yaml
management.endpoint.health.group.readiness.include: readinessState,db
```

so readiness goes DOWN when Postgres is unreachable (the pod leaves the load balancer), while **liveness stays
`livenessState` only** (a DB blip must never restart the process). The custom indicator implements the Boot-4
interface (`org.springframework.boot.health.contributor.HealthIndicator`) and reports the outbox backlog as the
`outbox` component on root health only — degraded, not dead:

```java
Status status = pending > maxPending ? Status.OUT_OF_SERVICE : Status.UP;   // never DOWN
```

It is deliberately **not** in the readiness group (a backlog doesn't stop serving requests). The container
`HEALTHCHECK` moved to `/actuator/health/liveness` so only a real process failure restarts the container.

**Slice 5 — alerts.** `infrastructure/observability/alert-rules.yml` defines five rules (`BackendTargetDown`,
`OutboxBacklogHigh`, `HighHttp5xxRate`, `HighRequestLatencyP95`, `JvmHeapHigh`), loaded via `rule_files` in
`prometheus.yml` and mounted into the container. To alert on the outbox backlog we promoted slice 4's health
signal into a metric — a Micrometer **gauge**:

```java
Gauge.builder("healthcloud.outbox.pending", repo, r -> (double) r.countByPublishedAtIsNull())
     .register(meterRegistry);   // renders healthcloud_outbox_pending
```

No Alertmanager locally: Prometheus evaluates rules and exposes their state (`/api/v1/rules`, `/api/v1/alerts`)
without it — Alertmanager is only the routing/notification layer (a follow-up).

**Slice 6 — backup & restore drill.** `scripts/db-backup.sh` runs `pg_dump -Fc` **inside** the postgres
container (no host psql needed) to `var/backups/` (git-ignored). `scripts/db-restore-drill.sh` rehearses recovery
without touching the live DB: back up → create a scratch DB → `pg_restore` into it → compare `count(*)` of every
`public` table between source and restored → drop the scratch DB → PASS/FAIL. The dump includes
`flyway_schema_history`, so a restored DB passes `ddl-auto: validate`.

**Slice 7 — runbooks.** `docs/runbooks/README.md` (stack overview, the three-signals model, the triage workflow)
and `docs/runbooks/alert-response.md` (one section per alert: meaning · confirm · causes · recovery). Each alert
rule gained a `runbook` annotation pointing to its section, so a firing alert links to its playbook.

### Key points to remember

- **Count only committed work.** Domain metrics increment on `afterCommit`, not inline, so a rolled-back
  transaction is never counted. This is the template for any future domain counter.
- **Counter vs gauge.** Adjudications are a monotonic **counter** (`_total`, only ever goes up → rate()). The
  outbox backlog is a **gauge** (goes up and down as the relay drains) — the gauge supplier runs a cheap COUNT at
  each scrape.
- **PHI-free metrics (rule 5).** Metrics/traces/alerts carry counts, coded outcomes, and ids only — never patient
  data. Also watch **cardinality**: tag by low-cardinality dimensions (outcome/type), never by patient id.
- **Liveness ≠ readiness ≠ root health.** Liveness = "restart me?" (process only). Readiness = "send me traffic?"
  (includes the DB). Root `/actuator/health` = the monitoring aggregate (may go 503 to *signal* degradation
  without killing anything). Put a dependency in readiness only if the app genuinely can't serve without it.
- **Thresholds are targets, not measured SLOs (rule 2).** The alert numbers (5% 5xx, p95 > 1s, 90% heap) are
  demo targets; we don't claim them as measured guarantees.
- **The three signals join by id.** A response's `X-Correlation-Id` → `grep 'cid='` in logs → its `traceId` →
  the trace in Jaeger. This is the whole triage workflow, and why the log pattern carries all three.
- **Boot 4 moved/renamed things.** Health contributors are under `org.springframework.boot.health.contributor.*`;
  tracing is opt-in via dedicated modules; the OTLP tracing property was renamed (see failures).
- **A backup you have never restored is not a backup.** We ship the *drill*, not just the dump — the restore is
  the thing that's actually verified.
- **Honest scope:** AWS has no Prometheus/Jaeger scrape wired, Alertmanager routing is deferred, and RDS
  PITR/snapshot DR is an on-demand, approval-gated follow-up. All documented, none pretended-done.

### Failures and how we fixed them

- **`/actuator/prometheus` returned 500 in tests** (`NoResourceFoundException`). Root cause: Spring Boot disables
  metrics exporters in `@SpringBootTest` by default. Fix: add `@AutoConfigureMetrics` to the metrics endpoint
  test. (Runtime was always fine — confirmed with a live `curl`.)
- **p95 latency panel had no data.** `http_server_requests_seconds_bucket` isn't published by default. Fix: enable
  `percentiles-histogram.http.server.requests: true`.
- **Tracing produced no spans / didn't export (slice 3, the hard one).** A chain of Boot-4 changes:
  `spring-boot-starter-aop` doesn't exist in Boot 4.1 (used `org.aspectj:aspectjweaver`); tracing autoconfig
  wouldn't activate until we added the dedicated `spring-boot-micrometer-tracing-opentelemetry` module;
  `@ConditionalOnClass(OtelTracer)` still failed because `micrometer-tracing-bridge-otel` was only a runtime
  transitive → we declared it explicitly at compile scope; and the **root cause of "spans created but never
  exported"** was Boot 4.1 renaming the property to `management.opentelemetry.tracing.export.otlp.endpoint` (the
  old `management.otlp.tracing.endpoint` silently no-ops) — found via the `--debug` condition-evaluation report.
  We also *removed* `spring-boot-starter-opentelemetry`: its full SDK autoconfigure built a competing tracer
  provider (no exporter) and turned on an unwanted OTLP metrics registry.
- **Health-indicator test hit a foreign-key violation.** Inserting an outbox row with a random `organizationId`
  violated the FK to `organization`. Fix: use a **seeded** org id (`organizationRepository.findAll().get(0)`).
- **The restore drill only checked ONE table.** Classic bash trap: `docker compose exec` inside a `while read`
  loop consumes the loop's stdin (the table list), so the loop exited after the first iteration. Fix: detach
  stdin on the in-container helpers (`</dev/null`) since they use `-c` and need no stdin. Documented in the script
  and runbook so it can't recur.

### Interview Q&A

#### 1. Beginner

**Q: What are the "three pillars" of observability?**
A: Metrics (aggregate numbers over time — rates, latencies, gauges), logs (discrete event records), and traces
(the path of a single request across components). HealthCloud has all three: Prometheus/Grafana for metrics,
structured logs with correlation + trace ids, and Jaeger for traces.

**Q: What is `/actuator/prometheus`?**
A: A Spring Boot Actuator endpoint (enabled by the `micrometer-registry-prometheus` dependency) that exposes the
app's metrics in Prometheus text format, so a Prometheus server can scrape them on a schedule.

**Q: What's the difference between a counter and a gauge?**
A: A counter only ever increases (e.g. total adjudications) — you look at its *rate*. A gauge goes up and down
(e.g. the current number of unpublished outbox events) — you look at its *current value*.

**Q: Liveness vs readiness probe — what's the difference?**
A: Liveness answers "is the process alive?" — if it fails, restart it. Readiness answers "can it serve traffic
right now?" — if it fails (e.g. the database is down), stop routing traffic to it but don't restart it.

**Q: What's an alerting rule?**
A: A Prometheus expression plus a duration (`for:`); when the expression is true for that long, the alert fires.
Example: `up == 0 for 1m` means "the target has been unreachable for a minute."

#### 2. Intermediate

**Q: Why increment domain metrics in `afterCommit` instead of inline?**
A: So we only count work that actually happened. If the transaction rolls back, an inline increment would have
over-counted. `TransactionSynchronizationManager.registerSynchronization(...).afterCommit()` runs the increment
only after a successful commit.

**Q: Why does your custom outbox indicator report `OUT_OF_SERVICE` instead of `DOWN`, and why isn't it in the
readiness group?**
A: A relay backlog is a degraded async pipeline, not a dead app — the app still serves user requests fine.
`OUT_OF_SERVICE` signals degradation on the root health aggregate (an operator/alert signal) without implying the
process is dead. It's excluded from readiness because readiness gates *traffic*; pulling the pod out of the load
balancer over a backlog would be wrong. And the container health check targets liveness, so it can't trigger a
restart either.

**Q: Why did you enable request histograms, and what do they let you do?**
A: `percentiles-histogram.http.server.requests: true` publishes latency bucket counters. With buckets you can
compute quantiles server-side across instances using `histogram_quantile(0.95, rate(..._bucket[5m]))` — that's how
the p95 latency alert and dashboard work.

**Q: Why alert rules but no Alertmanager?**
A: Prometheus itself evaluates rules and exposes their state (firing/pending) via its API — that's enough to prove
the rules are correct and to see them in the UI. Alertmanager is the *routing/notification* layer (email, Slack,
PagerDuty), which needs external services and secrets — out of scope for a local, synthetic, verifiable slice, so
it's a documented follow-up.

**Q: What makes a "restore drill" different from just taking a backup?**
A: A backup you've never restored might be corrupt, incomplete, or unrestorable — you don't actually know until
you try. The drill restores the dump into a scratch database and verifies fidelity (every table's row count
matches the source), so the backup is *proven* usable. "A backup you have never restored is not a backup."

**Q: How do you connect a metric alert back to a specific failing request?**
A: By id. Every log line carries `[cid=<correlationId> trace=<traceId>/<spanId>]`, and the API echoes
`X-Correlation-Id`. From a failing response you grab the correlation id, grep the logs for it to get the trace id,
and open that trace in Jaeger to see exactly where it failed or slowed down.

#### 3. Advanced

**Q: Walk through debugging "spans are created but never exported" in Spring Boot 4.**
A: Several Boot-4 changes compounded. Tracing is opt-in via dedicated modules now, so actuator alone doesn't wire
it — we added `spring-boot-micrometer-tracing-opentelemetry`. The OTel bridge (`OtelTracer`) was only a runtime
transitive, so the autoconfig's `@ConditionalOnClass(OtelTracer)` didn't match — we declared
`micrometer-tracing-bridge-otel` explicitly at compile scope. Even then nothing exported: the actual root cause
was that Boot 4.1 **renamed** the OTLP tracing endpoint property to
`management.opentelemetry.tracing.export.otlp.endpoint`; with the old Boot-3 name the exporter silently defaulted
and no-op'd. We found it via the `--debug` condition-evaluation report. We also removed
`spring-boot-starter-opentelemetry`, which was building a second, competing tracer provider (with no exporter)
and an unwanted OTLP metrics registry.

**Q: How does a trace follow a claim adjudication from the HTTP request into the Kafka consumer?**
A: The HTTP request starts a trace; the `@Observed(contextualName="adjudicate-claim")` method becomes a child
span (via the `ObservedAspect`). When the outbox relay publishes to Kafka, `observation-enabled: true` on the
template injects the trace context into Kafka headers; on the consumer side `observation-enabled: true` on the
listener extracts it and continues the same trace. So one trace can span the synchronous request and the
asynchronous consumer.

**Q: What are the cardinality and privacy risks in metrics, and how did you avoid them?**
A: High-cardinality tags (e.g. patient id, claim id) explode the number of time series and can leak PHI into the
monitoring system. We tag only low-cardinality, non-sensitive dimensions (outcome, type) and keep gauges/counters
to counts and coded values — never identifiers or clinical data (rule 5). The same discipline applies to alert
annotations and log lines.

**Q: Explain the bash bug in the restore drill and why `</dev/null` fixes it.**
A: The verify loop was `while read t; do ... done <<< "$TABLES"`, and inside it called
`docker compose exec ... psql`. `docker compose exec` reads from stdin, and stdin inside the loop *is* the
here-string feeding `read`. So the first `docker exec` consumed the rest of the table list, and the loop ended
after one iteration (it "passed" checking a single table). Redirecting the in-container helpers' stdin from
`/dev/null` detaches them from the loop's input (they use `psql -c`, so they need no stdin), and the loop reads
all tables. It's a general gotcha for `ssh`/`docker`/`psql` inside `while read` loops.

**Q: Why is putting the database in the readiness group (but not liveness) the correct design, and what's the
failure mode if you got it backwards?**
A: Readiness gates traffic; if the DB is unreachable the app can't usefully serve, so it should leave the load
balancer — hence `db` in readiness. Liveness triggers restarts; a transient DB outage must not restart every app
instance (that adds load and fixes nothing), so liveness stays process-only. If you put the DB in *liveness*, a
brief DB blip would cascade into a restart storm of otherwise-healthy app instances; if you left the DB out of
*readiness*, the load balancer would keep sending traffic to an instance that can only return errors.

---

## Local Cognito login, hosted-UI branding & self-signup removal (+ UI polish round 2) — 2026-09-20

### What we built
Three things this session: (1) made **real "Sign in with Cognito" work in local dev** — logging in as Dana
Provider through genuine OIDC against the existing Cognito pool, not the dev-login stand-in; (2) **branded the
Cognito hosted login page** (dark navy + teal + a HealthCloud logo) and **removed public self-signup**; and
(3) a **second UI-polish pass** — a full pixel audit of every screen in light and dark, a styled document-upload
control, un-cramped date/number form fields, live role→screen verification for all six roles, and adding the two
missing roles to the dev-login dropdown.

### How it works
- **The Cognito login path (recap):** the backend is a **BFF** — with `SPRING_PROFILES_ACTIVE=local,cognito` and
  the `COGNITO_CLIENT_ID/SECRET/ISSUER_URI` env, `SecurityConfig` enables `.oauth2Login`; the browser hits
  `/oauth2/authorization/cognito` → 302 to Cognito's hosted UI → user authenticates → Cognito redirects back to
  `/login/oauth2/code/cognito` with a code → Spring exchanges it → session established. `user-name-attribute: email`
  makes the OIDC principal the email, so the DB-driven roles/tenant logic is unchanged.
- **The blocker + the fix:** the pool existed but its Terraform-managed app client had been deleted with the last
  `terraform destroy`. Recreating it via `terraform apply -target=aws_cognito_user_pool_client.app` planned to also
  create `aws_cloudfront_distribution.main` and `aws_lb.main` (**cost**) — because the client's `callback_urls`
  interpolate `aws_cloudfront_distribution.main.domain_name`, so Terraform pulls CloudFront (and its ALB origin) in
  as dependencies. We caught this in the plan and did **not** apply. Instead we created a throwaway client by hand:

  ```
  aws cognito-idp create-user-pool-client --user-pool-id us-east-1_YA95ksq5k \
    --client-name healthcloud-local-dev --generate-secret --allowed-o-auth-flows-user-pool-client \
    --allowed-o-auth-flows code --allowed-o-auth-scopes openid email profile \
    --supported-identity-providers COGNITO --explicit-auth-flows ALLOW_REFRESH_TOKEN_AUTH \
    --callback-urls http://localhost:8080/login/oauth2/code/cognito http://localhost:5173/login/oauth2/code/cognito \
    --logout-urls http://localhost:8080/ http://localhost:5173/ --prevent-user-existence-errors ENABLED
  ```

  localhost callbacks only → no CloudFront/ALB reference → **$0**, no cost infra. The returned id/secret feed the
  backend env.
- **Branding the hosted UI:** `aws cognito-idp set-ui-customization --client-id <id> --css file --image-file
  fileb://logo.png`. The classic hosted UI accepts a restricted CSS allowlist keyed to fixed classes
  (`.background-customizable`, `.banner-customizable`, `.submitButton-customizable`, `.inputField-customizable`,
  `.label-customizable`, …) plus a logo image. We set a dark navy card, teal button (`#0d9488`), light on-dark
  labels, and the logo. **Limitation:** the grey page margin *around* the card isn't a customizable class.
- **Removing self-signup:** `update-user-pool` with `AdminCreateUserConfig.AllowAdminCreateUserOnly=true`. Because
  that API **replaces** omitted fields with defaults, we first `describe-user-pool`, then rebuilt the full config
  (password policy, MFA OPTIONAL, email/recovery, tags) into a `--cli-input-json` payload changing only that flag.
- **The logo pipeline (no image libraries installed):** wrote the lockup as an HTML file → `qlmanage -t` (macOS
  QuickLook via WebKit, which renders web fonts, unlike its SVG generator which dropped `<text>`) → a **pure-Python
  PNG auto-cropper** (stdlib `zlib` + manual PNG decode/unfilter/re-encode) that bounding-boxes to non-white pixels.
- **UI polish round 2 (repo code):** `DocumentsCard` swapped the bare `<input type="file">` for
  `<Button component="label">Choose file<input hidden …/></Button>` + a themed filename line (kept the `aria-label`
  on the hidden input so tests were unchanged); added `sx={{ minWidth: … }}` to date/number fields that were
  collapsing in `direction="row"` forms (clipping `mm/dd/yyyy` and truncating labels).

### Key points to remember
- **Terraform drift is intentional here.** The hand-made client, the hosted-UI branding, and the no-signup flag are
  all outside `cognito.tf`. A future `terraform apply` (deploy) reverts them — documented in CLAUDE.md + memory.
- **`-target` still pulls dependencies.** Targeting one resource plans everything it references; an interpolated
  attribute of another resource (CloudFront's domain in the callback list) forces that resource into the plan.
- **The assistant never types a password to authenticate.** The user ran `admin-set-user-password` and entered the
  credential in the hosted UI themselves — even though the demo password was visible in their command.
- **Proof of a *real* OIDC session, not dev-login:** `/me` resolved to the user AND the backend log showed **zero**
  `/dev-login` calls since the `local,cognito` restart. Absence-of-evidence made concrete.
- **Hosted UI ≠ our app.** It's an AWS-rendered page; we can only theme it within AWS's allowlist, never match the
  React design pixel-for-pixel. Fully custom modern login = Cognito "Managed Login," which can hit a paid tier.
- **Self-signup was a dead-end anyway:** `CognitoOidcUserService` rejects any login with no ACTIVE `AppUser`, so a
  self-registered user could authenticate to Cognito but never enter the app — removing the link is honest UX.

### Failures and how we fixed them
- **`terraform apply -target` would have cost money** (planned ALB + CloudFront). Root cause: callback URLs reference
  the CloudFront domain. Fix: don't use Terraform for the local client — create it by hand with localhost callbacks.
- **Canvas→base64→disk bridge failed:** the browser canvas produced a correct PNG data URL, but the ~20 KB base64
  was too long to transcribe into a shell heredoc without truncating (got a 216-byte, empty PNG). Abandoned that
  bridge.
- **qlmanage dropped SVG `<text>`** (only the glyph rendered). Fix: render the lockup as **HTML** instead (WebKit
  renders fonts).
- **qlmanage top-left-anchors + pads to a square**, and **`sips` crops from the center** — so a naive crop grabbed
  empty white. Fix: a pure-Python auto-cropper that finds the content bounding box regardless of placement.
- **`update-user-pool` replaces omitted settings with defaults** — a naive call would have reset the password policy
  / MFA. Fix: read the current config first and pass it all back via `--cli-input-json`, changing only the one flag.
- **Stale local DB** was missing the AUDITOR user (added to the seeder in Phase 7; the seeder is skip-if-exists, so a
  DB seeded earlier never got it). Fix: `db-reset.sh` → fresh canonical seed.

### Interview Q&A

#### 1. Beginner
**Q: Why did the "Sign in with Cognito" button start out disabled locally?**
A: A public endpoint, `GET /api/v1/auth/config`, returns `{cognitoEnabled}` — true only when the backend has a
Cognito client registration configured. Local dev normally runs the `local` profile with no OIDC client, so the
probe returns false and the login page renders the button disabled with a note, instead of letting a click hit a
500. Once we ran `local,cognito` with real client credentials, the probe returned true and the button activated.

**Q: What is the Cognito "hosted UI"?**
A: A login page hosted and rendered by AWS Cognito (on the pool's `*.auth.<region>.amazoncognito.com` domain), not
part of our React app. Our BFF redirects the browser there to authenticate; that's why our MUI theme doesn't reach
it and we could only restyle it within AWS's limited customization.

**Q: Why can't the assistant just log in for you?**
A: Entering a password into a login form to authenticate is a prohibited action for the assistant, even when the
password is known/visible. The user performs the actual sign-in; the assistant sets up everything around it.

#### 2. Intermediate
**Q: Why did recreating just the Cognito app client threaten to cost money, and how did you avoid it?**
A: Terraform builds its dependency graph from references. The client's `callback_urls` interpolate the CloudFront
distribution's domain name, so `apply -target` on the client also planned to create CloudFront — and CloudFront's
origin is the ALB — both billable. We avoided it by creating the client out-of-band with the AWS CLI using only
localhost callbacks (no CloudFront reference), keeping it $0 and leaving the deploy stack torn down.

**Q: How do you disable self-registration on a Cognito pool without clobbering other settings?**
A: Set `AdminCreateUserConfig.AllowAdminCreateUserOnly=true` via `update-user-pool`. But that API is a replace for
many fields, so you must first `describe-user-pool`, capture the current config (password policy, MFA, email,
recovery, tags), and resubmit it all with only that one field changed — best done with `--cli-input-json`.

**Q: How did you prove the session was real Cognito OIDC and not the dev-login stand-in?**
A: Two signals: `/me` resolved to the expected user with the right role, and the backend log showed **zero**
`/dev-login` calls since the `local,cognito` restart. Since dev-login is the only other way to establish a session
and it wasn't used, the session could only have come from the Cognito hosted-UI flow.

#### 3. Advanced
**Q: You generated a PNG logo with no PIL/ImageMagick/rsvg. Walk through it.**
A: HTML lockup (glyph as CSS + wordmark text) → `qlmanage -t` renders it via WebKit to a PNG (WebKit renders web
fonts; qlmanage's SVG path silently drops `<text>`). qlmanage top-left-anchors the content and pads to a square,
and `sips` only center-crops, so I wrote a ~60-line pure-Python PNG tool (stdlib `zlib` + manual chunk parse,
scanline unfiltering incl. Paeth, and re-encode with filter-0 rows) that computes the content bounding box
(non-white pixels) and crops to it — deterministic regardless of where qlmanage placed the content.

**Q: This whole setup is Terraform drift. Why is that acceptable, and what's the migration path?**
A: It's acceptable because it's a **local-dev convenience** on a torn-down deploy, it's $0, and it's fully
documented (CLAUDE.md + a memory note) with exact recreate/cleanup commands. The migration path when the app is
next deployed: `cognito.tf` recreates its own client (the app reads that client's secret from Secrets Manager),
so we'd add `admin_create_user_config { allow_admin_create_user_only = true }` to the pool resource and re-apply
the hosted-UI branding to the new client (or, better, encode the branding as an `aws_cognito_user_pool_ui_customization`
resource). The throwaway local client is deleted with `delete-user-pool-client` when no longer needed.

**Q: What are the limits of Cognito classic hosted-UI branding, and when would you move past them?**
A: The classic hosted UI only exposes a fixed set of CSS classes (background, banner, inputs, labels, submit
button, links) plus a logo image — you can't restyle the outer page, restructure the layout, or match an external
design system exactly. You'd move to Cognito **Managed Login** (the 2024 branding designer) for richer control, but
it can push the pool into a paid pricing tier, so for a $0 portfolio project the classic CSS branding is the right
trade-off.

---

## Enabling Cognito for more demo users + verifying every login live in the browser — 2026-09-21

### What we built
No new application code — this session **operationalized** the Cognito login for more of the seeded demo users and
then **proved every login works** through the browser. We took the local Cognito pool from "only
`provider@northcare` really works" to **6 fully working accounts**: the 4 provider-role users
(`provider@`/`provider2@` in both NorthCare and Green Valley) and both org admins (`admin@northcare`,
`admin@greenvalley`). Then we drove the real browser through the branded Cognito hosted UI for all six and confirmed,
via `/api/v1/me`, that each resolved to the correct user, role, and tenant.

### How it works
Three things must line up for a Cognito login to succeed in this app:

1. **The user exists in the Cognito pool** (`us-east-1_YA95ksq5k`) — created with
   `aws cognito-idp admin-create-user --message-action SUPPRESS` (suppresses the invite email; no password in the
   command). A fresh user lands in `FORCE_CHANGE_PASSWORD` status.
2. **The pool user has a permanent password** — set with `aws cognito-idp admin-set-user-password --permanent`,
   which flips the status to `CONFIRMED`. The **user ran this themselves** in a real terminal; the assistant never
   types a credential.
3. **A matching ACTIVE `AppUser` exists in the DB** (by email) — already true for all 14 seeded users;
   `CognitoOidcUserService` rejects any Cognito login with no app user.

The browser verification flow for each account:

```
log out of the app  →  clear the Cognito SSO session (hosted-UI /logout)
  →  click "Sign in with Cognito"  →  branded hosted UI (dark navy + teal + logo)
  →  type email + password  →  redirect back to the app  →  GET /api/v1/me confirms identity
```

The key proof is in the `/me` results: `admin@*`→ORG_ADMIN, `provider*@*`→PROVIDER, with NorthCare accounts on
`organizationId 6a1a8a6b…` and Green Valley on `db43d7a2…`. The two "Alex Admin" and the Dana/Morgan pairs are
**distinct rows** (different `userId`/`organizationId`) — Cognito authenticated the *email*; the *role and tenant*
came from the database (rule 4).

### Key points to remember
- **Cognito supplies identity, the DB supplies authorization.** Because `user-name-attribute: email` makes the OIDC
  principal name the email, `UserContextFilter.resolveByEmail` maps it to the DB user and all role/tenant logic is
  unchanged. Never trust Cognito for roles.
- **The Cognito pool is a separate account list from the app DB.** A user existing in the DB (so dev-login works)
  does NOT mean it exists in Cognito. "Enabling Cognito for a user" = create it in the pool + set a password.
- **The assistant's password boundary held the whole session:** it created pool users and filled *email* fields in
  the browser, but the *user* set and typed every password. This is a hard rule even when a password is visible.
- **Two login paths coexist:** dev-login (local dropdown, no password, all 14 users) and Cognito (real OIDC, only
  pool-provisioned users). dev-login is `local`-profile only.
- **SSO makes a second login look "instant."** After one Cognito login, the hosted UI keeps its own session cookie,
  so clicking "Sign in with Cognito" again silently returns without a form. To force the login form (and to switch
  users), clear the Cognito session via the hosted-UI `/logout?client_id=…&logout_uri=…` endpoint (the app's own
  logout only clears the Spring session — RP-initiated Cognito logout is a documented follow-up).
- **No passwords are stored anywhere** — repo, docs, or memory record only *which* accounts are enabled.

### Failures and how we fixed them
- **The permanent-password step kept "hanging" / rejecting an empty password.** Symptom: running the
  `read -s "PW?…"; aws … admin-set-user-password …` one-liner showed the prompt then nothing, or Cognito returned
  `Value at 'password' failed to satisfy constraint: Member must satisfy regular expression pattern ^[\S]+.*[\S]+$`.
  Root cause: the command was being run through the desktop app's **inline command runner**, which executes
  non-interactively — `read` gets no stdin, so `PW` stayed empty and Cognito rejected the empty value. Fix: run it
  in the **real macOS Terminal app** (a genuine TTY). Once there, it worked immediately and the status flipped to
  `CONFIRMED`.
- **`read -s -p` errored with `read: -p: no coprocess`.** Root cause: the shell is **zsh**, where `read -p` means
  "read from a coprocess," not "prompt." Fix: zsh's prompt syntax `read -s "PW?prompt"`.
- **"Incorrect username or password" for the Green Valley / provider2 accounts.** Root cause: those three providers
  were given a single shared password in one loop earlier, and the exact value wasn't remembered. Fix: reset the
  password (`admin-set-user-password --permanent` again) to a value the user would retype exactly, then log in.
- **Directly navigating the browser pane to the Cognito domain was blocked** ("navigation … denied or failed").
  It didn't matter — the OAuth *redirect* (app → Cognito) works fine within the flow, and the hosted-UI `/logout`
  navigation still cleared the SSO cookie enough that the next "Sign in with Cognito" showed the login form.
- **A CI watcher reported a false "FAILED"** for the earlier docs commit; the authoritative `gh run view` showed all
  four jobs green. Lesson: confirm a watcher's verdict against the real run status.

### Interview Q&A

#### 1. Beginner
**Q: Why did only one user work with "Sign in with Cognito" at first?**
A: Because only that user had been fully provisioned in the Cognito user pool (created *and* given a permanent
password). The others existed in the app's database (so the local dev-login dropdown worked for them) but weren't in
Cognito yet. Cognito is a separate list of accounts.

**Q: What are the two ways to log into this app, and how do they differ?**
A: dev-login — a local-only convenience that logs you in as any seeded user with no password (only under the `local`
profile). And Cognito — the real OIDC login (email + password against AWS Cognito's hosted UI), which only works for
users provisioned in the pool.

**Q: How do you make a new user able to sign in with Cognito?**
A: Create them in the pool (`admin-create-user`), set a permanent password (`admin-set-user-password --permanent`),
and make sure an ACTIVE app user with the same email exists in the DB.

#### 2. Intermediate
**Q: The app showed two different "Alex Admin" and two "Dana Provider" users. How is that not a bug?**
A: The seeder gives each organization's counterpart user the same display name, but they're distinct database rows
with different `userId` and `organizationId`. Cognito only authenticated the email; the app looked that email up in
the DB and scoped the session to that specific user and tenant. It's proof that identity (Cognito) and authorization
(DB) are separate.

**Q: Why did a second Cognito login skip the password form, and how did you force it to prompt?**
A: Cognito's hosted UI keeps its own SSO session cookie, so after one login the authorization endpoint immediately
returns a code without re-prompting. The app's logout only clears the local Spring session, not Cognito's. To force
a fresh prompt we hit the hosted-UI `/logout` endpoint (with `client_id` + a registered `logout_uri`) to clear the
Cognito cookie.

**Q: Why couldn't the assistant just set the passwords itself to save time?**
A: Handling or typing a password (even a synthetic one, even one visible in the chat) is a hard safety boundary. The
assistant did the non-secret parts (creating pool users, filling email fields) and the user set/typed every password.

#### 3. Advanced
**Q: `admin-set-user-password` kept failing with an empty-password regex error in one environment but worked in
another. Diagnose it.**
A: The failing environment ran the command non-interactively (a runner with no TTY), so the interactive `read -s`
captured nothing and passed an empty string, which Cognito rejects with the `^[\S]+.*[\S]+$` constraint. A real TTY
(the Terminal app) let `read` capture input. A non-interactive alternative would be to pass the password another
way — but that would expose it, so the interactive-TTY approach is preferred here.

**Q: This browser-verified setup is AWS drift. What breaks on the next Terraform deploy, and how would you make it
durable?**
A: The enabled users, their passwords, the hand-made local app client, the hosted-UI branding, and the disabled
self-signup are all outside `cognito.tf`. A deploy recreates the Terraform-managed client (without branding) and, if
the pool is recreated/updated, could reset self-signup. To make it durable: encode
`admin_create_user_config { allow_admin_create_user_only = true }` on the pool, add an
`aws_cognito_user_pool_ui_customization` resource for the branding, and manage the demo users as
`aws_cognito_user` resources (passwords still set out-of-band, never in state).

**Q: What's the security significance of `CognitoOidcUserService` rejecting logins with no ACTIVE AppUser?**
A: It means a valid Cognito identity is necessary but not sufficient — the app is the authority on *who is allowed
in and with what role/tenant*. Even if someone authenticated at Cognito, without a provisioned ACTIVE app user they
get no session. That's also why self-signup was safe to disable: a self-registered Cognito user would have no app
user and would be rejected anyway.

---

## Login page redesign (theme-aware "Console") + demo-credentials card + real logo asset — 2026-09-22

### What we built
A portfolio-grade redesign of the login page (the first thing a stranger sees when the project is shared on
LinkedIn), plus a demo-credentials card so a recruiter can actually sign in and explore, and a switch to using the
**real logo image** as the brand mark. Frontend-only; no backend/API changes.

### How it works
- **Demo-credentials card** (`frontend/src/auth/LoginPage.tsx`): rendered whenever `cognitoEnabled` is true. A
  `DEMO_ACCOUNTS` array (role · email · one-line "what to try" hint) and a shared `DEMO_PASSWORD` constant, a
  "Synthetic data only" chip, and two tips — swap `northcare`↔`greenvalley` to witness tenant isolation, and open a
  fresh Incognito window to switch users (Cognito keeps its own SSO cookie the app's logout can't clear). The password
  is a **placeholder** (`REPLACE_WITH_YOUR_DEMO_PASSWORD`) — the real shared demo password is filled in before deploy,
  never committed.
- **The redesign** — after exploring 8 directions as throwaway HTML artifacts, we settled on a **"Console"**
  engineering-credibility look, then rebuilt it **theme-aware**. Structure: `<Brand size="lg" />` + a
  `CARE COORDINATION & CLAIMS` eyebrow; a centered headline *"Care coordinated. Consent enforced. Decisions
  explained."* with the verbs in `primary.main`; a one-line hook sub; then two balanced columns — a **Security
  posture** panel (monospace rows stating backend-enforced capabilities) and the **Sign in** card — with the demo
  card below.
- **Theme-awareness** is the key engineering point: instead of a hardcoded dark palette, every color is a **theme
  token** (`text.primary`, `text.secondary`, `background.default`, `primary.main`, `success.main`, `divider`) and
  scheme-specific bits use `theme.applyStyles('dark', { … })`. Because the app's MUI theme already ships light + dark
  color schemes (CSS variables), the login now **follows the visitor's light/dark preference automatically**.
- **Real logo asset** (`frontend/src/components/Brand.tsx` + `frontend/public/logo.png`): `Brand` now renders
  `<Box component="img" src="/logo.png" />` (with a `size` prop) instead of a CSS-drawn glyph. The PNG is the user's
  actual logo with its white background removed.

### Key points to remember
- **To match a *designed* logo, use the real asset — not a CSS recreation.** We wasted several iterations
  color-guessing a gradient before switching to the file. A CSS gradient can approximate but never equals a designed
  image.
- **Removing a background with no image tools:** the Mac had no PIL and no ImageMagick. We removed the white
  background **in the browser canvas**: draw the image, **flood-fill from the four borders** clearing connected white
  pixels to transparent (this deletes the outer background but *keeps the enclosed white cross*, since it isn't
  border-connected), crop to the opaque bounding box, `canvas.toDataURL('image/png')`, then base64-decode to
  `public/logo.png`. A naive "make all white transparent" would have deleted the cross too.
- **Honest UI, rule 2:** the Security posture panel reads like live telemetry but is worded as *capabilities the
  backend enforces* (which are all real, implemented features) — deliberately not faked "live" numbers/status.
- **`public/` in Vite** serves files at the root path (`/logo.png`) in both dev and the production build; a newly
  added file is served immediately (no server restart needed).
- **Cognito SSO cookie:** app logout only clears the Spring session; Cognito remembers the last sign-in via its own
  cookie, so re-clicking "Sign in with Cognito" silently returns the same user. Switching users = a fresh Incognito
  window or the hosted-UI `/logout` endpoint. This is now called out on the demo card.

### Failures and how we fixed them
- **CSS gradient never matched the logo.** Symptom: user repeatedly said "completely different." Root cause: trying
  to reproduce a designed logo with a guessed CSS gradient. Fix: sampled the real pixels, then abandoned CSS entirely
  and used the actual image file with the background removed.
- **Wrong file found.** A `logo.png` already sat in `~/Downloads` — but it was an unrelated red logo. Fix: verified
  the image by viewing it before using it; found the correct one (a Desktop screenshot the user had renamed
  `logo file.png`).
- **Sign-in inputs on a dark bespoke surface** (in the first Console pass) needed manual dark styling. The
  theme-aware rebuild removed that problem — theme components adapt to light/dark on their own.

### Interview Q&A

#### 1. Beginner
**Q: Why use the app's theme tokens instead of hardcoded colors on the login page?**
A: So the page automatically adapts to light and dark mode. Hardcoded hex values look right in one mode and broken in
the other; theme tokens resolve to the correct color for whichever scheme is active.

**Q: Why is the demo password a placeholder in the code?**
A: The page is public and committed to a public repo, so a real password must never be in the source. The placeholder
is swapped for the real shared demo password only in the deployed build.

#### 2. Intermediate
**Q: How do you remove a white background from an image with no image-processing libraries installed?**
A: Use the browser canvas. Draw the image, read the pixel buffer, and flood-fill from the borders turning
border-connected white pixels transparent, then re-export as PNG. Flood-fill (rather than "key out all white")
preserves white regions enclosed by non-white — here, the logo's inner cross.

**Q: How does `theme.applyStyles('dark', …)` work and why not just check a boolean?**
A: With MUI CSS-variable color schemes, `applyStyles('dark', styles)` emits the given styles under the dark-scheme
selector so the browser applies them when the dark scheme is active — no JS re-render, and it works with SSR/no-flash
hydration. A runtime boolean would require knowing the mode at render time and wouldn't switch via pure CSS.

#### 3. Advanced
**Q: The security-posture panel could look like fake live monitoring. How do you keep it honest?**
A: Word it as *capabilities the platform enforces* (tenant isolation, consent policy, audit hash-chain, field
masking) — each maps to a real implemented feature — rather than as live metrics or uptime numbers. The project's
"no unmeasured claims" rule forbids presenting unmeasured values as measured, so the panel states guarantees, not
telemetry. If we wanted true live status we'd wire each row to a real health/probe signal.

**Q: Trade-offs of shipping the logo as a raster PNG vs. inline SVG or CSS?**
A: The PNG is an exact match to the designed asset and trivial to swap, but it's raster (fixed resolution, ~9 KB) and
its colors don't adapt to theme. An SVG would be crisp at any size and themeable, and CSS would be the lightest but
can't perfectly reproduce a designed mark. For a fixed brand logo, exactness won — a designed logo shouldn't be
re-interpreted per theme anyway. If we needed multi-resolution crispness we'd export an SVG from the source art.

---

## Front page rebuilt as a landing page (header nav + hero) + fixing a silently-transparent logo — 2026-09-22

### What we built
We replaced the login page with a proper **landing page** built to match a design the user supplied as a reference
image: a top **header** (logo + "HealthCloud" wordmark on the left, five clickable role personas with icons in the
center, a rounded "Sign in" pill on the right) over a hairline divider, then a **hero** with a big horizontal-flowing
headline (*"Care coordinated. Consent enforced. Decisions explained."*, the verbs teal-accented) and a one-line sub.
It is **theme-aware**: light mode reproduces the reference (a soft mint→white wash) and dark mode is a "technical"
deep-navy field (a teal top-glow + a faint engineering grid). We also fixed a real bug: the committed logo PNG was
100% transparent, so the brand glyph had been rendering invisibly.

### How it works
- **File:** `frontend/src/auth/LoginPage.tsx` (rewritten). Icons come from `@mui/icons-material`
  (`PersonOutlined`, `AccountTreeOutlined`, `FactCheckOutlined`, `TuneOutlined`, `CenterFocusStrongOutlined`).
- **Theme-aware background** — one `sx` callback sets the light gradient and overrides it for dark via
  `theme.applyStyles('dark', { … })`:
  ```tsx
  sx={(theme) => ({
    backgroundColor: '#f7faf9',
    backgroundImage: `radial-gradient(1200px 520px at 50% -12%, rgba(13,148,136,0.12), transparent 62%),
      linear-gradient(180deg, rgba(214,240,235,0.55), transparent 42%)`,
    ...theme.applyStyles('dark', {
      backgroundColor: '#070b18',
      backgroundImage: `radial-gradient(1200px 540px at 50% -12%, rgba(45,212,191,0.14), transparent 60%),
        linear-gradient(rgba(148,163,214,0.05) 1px, transparent 1px),
        linear-gradient(90deg, rgba(148,163,214,0.05) 1px, transparent 1px)`,
      backgroundSize: 'auto, 44px 44px, 44px 44px',
    }),
  })}
  ```
  The two crossed `linear-gradient`s + `backgroundSize: 44px 44px` are the grid (dark only); the `radial-gradient` is
  the glow.
- **Interactive roles.** Each persona is a `<Box component="button">` calling `scrollToSignIn(key)`, which sets a
  `highlight` state and does `document.getElementById('signin')?.scrollIntoView({ behavior: 'smooth' })`. The matching
  demo-account card below then renders with a `primary.main` ring (`border: active ? 2 : 1`). The "Sign in" pill calls
  `scrollToSignIn()` with no key (no highlight) — so the two controls have distinct jobs.
- **Responsive.** The role bar is `display: { xs: 'none', md: 'flex' }` — hidden on phones where a horizontal role
  strip can't fit; the brand + pill stay.
- **Logo fix** — `frontend/public/logo.png`, regenerated by a throwaway pure-Python script (`zlib` only):
  read the source PNG (RGBA, non-interlaced) → un-filter each scanline → flood-fill inward from the border, clearing
  near-white pixels (`r,g,b` all > 220) to transparent, which stops at the colored rounded square and never reaches
  the *enclosed* white cross → crop to the opaque bounding box → re-encode (filter 0 per row + `zlib.compress`).

### Key points to remember
- **To match a *designed* asset, use the real file — and process image bytes end-to-end in ONE tool.** The
  background removal itself was fine; what bit us was moving the result across a boundary (below).
- **`theme.applyStyles('dark', …)` is the project's one way to do scheme-specific styling** — never hardcode a
  scheme hex. Light stays the base; dark is the override. This is why the same component is both "the reference" and
  "technical dark" with no duplication.
- **Dev login was removed from the page** on the user's instruction (Cognito is the sign-in path). Consequence:
  local login now requires the backend on the `cognito` profile — there's no one-click dev sign-in in the UI.
- **Forcing the app's theme in the preview:** the pane re-syncs the tab to the app's (dark) theme, so emulating
  `prefers-color-scheme` isn't enough. The app persists its mode under `localStorage['mui-mode']` (`light`/`dark`/
  `system`) with a `class` selector on `<html>`; set that key + reload to see a specific scheme.
- **Tests:** removing dev login meant the old "developer sign-in" test no longer applied — it was replaced with a
  test asserting the `<h1>` hero and the clickable role buttons render. All 187 frontend tests pass.

### Failures and how we fixed them
- **The glyph was invisible.** Symptom: `<img src="/logo.png">` loaded (`naturalWidth` 79, HTTP 200) but showed
  nothing. Root cause: the *committed* `logo.png` was **100% transparent** — a previous browser-canvas background
  removal had flood-filled the entire image away (0 opaque pixels of 6320). A canvas check
  (`getImageData` → count `alpha > 20`) confirmed it. Fix: regenerate with a correct border-only flood-fill.
- **The base64 copy-paste corrupted the PNG.** First fix attempt did the flood-fill in the browser, exported a
  `toDataURL` PNG, and pasted its ~12 KB base64 into a shell heredoc to `base64 -d` onto disk. Symptom: `file`
  reported a valid "79×79 RGBA PNG", but it *still* read as transparent, and a manual `zlib.decompress` of the IDAT
  threw **"incorrect data check"**. Root cause: the base64 blob was altered in transit (the IHDR header survived, so
  `file` was fooled; the compressed IDAT did not). Fix: **do the whole thing in one tool** — a pure-Python script
  that reads the source, processes, and writes `logo.png` directly, no cross-boundary blob. Lesson: `file` validates
  the header, not the pixel data; to trust a generated PNG, decode its IDAT (or count opaque pixels).
- **macOS `base64` flags.** `base64 -d <file>` fails on macOS (BSD) — it needs `-i <in> -o <out>`. (Moot once we
  stopped shuttling base64, but worth remembering.)

### Interview Q&A

#### 1. Beginner
**Q: How does one React component render as two very different designs (light reference vs. technical dark)?**
A: It uses MUI's theme system. Colors and backgrounds are theme tokens, and scheme-specific rules go through
`theme.applyStyles('dark', …)`. The browser (or a user toggle) picks the scheme; the component code is written once.

**Q: Why hide the role nav on phones?**
A: Five icon+label items in a horizontal row don't fit a ~375px screen without wrapping or shrinking illegibly. The
brand and the "Sign in" pill are enough on mobile; the roles reappear at the `md` breakpoint.

#### 2. Intermediate
**Q: The user asked "if the roles are clickable, what's the Sign in button for?" How did you resolve it?**
A: By giving them distinct jobs. A role = "explore as this persona": it scrolls to the sign-in area and highlights
that role's demo account. The Sign in pill = the primary CTA: it scrolls to the same area with no role pre-selected.
Both lead to the one real login (Cognito); they differ in whether they pre-frame a persona.

**Q: How do you remove a white background but keep a white shape *inside* the logo?**
A: Flood-fill from the image border inward, only through near-white pixels. The fill stops at the colored rounded
square, so it clears the outside white but can never reach the white cross enclosed by color. A global "make white
transparent" pass would have erased the cross too.

#### 3. Advanced
**Q: `file` said the PNG was a valid 79×79 RGBA image, yet it rendered transparent. How is that possible, and how
would you detect it in CI?**
A: A PNG's dimensions/format live in the IHDR chunk; the pixels live in compressed IDAT chunks. Our base64 round-trip
corrupted IDAT but left IHDR intact, so `file` (which reads the header) was satisfied while the pixel data was
garbage/undecodable. Detection: actually decode the image — `zlib.decompress` the IDAT (a CRC/"incorrect data check"
failure is a red flag), or load it and count pixels with `alpha > 0`. A pre-commit check that asserts a logo asset
has a minimum share of opaque pixels would have caught both the all-transparent file and the corrupted one.

**Q: Why regenerate the asset in Python instead of the browser canvas you'd already used?**
A: The browser produced the correct pixels — the failure was *exfiltrating* them (a 12 KB base64 blob through a
copy-paste/shell boundary). Python reads the source, transforms, and writes the destination file in one process with
no lossy hand-off, so what it computes is exactly what lands on disk. The general rule: keep binary-generation
end-to-end in a single tool.
