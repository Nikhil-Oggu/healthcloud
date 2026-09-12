# HealthCloud — Master Plan & Claude Code Operating Manual

## Context

You have a finalized 111-page source-of-truth for **HealthCloud: a Consent-Aware Care
Coordination & Claims Platform** — a large, production-style, portfolio-grade healthcare
system built on **synthetic data only**. The design is complete and frozen; nothing about
*what* to build is unresolved. Your problem is different and entirely reasonable: you are
**new to Claude Code** and don't yet know *how to actually drive me to build a project this
big* — how to start, how to say "build this," how to continue across days without getting lost.

This document is that missing manual. It has two halves:
1. **How to work with Claude Code** (the operating manual — read this first, it removes the confusion).
2. **The build roadmap** (Phase 0 → Phase 12, mapped to concrete Claude Code sessions and the exact prompts to type).

You chose: **master plan only** (no code yet), **nothing installed yet** (setup checklist included),
**local-first** (build & test locally with Docker; touch AWS only on-demand, late). This plan reflects all three.

**Important expectation-setting:** this is a 12-phase system that a professional would build over
**weeks to a few months**. That is normal and fine. You do not build it in one sitting. You build it
one small, verified slice at a time — and Claude Code is designed exactly for that rhythm.

---

## PART A — How to work with Claude Code (your operating manual)

### A.1 The core loop (this is the whole secret)

Every piece of this project follows the same four-step loop. Once this clicks, your confusion disappears:

```
1. PLAN    → Ask me to plan a small slice. I research + propose. You approve.
2. BUILD   → I write the code for that slice.
3. VERIFY  → We run it / test it. Green = good. Red = I fix it.
4. COMMIT  → Save it to git with a clear message. Then start the next slice.
```

You never say "build HealthCloud." You say "build **the next slice**," over and over. The slices
are already defined for you in Part B. Your job is mostly to say **"do the next one"** and to
**look at the result and tell me if it's what you wanted.**

### A.2 How you actually talk to me — copy/paste starter phrases

You were worried about *how to phrase things*. You don't need special syntax. Plain English is enough.
Here are the phrases you'll reuse constantly:

- **Start a slice:** *"Read PLAN.md and CLAUDE.md. Let's do Phase 1, Step 1. Plan it first, then wait for my approval."*
- **Approve and go:** *"Looks good, go ahead."* / *"Approved, build it."*
- **Continue:** *"Continue with the next step."* / *"Do the next slice."*
- **Course-correct:** *"That's not what I meant — I wanted X, not Y. Redo just that part."*
- **When lost:** *"Where are we in the plan? What's done and what's next?"*
- **Verify:** *"Run it and show me it works."* / *"Write and run the tests for this."*
- **Save:** *"Commit this with a good message."*
- **End of day:** *"Summarize what we did today and what the next session should start with. Update PROGRESS.md."*

You do **not** need to remember the whole design — I read it from the repo each session. You mostly
just steer: *next*, *yes*, *no, change this*, *show me*.

### A.3 Claude Code features you'll use, and when

| Feature | What it is | When you use it in HealthCloud |
|---|---|---|
| **Plan Mode** (this mode) | I research & propose before touching code; you approve first | Start of every phase and every non-trivial slice. Prevents me from running off in the wrong direction. |
| **CLAUDE.md** | A file at the repo root I read automatically every session | Holds the project's "rules of the road" (stack versions, conventions, the synthetic-data boundary). We create it in Phase 0. Keeps me consistent across weeks. |
| **PLAN.md / PROGRESS.md** | Plain markdown files in the repo | PLAN.md = the roadmap (from this doc). PROGRESS.md = a running log of what's done. Reading these is how I "remember" across sessions. |
| **Task list / TODOs** | I track multi-step work as a live checklist | Automatically used inside a phase so you can see the steps tick off. |
| **Subagents** (Explore / Plan / general) | Helpers I spawn for big research/search jobs | Later phases, when the codebase is large and I need to search many files. You'll rarely request these directly. |
| **/code-review** | An automated review pass over changed code | After each phase, before committing, to catch bugs & security issues (critical for an auth/consent system). |
| **/security-review** | Security-focused review of pending changes | Phase 3 (consent/authorization) and Phase 7 (break-glass/audit) especially. |
| **Git integration** | I create branches, commits, and (if you want) PRs | Every slice ends in a commit. One branch per phase is a clean rhythm. |
| **Browser tools** | I can open and click through the running app | Verifying frontend screens actually work (e.g. login, dashboards). |
| **Memory** | Long-term notes about *you* and your preferences | I'll remember things like "you're new, explain as you go, local-first." Already noted. |
| **Skills** (`/pdf`, `/docx`, `/dataviz`, etc.) | Packaged capabilities | `/pdf` (already used to read your source-of-truth), `/dataviz` for architecture diagrams later, doc skills for the README/case study in Phase 12. |
| **Artifacts** | Shareable rendered pages/diagrams | Phase 12 portfolio materials, architecture diagrams, the case study. |

