# Architecture Decision Records (ADRs)

Each ADR captures one significant decision: its context, the choice, and the consequences.
The source-of-truth defines 17 ADRs (see §Appendix A). We add/refine them as phases reach them.

## Template
```
# ADR-NNN — <title>
- Status: Proposed | Accepted | Superseded by ADR-XXX
- Date: YYYY-MM-DD
## Context
<the forces at play; what problem/decision this addresses>
## Decision
<the choice we made>
## Consequences
<trade-offs, what becomes easier/harder, what we revisit later>
```

## Index
| ADR | Title | Status |
|-----|-------|--------|
| 001 | Modular monolith first | Accepted |
| 002 | Shared PostgreSQL multi-tenancy | Accepted |
| 003 | Backend-derived tenant context | Accepted |
| 004 | Cognito + Spring BFF | Accepted |
| 005 | Hybrid authorization | Accepted |
| 006 | Limited clinical context | Pending |
| 007 | Bounded synthetic claims engine | Pending |
| 008 | PostgreSQL metadata + private S3 documents | Pending |
| 009 | Transactional outbox + Kafka | Accepted |
| 010 | Per-organization HMAC audit chains | Accepted |
| 011 | Concurrency strategy | Accepted |
| 012 | ECS Fargate target deployment | Pending |
| 013 | Terraform + GitHub Actions | Pending |
| 014 | Immutable consent and claim decisions | Accepted |
| 015 | On-demand full AWS validation | Accepted |
| 016 | Single-origin browser architecture | Accepted |
| 017 | PostgreSQL first for cache/search | Pending |
| 018 | Dev-login stand-in (local only) | Accepted |

The six **Pending** ADRs (006, 007, 008, 012, 013, 017) reflect decisions that were made and implemented; they
are simply not yet written up as records — a documented follow-up.
