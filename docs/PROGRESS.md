# PROGRESS.md — HealthCloud running log

> The **diary** of the project. Updated at the end of every session (by the `/wrap` command once it
> exists, or manually). Read this + `CLAUDE.md` + `docs/PLAN.md` at the start of every session.

## Current position
- **Phase:** 0 — Project design & planning (in progress)
- **Next up:** finish Phase 0 (git + GitHub repo), then Phase −1 environment setup (install Java 25,
  Node 24, Docker), then Phase 1 slice 1 (Spring Boot skeleton + Postgres in Docker Compose).

## Log (newest first)

### 2026-09-12 — Phase 0 started
- Created clean repo root `~/Desktop/healthcloud` (lowercase, no space).
- Renamed `documents/` → `docs/`; source-of-truth PDF now at `docs/source-of-truth/`.
- Built full monorepo skeleton (backend/frontend/worker/infrastructure/api/docs/synthetic-data/scripts/.github).
- Wrote `CLAUDE.md` (rulebook), `docs/PLAN.md` (roadmap), this `docs/PROGRESS.md`.
- Drafted first ADRs (001 modular monolith, 002 shared-DB multi-tenancy, 004 Cognito+BFF) + ADR template/index.
- Added `README.md`, `.gitignore`, `docker-compose.yml` placeholder, OpenAPI + event-catalog stubs.
- **Pending in this phase:** `git init` + first commit + create private GitHub repo + push.

## Environment status (2026-09-12)
- ✅ Git, GitHub CLI (logged in as `Nikhil-Oggu`), VS Code, git identity all set.
- ⬜ Not yet installed: Java 25, Maven, Node 24, Docker Desktop (needed from Phase 1 — done in the "Phase −1" setup session).

## Definition of "slice done"
Functionality works + negative/security cases pass + tests written + docs updated + committed + this file updated.