**You do not need to learn all of these now.** The only ones you touch early are: Plan Mode,
CLAUDE.md, PLAN.md/PROGRESS.md, and git commits. The rest I bring in when the phase calls for it.

### A.4 How to not get lost across many days

This is the anti-confusion system. Three files in the repo carry all the memory so neither of us
has to hold it in our heads:

- **`CLAUDE.md`** — the rules (stack, conventions, boundaries). I read it automatically.
- **`docs/PLAN.md`** — the full roadmap (Part B of this document, committed into the repo).
- **`docs/PROGRESS.md`** — updated at the end of every session: "Phase X Step Y done, next is Z."

**Every session starts the same way:** *"Read CLAUDE.md, docs/PLAN.md, and docs/PROGRESS.md, then tell
me where we are and propose the next slice."* That single sentence re-orients me completely, even if
it's been two weeks. This is the habit that keeps a months-long project from ever feeling lost.

### A.5 Golden rules for a beginner (so quality stays high)

1. **One slice at a time.** Never let me build three phases at once. Small = verifiable = safe.
2. **Always verify before moving on.** "It compiles" is not "it works." Ask me to run it.
3. **Commit often.** A commit after every green slice means you can always go back.
4. **Ask me to explain.** Say *"explain what you just did in simple terms"* whenever you want. Learning is part of the goal.
5. **Trust the phase order.** The PDF's Phase 0→12 order exists for real dependency reasons. We don't skip ahead.
6. **Never claim results you haven't measured.** The source-of-truth is strict about this (no fake performance/security numbers). We only write down what we actually tested.

---

## PART B — The build roadmap (Phase 0 → 12)

This mirrors the finalized phase plan in the source-of-truth (§58–60). Each phase is a set of
**verified slices**. For each phase you'll open a session and we'll work the slices in order.
**MVP = Phase 0 through Phase 5.** Everything after is "advanced" and can come later.

### Phase −1: Environment setup (because nothing is installed yet)

Before any code, we get your machine ready. This is its own short session. I'll give you exact,
copy-pasteable install commands (Homebrew-based on your Mac) and verify each one:

- **Homebrew** (macOS package manager)
- **Git** + a GitHub account (free)
- **Java 25 LTS** (Temurin) + **Maven** (via wrapper, so minimal global install)
- **Node.js 24 LTS** (for the React frontend)
- **Docker Desktop** (runs PostgreSQL, Kafka, etc. locally — this is what makes "local-first" possible)
- **An IDE** — VS Code (easiest for a beginner) or IntelliJ IDEA Community
- *(AWS account — NOT needed yet. We defer this to Phase 10.)*

**Verification:** each tool prints its version; a "hello world" Docker container runs. Then we move on.

**Your prompt to start:** *"I have nothing installed. Walk me through setting up my Mac for HealthCloud,
one tool at a time, and verify each before the next."*

### Phase 0: Project design & planning (no app code yet)

Goal: lay the repo skeleton and the design artifacts so every later phase has a home. Slices:

1. Create the GitHub repo + local `healthcloud/` folder with the **monorepo structure** from §63
   (`backend/`, `frontend/`, `worker/`, `infrastructure/`, `api/openapi/`, `docs/{architecture,er-diagram,events,threat-model,adr,runbooks,evidence}/`, `synthetic-data/`, `scripts/`, `.github/workflows/`, `docker-compose.yml`, `README.md`).
2. Write **CLAUDE.md** (stack versions, conventions, synthetic-data-only boundary, "no unmeasured claims" rule).
3. Commit **docs/PLAN.md** (this roadmap) and start **docs/PROGRESS.md**.
4. Draft the first **ADRs** (§Appendix A gives all 17 — start with ADR-001 modular monolith, ADR-002 shared-DB tenancy, ADR-004 Cognito+BFF).
5. Draft the initial **OpenAPI** skeleton and **event catalog** placeholders.

**Proof of done:** repo skeleton exists, CLAUDE.md guides me, first commit pushed to GitHub.

### Phase 1: Application foundation & multi-tenant identity  ← first real running app

