# ADR-012 — ECS Fargate Target Deployment
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 10)

## Context
The backend is a production-style Spring Boot (JVM) application. Deploying it to AWS raised a choice: keep it as a
long-running container, or re-platform it to a serverless runtime (e.g. Lambda) chiefly to minimize idle cost.
Re-platforming a stateful, session-holding Spring BFF to Lambda would mean cold starts, a different runtime model,
and design compromises — changing *what the project is* to chase a cost number.

## Decision
Deploy the app as **containers on ECS Fargate**, running the **same images built for local/CI**, rather than
changing the backend runtime to serverless solely for cost. One Fargate task holds both containers (backend + nginx)
over `localhost`, on **ARM64/Graviton**. Cost is instead controlled by *when* it runs, not by degrading the runtime:
the stack is stood up on demand and destroyed after evidence capture (see ADR-015).

## Consequences
- ✅ The deployed artifact is the real production-shaped app (JVM, sessions, BFF) — no runtime rewrite, no cold-start
  compromises; the image runs unchanged from laptop to cloud.
- ✅ ARM64/Graviton trims ~20% off compute cost without any design change.
- ⚠️ Fargate bills per running hour (unlike scale-to-zero serverless), so an always-on deployment would cost money —
  mitigated by the on-demand apply→evidence→destroy model (ADR-015), not by weakening the stack.
- ⚠️ Managing a task/service/ALB is more moving parts than a function; acceptable for a production-style demo.
