# ADR-018 — Local dev-login stand-in for authentication
- Status: Accepted (implementation-time; ADRs 001–017 come from the source-of-truth, 018+ are implementation decisions)
- Date: 2026-09-12

## Context
The target design (ADR-004) is Amazon Cognito (OIDC) + a Spring BFF with mandatory MFA. Cognito
requires an AWS account, which is deliberately deferred to ~Phase 10 (local-first strategy). We still
need working authentication locally to build and test all the tenant/consent logic that depends on
"who is the user."

## Decision
Implement a **local-only dev-login stand-in** with the same *shape* as the eventual BFF:
- `POST /api/v1/dev-login` (active only under the `local` profile) establishes an authenticated
  server-side session for a seeded, ACTIVE user identified by email — **no password, no MFA**.
- Sessions are server-side, stored in PostgreSQL via **Spring Session JDBC** (starter
  `spring-boot-starter-session-jdbc`), carried by a secure **HttpOnly `SESSION` cookie**.
- **CSRF** protection uses a readable `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header (a `CsrfCookieFilter`
  forces the token to be emitted). `dev-login` is CSRF-exempt because it bootstraps the session.
- `GET /api/v1/me` returns the authenticated user's context (user → active membership → org → roles);
  unauthenticated protected requests return `401` (no login-page redirect).

## Consequences
- ✅ All downstream authorization/consent work can proceed locally with realistic sessions.
- ✅ When Cognito is added, only the login entry point changes; session, CSRF, `/me`, and the security
  rules stay.
- ⚠️ **Honest limitation:** this is NOT real authentication — no passwords, no MFA. It must never run
  outside the `local` profile and is never described as production auth.
- Notes on Boot 4.1: Spring Session auto-config lives in the `spring-boot-starter-session-jdbc`
  module (not the raw `org.springframework.session:spring-session-jdbc` library alone); the full
  session-cookie flow is verified with a real embedded server, not MockMvc.