Goal: a runnable local app that proves identity, sessions, and tenant isolation.
- **Backend:** Spring Boot 4.1 modular skeleton, Cognito OAuth2/BFF (or a local stand-in for dev), Spring Session JDBC, current-user endpoint, global error handling, tenant/user context, health endpoints; organization/facility/user/role/membership modules.
- **Frontend:** React/TS/Vite shell, routing, session-aware nav, protected routes, CSRF, role-aware menus, loading/error/denied states.
- **Database:** Flyway migrations for `organization`, `facility`, `app_user`, `organization_membership`, `facility_membership`, `role`, `user_role`, session tables + synthetic seed foundation.
- **DevOps:** `docker-compose.yml` (Postgres + app), Maven/npm builds, first GitHub Actions pipeline.

**Proof of done (from §60):** MFA login works; **NorthCare user cannot access Green Valley data**; a browser cannot pick a different tenant; Docker Compose + CI succeed.

### Phase 2: Core care-coordination workflow
Patient/provider profiles, assignments, service requests with the full **state machine**
(DRAFT→SUBMITTED→TRIAGED→ASSIGNED→UNDER_REVIEW→NEEDS_INFORMATION→APPROVED/REJECTED/CANCELLED/CLOSED),
comments/timeline, optimistic locking. Dashboards for patient/provider/coordinator.
**Proof:** valid transitions work; invalid/concurrent transitions don't silently overwrite.

### Phase 3: Consent, authorization, privacy & documents  ← the flagship differentiator
Consent lifecycle/versioning, central hybrid RBAC+attribute **policy evaluator**, field-level
visibility/masking, secure S3 document upload/download authorization, malware-scan/quarantine
(fake scanner locally), audit integration. **Run `/security-review` here.**
**Proof:** two users with the *same role* get *different results* because relationship/consent/purpose differ; no sensitive data leaks into logs.

### Phase 4: Clinical context & basic claims intake
Limited clinical summaries (diagnosis/treatment/encounter, ICD-10/HCPCS code model), claim
header + lines, plan/eligibility foundations, submission/validation workflow.
**Proof:** a claims reviewer sees claim-relevant data *without* unrestricted medical context.

### Phase 5: Basic synthetic claims-adjudication engine  ← completes the MVP
Deterministic, explainable rule engine (eligibility, coverage, allowed amount, deductible, copay,
coinsurance, exclusions), line + claim outcomes, immutable adjudication versions.
**Proof:** for any decision, the platform can show which rules ran and how every amount was computed.
**🎯 Reaching the end of Phase 5 = a demonstrable MVP.** Strong stopping/portfolio point if you want one.

### Phases 6–11: Advanced (build after MVP is solid)
- **6 – Advanced claims:** provider network, prior auth, referrals, anomaly signals, manual review, appeals, reprocessing.
- **7 – Advanced security/governance:** break-glass emergency access, per-org HMAC tamper-evident audit chains, access reviews, retention. (`/security-review` again.)
- **8 – Event-driven:** transactional outbox + Kafka, idempotency, retry/backoff, DLQ, replay. (Local Kafka via Docker.)
- **9 – Search/reporting/accessibility:** filters, pagination, CSV export with masking, WCAG 2.2 AA.
- **10 – Cloud deployment & CI/CD:** *now* we introduce AWS — Terraform, ECS Fargate, RDS, S3/CloudFront, Cognito, MSK. **On-demand**: stand it up, capture evidence, tear it down to control cost.
- **11 – Observability & recovery:** dashboards, alerts, tracing, backup/restore drills, runbooks.

### Phase 12: Final validation & portfolio presentation
Full test suite, README as an engineering case study (§64), architecture diagrams (`/dataviz`),
ADRs, threat model, demo video, resume bullets, sanitized Evidence Pack. This is where the project
becomes something you can *show and defend in interviews*.

---

## Verification approach (how we prove each slice actually works)

- **Local run:** Docker Compose brings up the app; I run it and we click through in the browser.
- **Automated tests:** JUnit 5 / Testcontainers (backend), Vitest / React Testing Library (frontend), Playwright (E2E) — written *alongside* each phase, not at the end.
- **Negative/security tests are first-class:** cross-tenant denial, consent denial, invalid transitions — these are the *proof*, per §60 acceptance criteria.
- **Review gates:** `/code-review` after each phase; `/security-review` on Phases 3 and 7.
- **Nothing is "done" until its §60 acceptance criterion passes** and PROGRESS.md is updated.

## Suggested first three sessions

1. **Session 1 — Setup:** *"I have nothing installed. Set up my Mac for HealthCloud, one tool at a time, verifying each."*
2. **Session 2 — Phase 0:** *"Create the HealthCloud repo skeleton, CLAUDE.md, docs/PLAN.md and PROGRESS.md, and the first ADRs. Plan it first."*
3. **Session 3 — Phase 1, slice 1:** *"Read CLAUDE.md and docs/PLAN.md. Start Phase 1: Spring Boot skeleton + Postgres in Docker Compose. Plan first, then build."*

