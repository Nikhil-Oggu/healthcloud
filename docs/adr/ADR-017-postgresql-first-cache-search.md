# ADR-017 — PostgreSQL First for Cache/Search
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 9)

## Context
Search, filtering, pagination, and (eventually) caching are easy to over-engineer by reaching for Redis, OpenSearch,
or database row-level security (RLS) early. Each adds infrastructure to run, secure, monitor, and pay for — and at
this project's synthetic scale, none of them is justified by a measured need. The temptation is to add them because
"real systems have them," not because the data demands them.

## Decision
Use **PostgreSQL first** and add specialized cache/search/RLS infrastructure only when real measurements or
requirements justify the added complexity:
- **Search** is implemented in SQL — a `SearchTerms.likeContains` helper builds an escaped, case-insensitive `LIKE`
  "contains" match against **PHI-free business identifiers** (claim/auth/referral/… numbers), across all eight work
  queues.
- **Pagination/filtering/sorting** are pushed into SQL via a reusable `PageResponse<T>` envelope + `PageRequests`
  (size clamp + sort allowlist) — no in-memory filtering.
- **Tenant isolation** is enforced in the application + composite FKs (ADR-002/-003), **not** Postgres RLS.
- No Redis, no OpenSearch.

## Consequences
- ✅ Fewer moving parts, lower cost, simpler local dev and deployment; one datastore to reason about and secure.
- ✅ Search stays safe by construction (escaped `LIKE`, PHI-free columns, no user input in query strings).
- ⚠️ SQL `LIKE` search won't scale to large-corpus full-text ranking/relevance; that's the trigger to revisit
  (OpenSearch) — with measurements, not by default.
- ⚠️ App-enforced tenancy (vs RLS) relies on the discipline of org-scoped finders; the `TenantIsolation*` tests guard
  it. Revisit RLS only if that guarantee needs a database-level backstop.
