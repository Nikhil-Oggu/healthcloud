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
| 006 | Limited clinical context | Accepted |
| 007 | Bounded synthetic claims engine | Accepted |
| 008 | PostgreSQL metadata + private S3 documents | Accepted |
| 009 | Transactional outbox + Kafka | Accepted |
| 010 | Per-organization HMAC audit chains | Accepted |
| 011 | Concurrency strategy | Accepted |
| 012 | ECS Fargate target deployment | Accepted |
| 013 | Terraform + GitHub Actions | Accepted |
| 014 | Immutable consent and claim decisions | Accepted |
| 015 | On-demand full AWS validation | Accepted |
| 016 | Single-origin browser architecture | Accepted |
| 017 | PostgreSQL first for cache/search | Accepted |
| 018 | Dev-login stand-in (local only) | Accepted |

All 17 source-of-truth ADRs (001–017) are now recorded, plus ADR-018 (an emergent decision). ADR-008 notes an
honest gap: private-S3 document storage is the target design, while local dev uses a filesystem stand-in.