From there the rhythm is always the same: **read the files → plan the next slice → build → verify → commit → update PROGRESS.md.**

---

## PART C — Custom commands, subagents & hooks (your automation toolkit)

### C.0 The mental model (four different tools — don't mix them up)

| Mechanism | What it actually is | Who triggers it | Lives in |
|---|---|---|---|
| **Slash command** | A saved *prompt/instruction template* — "do this task the way I scripted it" | **You** (`/name`) | `.claude/commands/name.md` |
| **Subagent** | A *separate AI worker* with its own fresh context + instructions/tools, delegated a job | Claude (or you, by name) | `.claude/agents/name.md` |
| **Hook** | A *dumb shell script* the system runs automatically on an event — **not AI** | System, on an event | `.claude/settings.json` |
| **`/init`, `/compact`, `/clear`** | Built-in CLI commands | You | (built in) |

**Key rule:** `CLAUDE.md` is the project **rulebook (stable)**; `docs/PROGRESS.md` is the **diary (changes every session)**. Never conflate them.

### C.1 Verdicts on the user's six ideas

1. **`/init`** — ✅ Useful. Correction: it scans *existing code* to write CLAUDE.md, so run it from **Phase 1+**, not on an empty repo. It documents structure/rules; it does **not** remember conversation history — that's PROGRESS.md's job. Hand-write a small CLAUDE.md in Phase 0, enrich with `/init` later.
2. **Custom "learning capture" command** — ⭐ Excellent, adopt as-is. Formalize as **`/capture-module <name>`** → writes `docs/learning/<name>.md` with: *What I built · How it works · Key points · Failures & fixes · Interview Q&A (beginner/intermediate/advanced)*. Doubles as interview prep.
3. **`/compact`** — ✅ Correct. Use manually at clean breakpoints (after a committed slice), never mid-task; update PROGRESS.md first. Prefer `/clear` when starting a fresh slice (context restores from CLAUDE.md + PROGRESS.md).
4. **Hook to auto-update CLAUDE.md** — ⚠️ **Change this.** Hooks run *dumb scripts*, not intelligent writes; auto-rewriting CLAUDE.md causes churn/bloat. Instead: keep the diary in PROGRESS.md via the `/wrap` command, and use hooks only for **deterministic** work (format/compile after edits; Stop-hook reminder to commit; guard forbidden content).
5. **Custom code + security reviewer subagents** — ✅ Good, but built-in `/code-review` and `/security-review` already exist. Make yours **HealthCloud-specific** (tenant isolation, consent/purpose, field masking, audit completeness, no-sensitive-data-in-logs, idempotency, optimistic locking). Use built-ins Phases 1–2; introduce custom ones at **Phase 3**.

### C.2 Recommended additions

- **`/status`** (session-start): reads CLAUDE.md + docs/PLAN.md + docs/PROGRESS.md, reports where we are, proposes the next slice. *Highest value, trivial.*
- **`/wrap`** (session-end): updates PROGRESS.md, summarizes changes, lists next steps, reminds to commit.
- **`/adr <title>`**: scaffolds a new ADR in `docs/adr/` (project needs ~17).
- **Format/compile hook** (PostToolUse): auto-format (Spotless/Prettier) + compile after edits.
- **(Later, optional) synthetic-data guard hook** (PreToolUse): block edits containing real-PHI-looking patterns or "HIPAA-certified" claims — enforces the PDF's hard rules.

### C.3 Adoption order (complexity/risk rises: commands → subagents → hooks)

- **Phase 0:** hand-write CLAUDE.md; create `/status`, `/wrap`, `/capture-module`, `/adr`.
- **Phase 1:** run `/init` to enrich CLAUDE.md; add the format/compile hook.
- **Phase 3:** add custom code-reviewer + security-reviewer subagents (project-specific checklists).
- **Later/optional:** synthetic-data guard hook; any others as needed.

### C.4 How to create each (mechanics)

- **Slash command:** create `.claude/commands/<name>.md`. Body = the instructions I follow when you type `/<name>`. Optional YAML frontmatter (`description`, `argument-hint`) and `$ARGUMENTS` placeholder for input. Personal (all projects): `~/.claude/commands/`.
- **Subagent:** create `.claude/agents/<name>.md` with frontmatter (`name`, `description`, `tools`, optional `model`) + the agent's system instructions/checklist below it.
- **Hook:** add to `.claude/settings.json` under `hooks`, keyed by event (`PostToolUse`, `Stop`, `PreToolUse`, `SessionStart`, `PreCompact`…), each entry running a shell command. Use the **`update-config` skill** to edit settings safely.
- Everything above is committed to the repo, so the toolkit travels with the project and works on any machine.
