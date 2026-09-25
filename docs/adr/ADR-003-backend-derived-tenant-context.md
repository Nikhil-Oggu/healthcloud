# ADR-003 — Backend-Derived Tenant Context
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 1)

## Context
HealthCloud is multi-tenant over a shared database (ADR-002). The single most dangerous mistake in a
multi-tenant system is letting the client influence *which tenant* a request operates on — a browser that can
name its own `organization_id` can read another tenant's data. Authentication tells us *who* the user is; it
must also, safely, tell us *which organization* they are acting within.

## Decision
Derive the tenant (and the caller's roles) **entirely on the backend**, never from client input:
- `UserContextFilter` resolves the authenticated session principal to an `AppUser`, then loads the org and
  roles **fresh from the database by email on every request** into a request-scoped `UserContext`.
- Services read identity only via `UserContextAccessor` (`requireUser()`, `requireOrganizationId()`); they never
  accept a tenant/org id from the request body or query string.
- Every tenant-owned query is constrained by `requireOrganizationId()`, and rows are loaded by
  `(organization_id, id)`.
- **Structural defence in the schema:** child rows foreign-key *with* the org (`(parent_id, organization_id)` →
  `parent(id, organization_id)`), so the database itself refuses a cross-tenant reference.

## Consequences
- ✅ A client cannot select or spoof a tenant; a cross-tenant id simply isn't found (a **secure 404**, not a 403).
- ✅ Authority always reflects current DB state — a login (even via Cognito, ADR-004) establishes identity only,
  never roles/tenant.
- ✅ Isolation is enforced at two layers (application scoping **and** DB constraints), proven by the
  `TenantIsolation*` tests, which are extended whenever a tenant-owned resource is added.
- ⚠️ Every new tenant-owned query must route through the context and org-scoped finders — a convention that needs
  discipline (there is deliberately no bare `findById` in business code).
