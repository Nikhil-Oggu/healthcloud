# ADR-002 — Shared PostgreSQL multi-tenancy
- Status: Accepted
- Date: 2026-09-12

## Context
Multiple fictional healthcare organizations use one platform. Their data must stay strictly isolated,
but a database-per-tenant model adds heavy operational cost and complexity not justified at this scale.

## Decision
Use **one shared PostgreSQL database** with an `organization_id` tenant key on every tenant-owned row.
Tenant context is **derived on the backend** from the authenticated session — never read from a
client-supplied value. Database relationship constraints prevent cross-tenant references, and negative
tests prove isolation.

## Consequences
- ✅ Simple, low-cost, easy to develop and reason about; strong isolation via backend context + constraints + tests.
- ⚠️ Larger logical blast radius than physical isolation; every tenant-owned query/repository must be tenant-safe by design.
- Future option: database-per-tenant or row-level security as defense-in-depth if a real deployment justifies it.
