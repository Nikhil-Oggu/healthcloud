# ADR-004 — Cognito + Spring BFF
- Status: Accepted
- Date: 2026-09-12

## Context
The app needs secure authentication for multiple roles with mandatory MFA. A browser-based SPA holding
OAuth tokens (JWT in JS) is a larger attack surface (token theft via XSS). We want strong token
protection and server-controlled sessions.

## Decision
Use **Amazon Cognito** (OIDC authorization-code flow) with a **Spring Boot Backend-for-Frontend (BFF)**.
The browser holds only a **secure HttpOnly session cookie** (Spring Session JDBC in PostgreSQL) plus a
CSRF token for state-changing requests. The frontend **never** stores Cognito access/refresh tokens.
React and the API share one public origin via CloudFront.

## Consequences
- ✅ Tokens stay server-side; smaller browser attack surface; standard, well-supported OIDC flow.
- ✅ Server-side session enables clean logout, revocation, and MFA enforcement.
- ⚠️ Server must manage session state (PostgreSQL-backed); single-origin routing must be configured.
- Local dev may use a Cognito stand-in so the app runs without cloud dependencies.
