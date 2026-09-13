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
