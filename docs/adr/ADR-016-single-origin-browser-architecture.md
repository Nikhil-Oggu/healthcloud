# ADR-016 — Single-Origin Browser Architecture
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 1 / Phase 10)

## Context
The SPA and the API could be served from different origins, but that forces CORS and makes secure cookie handling
harder (SameSite, credentialed requests, CSRF across origins). For a session-cookie BFF (ADR-004), the simplest and
most secure arrangement is for the browser to see a **single origin**.

## Decision
Serve the frontend and proxy the application/auth routes through **one origin** at every tier:
- **Production:** CloudFront serves the React build and forwards `/api`, `/actuator`, `/oauth2`, `/login/oauth2`
  to the ALB → nginx → Spring Boot. CloudFront terminates HTTPS (ADR-004/-016 require HTTPS for the Cognito flow).
- **Container/local-full:** nginx serves the SPA and reverse-proxies the same paths to the backend.
- **Dev:** the Vite dev server proxies the same paths to `:8080`.
So in every environment the browser talks to one origin; the HttpOnly session cookie and the readable `XSRF-TOKEN`
cookie stay first-party, and OAuth tokens never leave the server-side BFF.

## Consequences
- ✅ No CORS; first-party cookies; CSRF handling stays straightforward; tokens remain server-side.
- ✅ The same image runs unchanged from local to cloud (only the proxy upstream differs).
- ⚠️ Behind CloudFront→ALB the ALB hop is HTTP, so the OIDC `redirect_uri` must be **pinned** to the HTTPS
  CloudFront callback (via a Spring relaxed-binding env var) or Spring would compute an `http://` URI Cognito
  rejects.
- ⚠️ The ALB is currently reachable directly over HTTP; locking it to CloudFront (SG prefix list, done) plus a
  per-distribution origin-verify header (follow-up) hardens the "only via CloudFront" intent.
