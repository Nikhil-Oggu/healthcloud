# CLAUDE.md — HealthCloud project rulebook

> This file is read automatically at the start of every session. It holds the **stable rules**
> of the project. It is NOT a diary — what we did and what's next lives in `docs/PROGRESS.md`.

## What this project is
**HealthCloud: Consent-Aware Care Coordination & Claims Platform** — a multi-tenant, production-style
healthcare portfolio application built on **synthetic data only**. Full frozen design is in
`docs/source-of-truth/HealthCloud_Final_Source_of_Truth.pdf` (the single source of truth for every
*what-to-build* decision). The roadmap is `docs/PLAN.md`.

## Non-negotiable rules (from the source-of-truth)
1. **Synthetic data only.** No real patient, employer, client, or production data — ever.
2. **No unmeasured claims.** Never state a performance, security, uptime, cost, or coverage number
   that hasn't actually been measured. Write "target" vs "measured" explicitly.
3. **Healthcare-inspired, HIPAA-aligned — NOT certified.** Never describe HealthCloud as
   HIPAA-certified or production healthcare software.
4. **Backend is the only security boundary.** The frontend may hide/disable UI, but every protected
   operation is authorized on the backend. Never trust browser-supplied tenant/org IDs.
5. **No sensitive data in logs, errors, or event payloads.**

## Working rhythm (how we build)
- **One small verified slice at a time.** Never build multiple phases at once.
- Loop: **read the 3 files → plan the slice → build → verify (run/test) → commit → update PROGRESS.md.**
- The user is new to Claude Code: explain what was done in plain terms; go step by step.
- Every session starts by reading `CLAUDE.md`, `docs/PLAN.md`, and `docs/PROGRESS.md`.

## Build order (MVP = Phase 0–5)
0 Design/scaffold · 1 Foundation & multi-tenant identity · 2 Care-coordination workflow ·
3 Consent/authorization/privacy/documents · 4 Clinical context & claims intake ·
5 Basic adjudication engine  ← **MVP ends here** · 6 Advanced claims · 7 Security/governance ·
8 Event-driven (outbox/Kafka) · 9 Search/reporting/accessibility · 10 AWS deploy & CI/CD ·
11 Observability & recovery · 12 Final validation & portfolio.
**Do not start a phase before its dependencies are stable.**

## Technology stack (frozen baseline — §29)
- **Backend:** Java 25 LTS, Spring Boot 4.1.0, Maven (wrapper), Spring Security, Spring Data JPA,
  Spring Session JDBC, Spring Kafka.
- **Database:** PostgreSQL 17.10, Flyway 12.4 migrations.
- **Frontend:** Node 24 LTS, React 19.2, TypeScript 6.0, Vite 8, React Router 7, TanStack Query 5,
  React Hook Form 7 + Zod 4, Material UI 9.
- **Messaging:** Apache Kafka (KRaft locally; MSK Serverless in AWS validation).
- **Auth:** Amazon Cognito (OIDC auth-code) + Spring Boot BFF; secure HttpOnly session cookie + CSRF.
- **Documents:** private S3 + PostgreSQL metadata. **Cloud:** ECS Fargate, RDS, S3/CloudFront, Terraform.
- **Testing:** JUnit 5, Testcontainers, MockMvc, Mockito (backend); Vitest, React Testing Library
  (frontend); Playwright + axe-core (E2E/accessibility); k6 (perf). **Security:** CodeQL, Dependabot, Trivy, OWASP ZAP.
- Versions are the frozen baseline; patch/minor updates OK via lockfiles + CI. Major upgrades need an ADR.

## Architecture conventions (§31)
- **Modular monolith** (no microservices to start). Shared PostgreSQL DB with an `organization_id`
  tenant key on tenant-owned rows; tenant context is **derived on the backend**, never from the client.
- **Thin controllers.** Business rules, authorization, state transitions, claim math, audit creation,
  and event creation live in dedicated service/domain components. Repositories are tenant-safe by design.
- **One transaction** for important state changes: domain change + status/version history + audit event
  + outbox event, all atomic (realized in Phase 8 — see the `outbox` package). Kafka publish happens only after
  commit, via the outbox relay.
- **Concurrency:** optimistic locking (version columns) for requests/claims/consent/assignments;
  row locks for financial accumulators.
- **Idempotency:** retriable commands (create request, submit claim, start adjudication) require an Idempotency-Key.
- **Errors:** consistent shape `{code, message, correlationId, details}`; secure 404 for existence-sensitive denials.

## How to build, run & test (local)
Non-interactive shells must set the toolchain first (interactive terminals get it from `~/.zshrc`):
```
export JAVA_HOME="/opt/homebrew/opt/openjdk@25"
export PATH="/opt/homebrew/opt/openjdk@25/bin:/usr/local/bin:/opt/homebrew/bin:$HOME/.docker/bin:$PATH"
```
- **Infra up:** `docker compose up -d postgres kafka` (Kafka in KRaft mode, Phase 8)  ·  **DB reset (reseed):**
  `./scripts/db-reset.sh` · Tests use Testcontainers, not this compose stack, so it need not be running for `verify`.
- **DB backup / restore drill (Phase 11 slice 6):** `./scripts/db-backup.sh` dumps to `var/backups/` (git-ignored);
  `./scripts/db-restore-drill.sh` rehearses recovery (backup → restore into a scratch DB → verify row counts →
  PASS/FAIL) against the running compose postgres. See `docs/runbooks/backup-and-restore.md`.
- **Run app (seeds demo data):** `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
- **Run app with real Cognito login (Phase 10 slice 12):** `export COGNITO_CLIENT_SECRET=$(cd infrastructure/terraform
  && terraform output -raw cognito_client_secret)` then `cd backend && SPRING_PROFILES_ACTIVE=local,cognito ./mvnw
  spring-boot:run`; open `http://localhost:8080/oauth2/authorization/cognito` (redirects to the Cognito hosted login).
  Needs the demo users to have a password (`aws cognito-idp admin-set-user-password --user-pool-id <pool> --username
  <email> --password '…' --permanent`). Without the `cognito` profile / `COGNITO_*` env, OIDC login is simply off.
- **Container images (Phase 10 slices 1 + 3):** `docker build -t healthcloud-backend:local ./backend` and
  `docker build -t healthcloud-frontend:local ./frontend` build the production images. `docker compose
  --profile full up -d postgres backend frontend` runs the whole app as containers — browse at
  **http://localhost:8081** (nginx serves the SPA + reverse-proxies `/api`+`/actuator` to `backend`); the
  backend uses the default Spring profile (Flyway migrates, no seed/dev-login; add `SPRING_PROFILES_ACTIVE: local`
  to the `backend` service to seed). `--profile full down` to stop. Everyday `up -d postgres kafka` is unchanged.
- **Observability stack (Phase 11 slices 2–3):** `docker compose --profile observability up -d prometheus grafana
  jaeger` runs local **Prometheus** (`:9090`), **Grafana** (`:3000`, anonymous Viewer, auto-provisioned "HealthCloud
  Overview" dashboard) and **Jaeger** (`:16686`). Opt-in behind the `observability` profile, so everyday `up -d
  postgres kafka` is unchanged. Prometheus scrapes `host.docker.internal:8080/actuator/prometheus` (works whether
  the app runs via `mvnw` or the `backend` container); the app exports traces over OTLP to Jaeger. Config lives in
  `infrastructure/observability/`. `--profile observability down` to stop.
- **Tests:** `cd backend && ./mvnw test` (uses Testcontainers → Docker must be running)
- **Health:** `curl localhost:8080/actuator/health` · **Login+me:**
  `curl -c j -X POST localhost:8080/api/v1/dev-login --data email=provider@northcare.example.org && curl -b j localhost:8080/api/v1/me`
- The **`local`** OR **`demo`** profile seeds synthetic demo data; **only `local` exposes `dev-login`**
  (both `DevLoginController` and the `SecurityConfig` permit/CSRF-exemption for `/api/v1/dev-login` are
  `local`-gated). The deployed app runs **`demo,cognito`** — seeded users, Cognito the only login path, the
  dev-login bypass absent (auth-hardening slice).
- **CI** (`.github/workflows/ci.yml`, on push + PR to `main`): a **backend** job (Temurin JDK 25 →
  `./mvnw -B verify`; the runner's Docker powers the Testcontainers tests), a **frontend** job
  (Node 24 → `npm ci` → `typecheck` → `test` → `build`), and (Phase 10 slice 2) a **backend-image** job
  (`needs: backend`) that builds the container image via `docker/build-push-action` and **publishes it to
  GHCR** — `ghcr.io/nikhil-oggu/healthcloud-backend`, tags `sha-<short>` (immutable, what a deploy pins to)
  + `latest` — **only on push to `main`**; on a PR it builds but does not push. Auth is the automatic
  `GITHUB_TOKEN` (`packages: write`) — no secrets. `linux/amd64` (ECS Fargate default; multi-arch is a
  follow-up); GHA layer cache (scoped per image) speeds rebuilds. And (Phase 10 slice 4) a **frontend-image** job
  (`needs: frontend`) that mirrors it — builds the frontend image and publishes it to GHCR
  (`ghcr.io/nikhil-oggu/healthcloud-frontend`, same `sha-<short>` + `latest` tags, push only on `main`). Pull with
  `docker pull ghcr.io/nikhil-oggu/healthcloud-backend:latest` (and `…/healthcloud-frontend:latest`) — each package
  may need to be made public once in the repo's Packages settings. Keep all four green — don't merge red.
- **Frontend (needs the backend running):** `cd frontend && npm install` once, then `npm run dev`
  (Vite on **:5173**, proxies `/api` + `/actuator` → `:8080`, so cookies are same-origin — no CORS).
  Checks: `npm run typecheck`, `npm test` (Vitest), `npm run build`. Node runs from `openjdk@25`'s
  sibling `node@24` — use `export PATH="/opt/homebrew/opt/node@24/bin:$PATH"` in non-interactive shells.
- **Infrastructure / Terraform (`infrastructure/terraform/`):** `terraform fmt -check -recursive`, `terraform validate`,
  `terraform plan`. The main config now uses an **S3 remote backend** (Phase 10 slice 6 — bucket
  `healthcloud-tfstate-927747714796`, key `healthcloud/dev/terraform.tfstate`, S3-native locking, no DynamoDB), so
  `init`/`plan` need the configured AWS creds (they read/write remote state). The **main config still declares no
  real resources** (`plan` = outputs only). Offline `validate` still works with `init -backend=false`.
  The state **bucket** was created once by `infrastructure/terraform/bootstrap/` (a small local-state config — run
  once, then left alone; see its README). Terraform CLI via `brew tap hashicorp/tap && brew install hashicorp/tap/terraform`;
  AWS CLI v2 via the **official pkg installer** (NOT Homebrew — the brew awscli had a pyexpat/expat breakage).
  **⚠️ Never run `terraform apply` (beyond the already-applied state bucket) or otherwise create AWS resources without
  explicit user go-ahead — see the deployment rule below.**

## Current implementation (Phase 1–4 COMPLETE; Phase 5 COMPLETE — slices 1–12 done; MVP (Phase 0–5) feature-complete, engine AND UI. Phase 6 (advanced claims) COMPLETE ✅ — slices 1–21 done: prior authorization, wired into adjudication, + prior-auth UI (queue/detail/decisions + request form) + plan-prior-auth-requirement admin card, + referrals (backend: care-coordination aggregate + decision lifecycle; + UI: queue/detail/decisions + request form), + appeals (backend: dispute a claim's decision + resolution lifecycle; + UI: queue/detail/decisions + submit form; + overturn wired into re-adjudication), + claim anomaly signals (backend: a deterministic detector + reviewer scan; + UI: Anomalies card + Scan button on the claim detail page), + claim manual review (backend: open/resolve/cancel a review case on a claim; + UI: queue/detail/decisions + open form), + reprocessing (backend: batch re-adjudication of a coverage plan's claims after a config change; + UI: batch queue/detail + Run form), + provider network (backend: plan network config + a rendering provider on the claim + the engine marks a covered line OUT_OF_NETWORK when its rendering provider is outside the covering plan's network; + UI: plan network admin card + the OUT_OF_NETWORK line chip + the rendering-provider picker on claim create, backed by a GET /api/v1/providers directory read) — this completes Phase 6's advanced-claims areas. **Phase 7 (advanced security/governance) COMPLETE ✅** — slice 1 done: the **security audit event log** (a tenant-owned, append-only, immutable `audit_event` + `AuditService.record(...)` written inside the domain action's own transaction, wired into adjudication + consent revoke; a role-gated read `GET /api/v1/audit-events` for AUDITOR/ORG_ADMIN); slice 2 done: the trail is now **tamper-evident** — a per-org HMAC-SHA256 hash chain (`sequence_no`/`prev_hash`/`entry_hash` appended under a pessimistic-locked `audit_chain_head`, keyed by a per-org key derived from a config master secret held out of the DB) + `GET /api/v1/audit-events/verify` that detects any modified/deleted/reordered/inserted/truncated row; slice 3 done: the **audit-trail UI** (`src/audit/` — an auditor-facing `/audit` page with a recent-events table + a Verify-integrity button, nav gated to AUDITOR/ORG_ADMIN); slice 4 done: **break-glass emergency access** (backend — a PROVIDER self-grants time-boxed access to an unassigned patient with a recorded reason via `POST /api/v1/break-glass`; `PatientAccessGuard` honours a live grant; a `BREAK_GLASS_INVOKED` audit event is written; never crosses tenants); slice 5 done: the **break-glass UI** (`src/breakglass/` — a PROVIDER who hits a patient's secure-404 gets an emergency-access panel to break the glass, plus a `/break-glass` page listing their live grants); slice 6 done: **access review of break-glass** (backend — an admin/auditor lists every live grant via `GET /api/v1/break-glass/all`, and an admin revokes one early via `POST /api/v1/break-glass/{id}/revoke`, cutting off access at once + auditing a `BREAK_GLASS_REVOKED` event); slice 7 done: the **access-review UI** (`src/breakglass/AccessReviewPage.tsx` — an `/access-review` page of all live grants with a Revoke button, nav gated to AUDITOR/ORG_ADMIN); slice 8 done: **data retention** (an ORG_ADMIN purges long-expired `break_glass_grant` rows for their tenant via `POST /api/v1/retention/break-glass/run` past a configurable window, while the audit trail is preserved and the purge is itself audited `RETENTION_PURGED`) — **Phase 7 COMPLETE ✅**. **Phase 8 (event-driven: transactional outbox + Kafka) COMPLETE ✅** — slice 1 done: the **transactional-outbox foundation** (an `outbox_event` row written INSIDE the domain tx via `OutboxService.record(...)`, §31.6; first emitter `AdjudicationService.adjudicate` → a PHI-free `claim.adjudicated` event); slice 2 done: the **outbox relay + Kafka** (a KRaft broker in docker-compose; `OutboxRelay`, a `@Scheduled` poller, publishes committed rows to Kafka after commit and stamps `published_at`); slice 3 done: an **idempotent consumer** (`ClaimAdjudicatedConsumer` `@KafkaListener` → a PHI-free `claim_adjudication_notification` feed, deduped on `event_id`); slice 4 done: consumer **retry/backoff + a dead-letter topic** (a `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → `claim.adjudicated.DLT`; structural failures non-retryable); slice 5 done: **dead-letter drain + inspection** (a `DeadLetterDrainer` into `dead_letter_event` + a role-gated `GET /api/v1/dead-letter-events`); slice 6 done: **DLT replay** (an ORG_ADMIN re-drives a stored dead-letter record back onto its source topic via `POST /api/v1/dead-letter-events/{id}/replay` — a `DeadLetterReplayService` publishes then stamps `replayed_at`/`replayed_by` + audits `DEAD_LETTER_REPLAYED`; safe because the consumer is idempotent); slice 7 done: the **dead-letter / replay UI** (`src/deadletter/` — an ORG_ADMIN-gated `/dead-letters` page listing failed messages with a Replay button) — **Phase 8 event-driven backend + ops UI COMPLETE ✅** (outbox → relay → Kafka → idempotent consumer → retry/DLT → drain → inspect → replay → UI). **Phase 9 (search / reporting / accessibility) COMPLETE ✅** — slices 1–5 done: **server-side pagination + filtering + sorting** rolled out across **every work queue**. Slice 1 introduced the reusable `com.healthcloud.common` foundation (`PageResponse<T>` envelope + `PageRequests` clamp/sort-allowlist helper) and paged the claims queue in SQL; slices 2–3 the claims + prior-auth UIs; slice 4 the referrals/appeals/claim-reviews queues (backend + UI); slice 5 the reprocessing/audit/dead-letter queues (backend + UI — audit also gained a server-side `action` filter + real paging in place of its old 200-row cap). Every list endpoint now returns a `PageResponse` and takes `page`/`size`/`sort` (+ each queue's filter). Slice 6 done: **free-text search** on the claims queue (backend + UI) — a new reusable `com.healthcloud.common.SearchTerms` helper (blank → no filter; escapes SQL `LIKE` wildcards) + a `q` param that runs a case-insensitive "contains" match on the **claim number** (a synthetic, PHI-free identifier — we deliberately do not search patient names) in SQL, and a debounced search box on the claims page. Slice 7 done: **free-text search rolled out to the five other numbered work queues** (prior-auth, referrals, appeals, claim-reviews, reprocessing) — each `q` matches its own PHI-free business number (auth/referral/appeal/review/batch number) in SQL via the same `SearchTerms` helper + a debounced search box. Slice 8 done: **free-text search on the last two queues** (audit → correlationId/resourceId; dead-letters → eventId/messageKey — UUID columns cast to text) — **all eight work queues are now searchable**. Slice 9 done: **CSV export with masking** (backend + UI) — a new reusable `com.healthcloud.common.Csv` formatter (RFC 4180 quoting + formula-injection defusing) and a `GET /api/v1/patients/export.csv` download that **reuses the field-masked patient list read**, so the export inherits the tenant scope + relationship gate + consent masking (a masked DOB is a blank cell, never a raw column read) — the "field-masking meets data export" proof — plus an Export CSV button on the patients page. Slice 10 done: **WCAG 2.2 AA accessibility pass** (frontend) — an automated **axe-core** gate for component/page tests (`src/test/axe.ts` → `expectNoAxeViolations`), plus app-shell fixes in `AppLayout` (a skip-to-content link → `#main`, a `<nav aria-label="Primary">` landmark, the brand demoted from a heading) and a shared **`PageHeading`** (`<h1>`) adopted as every page's single top-level title — verified in-browser (skip link, landmarks, single h1, contrast). **Honest scope:** AA-*aligned* (automated axe A/AA + keyboard/landmark/heading criteria + in-browser contrast on core screens), not certified; a full page-by-page audit + a Playwright+axe E2E gate are documented follow-ups. **Phase 9 COMPLETE ✅** (pagination + search + CSV export + accessibility). **Phase 10 (cloud deployment & CI/CD) COMPLETE ✅ — slices 1–15 done (containerize → CI images → Terraform/remote state → VPC → RDS → ECR → ECS/ALB → Cognito OIDC login live over HTTPS via CloudFront; run on-demand: apply → evidence → destroy). Documented hardening follow-ups: full Cognito RP-initiated logout, lock the ALB to CloudFront origins, optional custom domain** — slice 1 done: **containerize the backend** — a multi-stage `backend/Dockerfile` (build the jar on a full JDK 25, run it on a slim JRE as a non-root `spring` user, with a `/actuator/health` HEALTHCHECK) + a `backend/.dockerignore`, and an **opt-in `backend` service** in `docker-compose.yml` behind the `full` compose profile (so everyday `up -d postgres kafka` is unchanged) wired to the `postgres` container over the compose network. This is the artifact AWS ECS Fargate will run in a later slice; **local-only, zero AWS/cost this slice**. Verified locally: `docker build` packages the jar (tests skipped in the image build — they need Testcontainers/Docker and already gate in CI), then the container boots against Postgres with `/actuator/health` UP (db connected), runs as non-root, default Spring profile (production shape — Flyway migrates, no demo seed / dev-login). Outbox relay disabled for the containerized run (cross-container Kafka is a later slice). Slice 2 done: **build + publish the backend image in CI** — a new `backend-image` job in `.github/workflows/ci.yml` (`needs: backend`, so only a tested image publishes) builds the image on every change and **pushes it to GHCR** (`ghcr.io/nikhil-oggu/healthcloud-backend`, tags `sha-<short>` + `latest`) **only on push to `main`** (PRs build but don't push); auth via the automatic `GITHUB_TOKEN`, no secrets; still zero AWS/cost. Slice 3 done: **containerize the frontend** — a multi-stage `frontend/Dockerfile` (build the SPA with Node, serve it from a **non-root nginx** that reverse-proxies `/api`+`/actuator` to the backend, same-origin — the production mirror of the Vite dev proxy, so session+CSRF cookies stay first-party) + `frontend/default.conf.template` (SPA deep-link fallback + the `${BACKEND_UPSTREAM}`-templated proxy) + `frontend/.dockerignore`, and an opt-in `frontend` service in docker-compose (`full` profile, host :8081 → :8080). The whole app now runs as containers (frontend → backend → postgres); local-only, zero AWS/cost; verified: image builds (77 MB), SPA served, `/actuator/health` + `/api/v1/me` proxied to the backend, deep-link fallback works, runs non-root. Slice 4 done: **publish the frontend image in CI** — a `frontend-image` job in ci.yml (`needs: frontend`) mirrors slice 2, pushing to **GHCR** (`ghcr.io/nikhil-oggu/healthcloud-frontend`, tags `sha-<short>` + `latest`) only on push to `main`; both image builds now use distinct GHA cache scopes; still zero AWS/cost — **both images now build + publish in CI**. Slice 5 done: **Terraform skeleton** (`infrastructure/terraform/` — `versions.tf` pins Terraform ≥1.9 + AWS provider ~>6.0 (locked 6.65.0), `providers.tf` with `default_tags`, `variables.tf` (region/project/environment), `locals.tf` (`name_prefix` + `common_tags`), `outputs.tf`, `backend.tf` documenting the default **local** backend now + the S3 remote backend later, `terraform.tfvars.example`, README). **Declares NO resources → no AWS account, no credentials, $0**; verified `fmt`/`init -backend=false`/`validate`/`plan` all clean (plan = outputs only, no infra). The first AWS-account-needed, cost-incurring step is a later `apply` slice, gated + announced. Slice 6 done: **Terraform remote-state backend (S3)** — a small `infrastructure/terraform/bootstrap/` config (local state) created the state bucket `healthcloud-tfstate-927747714796` (versioning + AES256 + public-access-block), and the main config's `backend.tf` now points at it (`key healthcloud/dev/terraform.tfstate`, `encrypt`, **S3-native `use_lockfile` — no DynamoDB** on TF ≥1.10); `terraform init -migrate-state` moved state to S3 (state file confirmed in the bucket). **This is the first applied AWS resource** — done on the user's real account (id 927747714796, us-east-1, new **Free Plan** with $100 credits: usage above free tier draws from credits, card not charged while credits cover it) with an explicit two-gate go-ahead; cost ~$0 (empty bucket + kilobyte state). The state bucket is **kept** (not part of the deploy/destroy cycle). Next: real infra slices (VPC/networking → RDS → ECR → ECS Fargate → the live app) — **NO MSK** (event-driven proven locally; MSK too costly), on-demand apply→evidence→`destroy`. Slice 7 done: **VPC & networking** (`infrastructure/terraform/network.tf`) — a VPC (`10.0.0.0/16`, DNS on) + internet gateway + **2 public subnets** (ALB/Fargate, `map_public_ip_on_launch`) + **2 private subnets** (RDS) across 2 AZs (us-east-1a/1b) + public route table (0.0.0.0/0 → IGW) + private route table (local-only) + associations. **Deliberately NO NAT Gateway** — public subnets give Fargate its own internet egress, saving ~$32/mo (the private subnets have no egress, which RDS doesn't need). 13 **free** resources, applied ($0), `plan` clean, verified via `aws ec2` (VPC `vpc-0f5ae5185f6331cdb`, 2 public + 2 private subnets, zero NAT). Outputs `vpc_id`/`public_subnet_ids`/`private_subnet_ids` feed the next slices (RDS → ECR → ECS Fargate + ALB → Cognito → CloudFront). Slice 8 done: **RDS PostgreSQL** (`rds.tf`) — a managed **Postgres 17** DB (`db.t4g.micro`, 20 GB gp3, **encrypted at rest**) in the **private subnets** (a DB subnet group across 2 AZs), **not publicly accessible**, master password **managed in Secrets Manager** (`manage_master_user_password` — never in code/state); a security group allows 5432 **only from the VPC CIDR** (tighten to the app SG in the ECS slice). Single-AZ, backups off, `skip_final_snapshot` + `deletion_protection=false` → cheap and clean to `destroy`/recreate. 3 resources applied; verified `available`, `postgres 17.9`, publicly_accessible=false, encrypted=true. **Free-tier-eligible** (db.t4g.micro/20 GB) → ~$0 + ~$0.40/mo for the secret, from credits; on-demand. Outputs `db_address`/`db_port`/`db_name`/`db_master_secret_arn` feed the ECS slice (endpoint `healthcloud-dev-postgres.cyvye82887pw.us-east-1.rds.amazonaws.com`). Slice 9 done: **ECR + images pushed** (`ecr.tf`) — 2 registries `healthcloud-dev-backend`/`healthcloud-dev-frontend` (scan-on-push, keep-last-5 lifecycle policy, `force_delete`) applied ($0); output `ecr_repository_urls`. **Both images are in ECR** (tags `sha-c1e42ab` + `latest`), **built natively as `linux/arm64`** on the Apple-Silicon Mac → **the ECS Fargate slice MUST run ARM64** (Graviton, ~20% cheaper). **Pushed with `crane`, NOT `docker push`:** the backend image (823 MB uncompressed / 257 MB compressed) repeatedly failed `docker push` with `net/http: timeout awaiting response headers` (Docker splits upload bandwidth across parallel layers, so the biggest layer never finished within the HTTP timeout on a home uplink), and Docker Desktop then wedged on a `Docker.raw held / disk image in use` VM lock that only a **Mac restart** cleared. `crane push` (from a `docker save` tarball, after `aws ecr get-login-password | crane auth login`) retries/streams instead of timing out — backend up in ~2 min, frontend in seconds; `crane tag` adds a second tag with no re-upload. **Lesson: on a slow uplink push large images with `crane` (or from CI), not `docker push`.** The repos now hold the images the ECS slice pulls. Slice 10 done: **ECS Fargate + ALB — the app is LIVE on AWS** (`ecs.tf` + `alb.tf`). The app runs as **ONE Fargate task holding both containers** (backend + frontend nginx) talking over `localhost` — the cheapest shape (one task = one compute charge) and the images run **unchanged** (only env vars). Because awsvpc containers share a network namespace and nginx is hard-wired to 8080, the **backend runs on 8081** (`SERVER_PORT`) and nginx proxies `/api`+`/actuator` to `http://localhost:8081` (`BACKEND_UPSTREAM`); the ALB (HTTP :80, public subnets) forwards to the frontend target group (:8080). **ARM64 runtime platform** (the images are arm64). The **RDS master password is injected from Secrets Manager** by the ECS agent (`secrets` → `HEALTHCLOUD_DB_PASSWORD`/`_USER` from the managed secret's JSON keys — never in the task def or state); an **execution role** carries `AmazonECSTaskExecutionRolePolicy` + an inline `GetSecretValue`, and an empty **task role** awaits later slices. Backend env: `SPRING_PROFILES_ACTIVE=local` (dev-login + synthetic seed so the URL is demoable before Cognito) + Kafka relay/consumers **off** (no MSK on AWS). `rds.tf` **tightened** in-place — Postgres 5432 now only from the app SG (kept the SG-level `description` byte-identical so it isn't a replacement). **13 added, 1 changed, 0 destroyed**; verified live end-to-end (health UP w/ db UP, dev-login+`/me`, seeded synthetic patients relationship-gated + consent-masked, browser Patients page). Output `app_url`. **HTTP only** this slice (HTTPS = the CloudFront/Cognito slices). **~$0.08–0.10/hr from credits, card untouched — on-demand: `terraform destroy` to return to ~$0** (state bucket kept). Slice 11 done: **Amazon Cognito user pool — real-auth infrastructure** (`cognito.tf`, INFRA ONLY, **$0**). Replaces the local dev-login with real OIDC (ADR-004: Cognito + Spring BFF), sliced infra-first: an `aws_cognito_user_pool` (email sign-in, MFA **OPTIONAL**/TOTP not enforced, `deletion_protection=INACTIVE`), a **confidential** `aws_cognito_user_pool_client` (`generate_secret=true` for the server-side BFF; OIDC **authorization-code** flow, scopes `openid`/`email`/`profile`; local-dev callback `http://localhost:8080/login/oauth2/code/cognito` — HTTPS URLs come with the deploy slice, Cognito requires HTTPS for non-localhost), a free hosted-UI domain, and **2 synthetic users** (`provider@`/`admin@northcare.example.org`) created with **no password in Terraform** (set post-apply via `aws cognito-idp admin-set-user-password --permanent`, so no secret in state). Applied **via `-target`** (Cognito only) so the torn-down ALB/ECS were NOT recreated. Verified: pool `us-east-1_YA95ksq5k`, client secret present, OIDC discovery works, hosted login renders over HTTPS. Outputs `cognito_user_pool_id`/`cognito_client_id`/`cognito_client_secret`(sensitive)/`cognito_issuer_url`/`cognito_hosted_ui_domain`. Slice 12 done: **backend Spring Security OAuth2 BFF — real Cognito login, proven locally** (backend only, **$0**). Added `spring-boot-starter-oauth2-client`; a `cognito`-profile `application-cognito.yml` (client registration — issuer/client-id/scopes `openid email profile`/`redirect-uri={baseUrl}/login/oauth2/code/cognito`/**`user-name-attribute: email`**; **client secret from `${COGNITO_CLIENT_SECRET}` env, NEVER committed**; client-id/issuer env-overridable); `SecurityConfig` conditionally adds `.oauth2Login(...)` **only when a `ClientRegistrationRepository` bean exists** (via `ObjectProvider` — so offline/local/CI boot on dev-login alone, builds/tests unaffected), permits `/oauth2/**`+`/login/oauth2/**`; and `CognitoOidcUserService` (extends `OidcUserService`) which **rejects a Cognito login with no ACTIVE `AppUser`** (matched by email). **`user-name-attribute: email` makes the OIDC principal name = email, so `UserContextFilter.resolveByEmail` + all role/tenant/§21 logic work UNCHANGED** — Cognito only establishes the session; roles still come from the DB (rule 4; `AppUser.cognitoSub` exists for a future link). dev-login stays under `local`. Verified: `CognitoOidcUserServiceTest` 3/3; ran `SPRING_PROFILES_ACTIVE=local,cognito` vs the real pool → boots, `GET /oauth2/authorization/cognito`→302 to Cognito (PKCE S256), dev-login regression intact. Slice 13 done: **frontend "Sign in with Cognito" — the SPA login button** (frontend + a $0 Cognito client tweak). `LoginPage.tsx` has a primary **"Sign in with Cognito"** button = a full-page link to `/oauth2/authorization/cognito` (a redirect, NOT a fetch); the dev-login dropdown moved under a `import.meta.env.DEV`-gated "Developer sign-in (local only)" section (prod build shows only Cognito). `vite.config.ts` proxies `/oauth2`+`/login/oauth2`→:8080 (scoped, NOT the SPA's own `/login` route). `cognito.tf` adds the Vite dev-server callback/logout URLs (`http://localhost:5173/…`, applied `-target` on the client, in-place $0) — needed because the SPA-proxied flow makes the backend compute the :5173 callback. New `LoginPage.test.tsx`. Verified: typecheck + 184 tests + build green; E2E in-browser the button redirected SPA→proxy→backend→real Cognito login (`redirect_uri=…:5173/login/oauth2/code/cognito`). Deployed nginx must also proxy `/oauth2`+`/login/oauth2`, and the prod HTTPS callback must be registered — slice 14. Slice 14 done: **redeploy the Cognito-capable app on the ALB (HTTP) + OAuth proxy** (the deployed images were pre-Cognito slice-9, so **both were rebuilt arm64 + re-pushed via crane** — backend now has the OAuth2 code + `application-cognito.yml`, frontend the button + nginx proxy). `frontend/default.conf.template` proxies **`/oauth2/`+`/login/oauth2/`** to the backend; the **Cognito client secret is in Secrets Manager** (injected into the task like the DB password); `ecs.tf` task env = `SPRING_PROFILES_ACTIVE=local,cognito` + `COGNITO_CLIENT_ID`/`COGNITO_ISSUER_URI` + `SERVER_FORWARD_HEADERS_STRATEGY=framework` + injected `COGNITO_CLIENT_SECRET`; execution role reads both secrets. `terraform apply` 7 add/2 destroy recreated ALB/ECS. Verified: health UP, `/oauth2/authorization/cognito`→302 to Cognito through the deployed nginx (PKCE), deployed login page shows only "Sign in with Cognito", dev-login regression intact. Login completes over HTTPS in slice 15 (CloudFront — Cognito rejects the ALB's HTTP callback). Restarts ~$0.08/hr. Slice 15 done: **CloudFront HTTPS — the live Cognito login completes over HTTPS** (Cognito arc 11–15 COMPLETE, Terraform-only, no rebuild). `cloudfront.tf` = a distribution over the ALB origin (free `*.cloudfront.net` cert — ACM can't cert the ALB's `*.elb.amazonaws.com` name; `redirect-to-https`; all methods; managed `CachingDisabled` + `AllViewer` so cookies/headers/query forward — required for the session+CSRF cookies + OAuth code/state; `PriceClass_100`). `cognito.tf` adds the CloudFront HTTPS callback/logout URLs. `ecs.tf` **pins** the OIDC redirect_uri to the https CloudFront callback via the Spring relaxed-binding env `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_COGNITO_REDIRECTURI` (behind CloudFront→ALB the ALB hop is HTTP → Spring would else compute an http redirect_uri Cognito rejects). Applied 2 add/2 change/1 destroy (ALB/ECS NOT recreated). App now at **`https://<id>.cloudfront.net`**. Verified over HTTPS: health UP; after the ECS rollout completed, `/oauth2/authorization/cognito`→302 with the pinned https redirect_uri; browser "Sign in with Cognito" reaches the Cognito login. **Gotcha:** during a rolling deploy the OLD task briefly serves the previous behavior — verify only after the deployment reaches COMPLETED. **Auth-hardening slice (post-15):** closed the deployed dev-login bypass (deploy profile `local,cognito` → **`demo,cognito`**: seed without dev-login), **locked the ALB SG to the CloudFront origin-facing prefix list**, and **trimmed the Cognito client auth flows to `ALLOW_REFRESH_TOKEN_AUTH`** — the deployed app is now Cognito-only. Remaining follow-ups: local-only logout, a per-distribution secret origin-verify header on the ALB, optional custom domain, enforce MFA. **Phase 11 (observability & recovery) COMPLETE ✅** — slice 1 done: **metrics foundation** (Micrometer + `/actuator/prometheus`, `local`-only unauthenticated scrape, a common `application` tag, and the first domain counter `healthcloud_adjudications_total` incremented on afterCommit); slice 2 done: **local Prometheus + Grafana dashboards** (the `observability` compose profile + auto-provisioned "HealthCloud Overview" dashboard, HTTP p95 histograms enabled); slice 3 done: **distributed tracing** (Micrometer Tracing + OpenTelemetry → local **Jaeger** over OTLP, sampling 1.0 locally, `@Observed` `adjudicate-claim` span nested under the HTTP span, traceId/spanId in logs, Kafka observation for cross-boundary propagation). slice 4 done: **health & readiness probes + a custom health indicator** (Kubernetes-style
`/actuator/health/{liveness,readiness}` — liveness stays process-only, readiness = `readinessState`+`db` so the
pod leaves the LB when Postgres is down; a custom `OutboxHealthIndicator` surfaces the relay backlog on root
health only, never gating readiness; the container HEALTHCHECK moved to `/actuator/health/liveness`).
slice 5 done: **alert rules** (Prometheus alerting rules in `infrastructure/observability/alert-rules.yml` —
`BackendTargetDown`/`OutboxBacklogHigh`/`HighHttp5xxRate`/`HighRequestLatencyP95`/`JvmHeapHigh`, each over a
real exported metric; a Micrometer gauge `healthcloud.outbox.pending` makes the outbox backlog alertable; no
Alertmanager locally — routing is a documented follow-up).
slice 6 done: **backup & restore drill** (`scripts/db-backup.sh` + `scripts/db-restore-drill.sh` — a
non-destructive rehearsal that backs up, restores into a scratch DB, and verifies every table's row count
matches; runbook `docs/runbooks/backup-and-restore.md`; the RDS PITR/snapshot equivalent is an on-demand
follow-up); slice 7 done: **runbooks** (`docs/runbooks/` — a README index + triage workflow, an
`alert-response.md` with a section per alert, each wired from the rule's `runbook` annotation).
See the Observability conventions section. **Phase 11 COMPLETE ✅** (metrics → dashboards → tracing → health/
readiness probes → alert rules → backup/restore drill → runbooks). Documented follow-ups: wire Prometheus/Jaeger
to the AWS deployment, Alertmanager routing, RDS PITR/snapshot DR — all on-demand + approval-gated. See docs/PROGRESS.md for status)
- **Backend packages** under `com.healthcloud`: `organization` (Organization, Facility, FacilityMembership),
  `identity` (AppUser, Role, OrganizationMembership, UserRole; plus the **provider directory** read
  `GET /api/v1/providers` — `ProviderController`/`ProviderDirectoryService` list the caller's tenant's active
  PROVIDERs as `ProviderDto{userId, fullName}`, gated to the claim-create roles PROVIDER/CARE_COORDINATOR/ORG_ADMIN
  so a PATIENT/CLAIMS_REVIEWER can't enumerate staff; read-only over existing tables, no migration — Phase 6
  slice 21, it backs the rendering-provider picker on claim create), `auth` (SecurityConfig, DevLoginController,
  CurrentUserController/Service, CsrfCookieFilter, CognitoOidcUserService; plus **AuthConfigController** — the
  public `GET /api/v1/auth/config` sign-in-capability probe the login page uses to gate the Cognito button),
  `context` (UserContext + UserContextAccessor/Filter),
  `error` (ApiError, ErrorCode, GlobalExceptionHandler, CorrelationId),
  `common` (Phase 9 — the reusable pagination + search foundation: `PageResponse<T>`, a stable page envelope
  `{content, page, size, totalElements, totalPages, first, last}` returned by every paged list endpoint (we own
  the JSON shape rather than exposing Spring Data's `PageImpl`), with `of(Page<E>, mapper)` + `empty(pageable)`;
  and `PageRequests.toPageable(page, size, sort, allowedSortFields, defaultSort)`, a pure helper that clamps
  `size` to 1..100 + `page` to ≥0 and **allowlists the sort field** — an unknown field or bad direction is a clean
  400, never a `PropertyReferenceException` 500 or an arbitrary-column sort — see the Pagination convention below;
  and (Phase 9 slice 6) **`SearchTerms.likeContains(raw)`**, a pure free-text-search helper that returns `null`
  for a blank box (→ "no filter") and otherwise a case-insensitive `%…%` `LIKE` pattern with the SQL wildcards
  `\ % _` **escaped** (so a literal `%` a user types matches a percent sign, not the whole table) — paired in the
  query with `escape '\'`);
  and (Phase 9 slice 9) **`Csv`**, a pure CSV formatter (`field(raw)` RFC 4180-quotes a value with a comma/quote/
  line-break + defuses a leading formula trigger `= + - @` with a `'` to prevent spreadsheet CSV-injection;
  `row(cells)` joins fielded cells with commas + a `\r\n` terminator) — it only **formats**, so an export upholds
  field masking by feeding it the same already-masked DTOs the JSON read returns (see the CSV export convention)),
  `patient` (Patient CRUD:
  `GET/POST /api/v1/patients`, `GET/PATCH /api/v1/patients/{id}`, tenant-scoped → secure 404 cross-tenant;
  reads are relationship-gated (providers see only assigned patients; a PATIENT sees only their own profile —
  linked via the nullable `patient.app_user_id`; all via the shared `PatientAccessGuard`) and
  consent-field-masked — see the Authorization-layering + Field-masking conventions below. Also **`GET
  /api/v1/patients/export.csv`** (Phase 9 slice 9 — CSV export/reporting): a `text/csv` attachment download that
  **reuses `listForCurrentTenant()`**, so the export inherits the same tenant scope + relationship gate + consent
  masking as the JSON list — a masked `dateOfBirth` is already `null` in the DTO, so it exports as a blank cell,
  never a raw column read; formatted via `common.Csv` — see the CSV export convention),
  `request` (ServiceRequest + RequestStatusHistory + `RequestTransitions` state machine: `GET/POST
  /api/v1/requests`, `GET /api/v1/requests/{id}`, `PATCH /api/v1/requests/{id}/status`,
  `GET /api/v1/requests/{id}/history`; controlled §14.6 transitions, optimistic-locked, history per move;
  request comments — `POST/GET /api/v1/requests/{id}/comments`, participant-role gated, tenant-scoped;
  and assignment — `GET /api/v1/requests/{id}/assignment`, `GET .../assignable-users`, `PUT .../assignment`,
  coordinator/admin-gated, `RequestAssignmentService`. **Assignment is the only path to ASSIGNED** — it
  advances TRIAGED→ASSIGNED atomically; a bare status PATCH to ASSIGNED is rejected. **A request is
  object/relationship-gated by its patient** (§21 layer 6): every request read (list, `?patientId=`, get,
  history, comments, assignment) and the participant writes (status change, comment) route the request's
  `patientId` through the shared `PatientAccessGuard`, so a PROVIDER reaches only requests about patients they
  are actively assigned to — an unreachable one is a secure 404; the unfiltered list is scoped to their active
  patients; coordinators/admins stay broad. Finer PATIENT-own / CLAIMS_REVIEWER-business-need rules are the
  deferred permission-matrix work),
  `consent` (Phase 3 — ConsentDirective lifecycle §22: `GET/POST /api/v1/patients/{patientId}/consent-directives`,
  `POST .../consent-directives/{id}/revoke`; immutable/versioned per the supersede pattern — recording a change
  supersedes the current directive for a natural key and inserts version+1, revocation flips it to REVOKED;
  writes allowed to staff (CARE_COORDINATOR/ORG_ADMIN) for any patient AND to a **PATIENT for their own record**
  (self-service, §22.1 — the write path routes the patient lookup through `PatientAccessGuard`, so a PATIENT
  touching another patient is a secure 404; providers/reviewers cannot write consent); reads open to any
  same-tenant user with access to the patient. Plus the **consent+purpose
  decision engine** (§22.5): `ConsentPolicy` (a pure policy class), `ConsentPolicyService`, and
  `GET .../consent-directives/decision?purpose=&dataCategory=` — decides GRANT/DENY for the *calling actor*
  by most-specific-tier (PROVIDER>CARE_TEAM>ORGANIZATION), DENY-wins, deny-by-default, with the effective-date
  window re-checked at decision time. **CARE_TEAM scope is evaluated via `CareTeamService.isOnCareTeam`** (active
  provider- or coordinator-assignment to the patient); the pure `ConsentPolicy.decide` takes `actorOnCareTeam`
  as a parameter so it stays DB-free. `ConsentPolicyService.decideForActor(org, actor, patient, purpose,
  category)` is the low-level hook field masking calls (it computes care-team membership too). The consent
  **reads** (`list` + `/decision`) pass the shared `PatientAccessGuard` first, so an unassigned provider gets a
  secure 404 here too),
  `relationship` (Phase 3 — provider↔patient care relationship §14.3: `GET/POST
  /api/v1/patients/{patientId}/provider-assignments`, `POST .../{id}/revoke`; effective-dated, auditable,
  states PENDING/ACTIVE/EXPIRED/REVOKED, at most one current per (patient, provider); coordinator/admin-gated,
  the assignee must be a same-tenant PROVIDER. **Enforces the object/relationship gate** (§21 layer 6): a
  PROVIDER reads only actively-assigned patients — the gate logic lives in the shared `PatientAccessGuard`
  (patient package), which `PatientService`, the consent reads, and this module's `listCurrent` all route
  through; unassigned → secure 404, coordinators/admins broad. Also **`care_coordinator_assignment`** (§14.3,
  the sibling table — `GET/POST /api/v1/patients/{id}/coordinator-assignments`, `POST …/{id}/revoke`; same
  effective-dated/versioned/one-current-per-pair shape, assignee must be a same-tenant CARE_COORDINATOR): the
  two tables together are the **care team** a CARE_TEAM-scoped consent directive applies to, surfaced by
  **`CareTeamService.isOnCareTeam`** and consumed by the consent engine (§22.5). Each table also exposes a
  candidate-picker read for the assignment UI — `GET .../provider-assignments/candidates` and
  `.../coordinator-assignments/candidates` (coordinator/admin-gated, patient-tenant-scoped → secure 404):
  same-tenant users holding the required role, minus anyone already currently assigned, minimum-necessary
  (`AssignmentCandidateDto{userId, fullName}`). `DevDataSeeder` assigns the primary provider (Dana) to 2 of 3
  patients and the coordinator to 2 of 3 (the second provider, Morgan, is left unassigned — see the provider
  network note under `coverage`/`devdata`), and links the `patient@` login to patient Sam Sample (index 0) so a
  PATIENT user has their own profile for self-service),
  `document` (Phase 3 — secure patient documents §19: `GET/POST /api/v1/patients/{id}/documents`,
  `GET .../documents/{docId}/content`. Metadata lives in `patient_document` (tenant key, `patient_id`,
  `file_name`/`content_type`/`size_bytes`, opaque `storage_key`, `scan_status` PENDING/CLEAN/QUARANTINED,
  uploader); the BYTES live behind the **`DocumentStorage`** abstraction — a `LocalFileSystemDocumentStorage`
  stand-in now (dir `healthcloud.documents.dir`, git-ignored `var/`), private S3 at Phase 10 — so nothing else
  knows where bytes live. Upload is multipart, gated to PATIENT (own record) / CARE_COORDINATOR / ORG_ADMIN
  (providers/reviewers → 403), size- and content-type-validated (≤ `healthcloud.documents.max-size-bytes`,
  allowlist pdf/png/jpeg/gif/txt/csv → 400). **Access inherits the object/relationship gate**: every read/write
  routes through `PatientAccessGuard`, so an assigned provider or the patient can download, an unassigned provider
  or another tenant is a secure 404. Bytes never enter a DTO/log/event (§23.4); download re-authorizes then
  streams as an attachment. **Uploads are malware-scanned** (§19): a `DocumentScanner` (the `FakeDocumentScanner`
  flags the EICAR test signature) sets `scan_status` on upload, and `download` withholds anything not CLEAN — a
  QUARANTINED/PENDING document is a 409 `DOCUMENT_NOT_AVAILABLE` (not a secure 404 — the caller already sees it in
  the listing with its status). Scanning is synchronous now; the async event-driven scanner (PENDING → worker
  flips it) is Phase 8),
  `claim` (Phase 4 — the claims-intake aggregate: `POST /api/v1/claims`, `GET /api/v1/claims` (list),
  `GET /api/v1/claims/{claimId}` (header + lines). A `claim` header owns one or more `claim_line` children, each
  billing a **procedure** code (CPT/HCPCS) from the global `medical_code` catalog; money is `BigDecimal` /
  `NUMERIC(12,2)` and the header `totalChargeAmount` is **computed on the backend** from the lines. **Top-level
  but gated by its patient** (like `service_request`, NOT nested): every read routes through `PatientAccessGuard`
  and the list scopes via `accessiblePatientIdsIfGated` — a provider sees only assigned patients' claims, while
  a broad role (coordinator/admin/**claims reviewer**) sees the tenant's claims as a work queue; another tenant's
  claim is a secure 404. Created in `DRAFT` only this slice. Creation is **one transaction** (§31.6 aggregate):
  the header + all lines are written atomically after each line's procedure code is validated against the catalog
  (the system — CPT or HCPCS — is resolved from the code; unknown or a **diagnosis** code → 400
  `VALIDATION_FAILED`; the canonical spelling is stored). Two DB FKs enforce integrity structurally:
  `(patient_id, organization_id)` → patient and `(procedure_code_system, procedure_code)` → the global catalog;
  `claim_number` is unique per tenant (server-allocated `CLM-XXXXXXXX`). **§60 proof:** a claim carries only
  coded, claim-relevant data (procedure codes + amounts + dates) — **no clinical narrative** — so a reviewer
  works claims without unrestricted medical context; the narrative lives (consent-masked) in `clinical_summary`.
  Not consent field-masked. **Rendering provider (Phase 6 slice 18, provider network):** a claim carries an
  optional header-level `renderingProviderId` (the PROVIDER who rendered the service) — nullable, set at creation,
  and when supplied validated as an **active same-tenant PROVIDER** (else 400) via the identity repos, exactly as
  the assignment/network tables do (this is the 3rd copy of that check — a future cleanup can extract a shared
  provider validator — now the 4th copy counting the slice-21 directory read). Exposed as a raw id on
  `ClaimDto`/`ClaimSummaryDto` (like `createdBy`; the UI resolves the name via the `GET /api/v1/providers` directory,
  and it is chosen on claim create through a rendering-provider picker — slice 21). The adjudication engine reads it
  against the covering plan's network to mark OUT_OF_NETWORK lines (slice 19); one rendering provider per claim
  (per-line is a later refinement). **State machine
  (§Phase 4 submission/validation):** `PATCH /api/v1/claims/{id}/status`
  + `GET /api/v1/claims/{id}/history`, driven by the pure `ClaimTransitions` policy class (mirrors
  `RequestTransitions`): DRAFT→SUBMITTED→{ACCEPTED,REJECTED}, plus CANCELLED; the submitter roles
  (PROVIDER-assigned/CARE_COORDINATOR/ORG_ADMIN) submit + cancel, and the **CLAIMS_REVIEWER** (+ORG_ADMIN)
  accept/reject — the reviewer's write action. Same check order as the request machine (exists → reserved →
  legal move → role → reason → validation → optimistic `expectedVersion`), status change + a `claim_status_history`
  row in one tx (null→DRAFT on creation). Submitting **validates** the claim (≥1 line, total > 0 → else 400).
  `ADJUDICATED` is structurally reachable from ACCEPTED but **engine-owned** — a bare status change to it is
  refused (reserved for the Phase-5 adjudication engine, like `ASSIGNED` on requests). Reason required to
  reject/cancel. The claims UI (work queue + detail + lifecycle actions + adjudication) shipped in Phase 5
  (see `src/claims/` under Frontend below)),
  `clinical` (Phase 4 — clinical summaries: `GET/POST /api/v1/patients/{patientId}/clinical-summaries`,
  `GET .../clinical-summaries/{id}`. A short clinical note about a patient encounter, pointing at an ICD-10-CM
  diagnosis from the global `medical_code` catalog. **Tenant-owned + patient-scoped**, so it reuses the whole
  Phase-3 stack rather than adding new machinery: reads/writes route through the shared `PatientAccessGuard`
  (unreachable patient → secure 404), the org is taken from the loaded patient (never the client), and the
  child row FK-with-orgs back to `patient(id, organization_id)` (§32.10). The diagnosis also **FKs the global
  catalog** `(code_system, code)` and is service-validated as an active ICD-10-CM code (unknown/non-diagnosis
  code → 400 `VALIDATION_FAILED`, the canonical spelling is stored). **Consent field masking (§22.5/§23):** the
  free-text `narrative` is the one consent-controlled field (`CLINICAL_CONTEXT`, read purpose fixed to
  `CARE_COORDINATION`), masked deny-by-default via `ConsentPolicyService.decideForActor` — `ClinicalSummaryFieldPolicy`
  is its `PatientFieldPolicy`-shaped map; the DTO blanks it to `null` + lists it in `maskedFields`. The
  structured diagnosis **code stays visible** so a caller sees the coded, claim-relevant diagnosis without the
  unrestricted narrative — the first half of the Phase-4 §60 proof. Writes require PROVIDER (must be actively
  assigned)/CARE_COORDINATOR/ORG_ADMIN; write responses are unmasked. Backend-only so far — no clinical-summaries
  UI yet (the consent-masked narrative surfaces on the patient detail page in a later slice). The broader "a
  CLAIMS_REVIEWER sees claims data *regardless* of consent"
  business-need rule is still the deferred permission-matrix work),
  `coding` (Phase 4 — the medical code catalog: `GET /api/v1/medical-codes?system=&q=` search +
  `GET /api/v1/medical-codes/{system}/{code}` single lookup. ICD-10-CM diagnoses + HCPCS/CPT procedures — the
  shared vocabulary clinical summaries and claim lines reference. **DELIBERATELY GLOBAL reference data, NOT
  tenant-owned** — `medical_code` has no `organization_id`, no `PatientAccessGuard`, no consent (public national
  standards, identical for every tenant; the app's first shared business-reference table, like `role`). Reads
  require an authenticated caller (any role) but are not tenant-scoped; rows are immutable in-app (no `@Version`).
  Search is active-only, matches a code prefix OR a description substring, and is capped at 50. An unknown
  `system` → 400 via the existing type-mismatch handler. `CodeSystem` carries a display `label` + `category`
  (Diagnosis/Procedure). Codes are public reference vocabularies, not PHI — seeding real-format values is fine),
  `coverage` (Phase 4 — coverage plans: `GET/POST /api/v1/coverage-plans`, `GET /api/v1/coverage-plans/{id}`.
  A benefit plan the org administers, holding the parameters the Phase-5 adjudication engine will apply —
  `deductibleAmount`, `coinsuranceRate` (member share after deductible, 0..1), `copayAmount`, optional
  `outOfPocketMax` — plus `planType` (HMO/PPO/EPO/HDHP). **Tenant-owned but NOT patient-scoped** (administrative
  benefit config, not PHI): org-scoped finders, cross-tenant → secure 404, but **no `PatientAccessGuard`**.
  Reads open to any same-tenant authenticated user; **create requires ORG_ADMIN** (403 otherwise); `plan_code`
  unique per tenant (duplicate → 409); `UNIQUE(id, organization_id)` so patient eligibility FKs-with-org.
  Money is `BigDecimal`/`NUMERIC`. **Also `patient_eligibility`** (same package, but patient-scoped):
  `GET/POST /api/v1/patients/{patientId}/eligibility`, `GET .../eligibility/{id}`. A patient's enrollment in a
  coverage plan for an effective-dated period (`effectiveFrom`, nullable `effectiveTo`, a `memberId`), FKs both
  `patient` and `coverage_plan` with-org. Patient-scoped, so reads/writes route through `PatientAccessGuard`
  (unreachable patient → secure 404); **enroll is CARE_COORDINATOR/ORG_ADMIN** (a PROVIDER/PATIENT enroll → 403),
  the plan must be in-tenant (else 400), and periods for a patient are kept **non-overlapping** (enforced in the
  service → 409) so coverage-on-a-date is deterministic. `PatientEligibilityRepository.findCovering(org, patient,
  date)` (also surfaced as `GET .../eligibility?asOf=`) is the hook the Phase-5 adjudication engine calls. Not
  consent field-masked (claims/benefits data, not clinical context). The adjudication math is Phase 5.
  **Also `plan_exclusion`** (Phase 5 slice 4 — procedure codes a plan does NOT cover): `GET/POST
  /api/v1/coverage-plans/{planId}/exclusions`, `DELETE .../exclusions/{id}`. Plan config (tenant-owned, not
  patient-scoped), procedure FKs the global catalog; reads open to same-tenant, **add/remove ORG_ADMIN**,
  duplicate → 409, unknown/non-procedure code → 400. The adjudication engine reads these to mark matching claim
  lines NOT_COVERED. **Also `plan_fee_schedule`** (Phase 5 slice 9 — the allowed amount a plan recognizes per
  procedure): `GET/POST /api/v1/coverage-plans/{planId}/fee-schedule`, `DELETE .../fee-schedule/{id}`; same shape
  as `plan_exclusion` (tenant-owned plan config, procedure FKs the catalog, reads same-tenant, **add/remove
  ORG_ADMIN**, duplicate 409, unknown code 400) plus an `allowed_amount`. The engine reads these to set
  `allowed = min(charge, fee-schedule amount)` for a priced covered line, else `allowed = charge`.
  **Also `plan_prior_auth_requirement`** (Phase 6 slice 2 — procedure codes that REQUIRE prior authorization under
  a plan): `GET/POST /api/v1/coverage-plans/{planId}/prior-auth-requirements`, `DELETE .../{id}`; identical shape
  to `plan_exclusion` (tenant-owned plan config, procedure FKs the catalog, reads same-tenant, **add/remove
  ORG_ADMIN**, duplicate 409, unknown code 400). The engine reads these to mark a covered line `AUTH_REQUIRED`
  when no APPROVED `prior_authorization` covers the service date.
  **Also `plan_network_provider`** (Phase 6 slice 17 — the PROVIDERs in a plan's network, for provider network):
  `GET/POST /api/v1/coverage-plans/{planId}/network-providers`, `GET .../network-providers/candidates`,
  `DELETE .../{id}`. Same tenant-owned plan-config shape as `plan_prior_auth_requirement` (reads same-tenant,
  **add/remove + candidates ORG_ADMIN**, duplicate 409) — but the participant is a **provider (`app_user`)**, not a
  catalog code: because `app_user` is not tenant-keyed there is no FK-with-org on the provider; the service
  validates it is an **active same-tenant PROVIDER** (else 400, no existence leak) via the identity repos, exactly
  as `ProviderPatientAssignmentService` does, and offers a candidate picker (same-tenant PROVIDERs not already in
  the network → `AssignmentCandidateDto`). **The adjudication engine reads this** (slice 19): a covered line on a
  claim whose rendering provider is not in the covering plan's network adjudicates `OUT_OF_NETWORK` (a plan with no
  network rows imposes no restriction; opt-in). Managed in the browser via the coverage-plan detail page's Network
  providers card (slice 20). Immutable (no `@Version`)),
  `adjudication` (Phase 5 — the basic synthetic claims-adjudication engine: `POST /api/v1/claims/{id}/adjudicate`
  + `GET /api/v1/claims/{id}/adjudication`. It turns an **ACCEPTED** claim into a deterministic, explainable
  `adjudication` (header + `adjudication_line` breakdown): it finds the coverage in effect on the claim's
  **service date** (`PatientEligibilityRepository.findCovering`), applies the covering plan's parameters via the
  pure **`AdjudicationCalculator`** (allowed → copay → deductible consumed across the claim's lines →
  coinsurance; money `BigDecimal` scale 2 HALF_UP), and records, per line and in total, allowed / copay /
  deductible-applied / coinsurance / **plan-paid** vs **member-responsibility** — the §60 proof (for any decision,
  which plan applied and how every amount was computed). **Adjudication is a dedicated engine command, not a bare
  status change** (like `ASSIGNED` on a request): the command advances ACCEPTED → ADJUDICATED and writes the
  adjudication + a `claim_status_history` row in **one transaction** (§31.6); a second attempt fails the ACCEPTED
  gate (the claim is now ADJUDICATED) — but that same command **re-adjudicates** it (slice 11, below). A claim
  with **no coverage** on the service date is `DENIED_NO_ELIGIBILITY` (plan pays 0, member responsible for the
  charge) — still a recorded, explainable decision. Authorization is the usual pipeline (§21): tenant → role (**CLAIMS_REVIEWER/ORG_ADMIN**, the reviewer's
  action) → object/relationship (`PatientAccessGuard`, via the claim's patient → secure 404). The record is
  **immutable** and carries `adjudicationVersion` (1 this slice). **The annual deductible carries across claims**
  (slice 2): a **`benefit_accumulator`** row per `(patient, coverage_plan, benefit_year)` tracks `deductible_met`
  (and `out_of_pocket_met`), and the engine reads-and-updates it **inside the adjudication transaction under a
  `PESSIMISTIC_WRITE` lock** (§31 "row locks for financial accumulators") — an **insert-if-absent** (`ON CONFLICT
  DO NOTHING`) guarantees the row before the locked read, so concurrent adjudications for the same patient/plan/year
  serialize without a lost update. The calculator takes the **deductible still remaining** (plan deductible − met);
  `benefit_year` is the claim's service-date calendar year (MVP: plan year = calendar year). **The out-of-pocket
  max is enforced** (slice 3): the calculator caps the member's cost-sharing (copay + deductible + coinsurance) so
  the year's cumulative out-of-pocket cannot exceed the plan's `outOfPocketMax` — the excess shifts to the plan and
  is recorded per line as `oopMaxAppliedAmount` (so `member = copay + deductible + coinsurance − oopMaxApplied`
  reconciles); a null `outOfPocketMax` means no cap. OOP-remaining is carried across claims by the same locked
  accumulator (`out_of_pocket_met`), so once the max is met the plan pays 100%. **Plan exclusions are applied**
  (slice 4): a claim line whose procedure the covering plan excludes (`plan_exclusion`) is `NOT_COVERED` — allowed
  0, plan pays 0, member owes the charge — and, because it skips the cost-sharing math, it does not consume the
  deductible or OOP; the claim is still `ADJUDICATED` (a mix of COVERED and NOT_COVERED lines). **Fee-schedule
  allowed amounts are applied** (slice 9): a covered line whose procedure the covering plan prices
  (`plan_fee_schedule`) is allowed `min(charge, fee-schedule amount)` instead of the full charge — the difference
  is a provider write-off no one pays — and everything downstream (copay/deductible/coinsurance/OOP/split) keys off
  that allowed; an unpriced procedure falls back to `allowed = charge`. **Prior authorization is enforced**
  (Phase 6 slice 2): a covered line whose procedure the covering plan requires prior auth for
  (`plan_prior_auth_requirement`), with no APPROVED `prior_authorization` whose window covers the service date, is
  the new `LineOutcome.AUTH_REQUIRED` — allowed 0, plan pays 0, member owes the charge — and, like an exclusion, it
  skips cost-sharing (no deductible/OOP consumption); exclusion takes precedence over an auth requirement. Approving
  a covering authorization and re-adjudicating (slice 11) flips the line to COVERED. **Provider network is enforced**
  (Phase 6 slice 19): when the covering plan **defines a network** (`plan_network_provider`) and the claim's
  `renderingProviderId` is present but not in it, every non-excluded line is the new `LineOutcome.OUT_OF_NETWORK`
  — allowed 0, plan pays 0, member owes the charge, no deductible/OOP consumption (like an exclusion). It is a
  claim-level determination (one header rendering provider). **Precedence: exclusion > out-of-network > auth
  requirement > covered.** A **null rendering provider** imposes no penalty (can't prove out-of-network) and a
  plan with **no network rows** imposes none (opt-in) — so existing/null-provider claims are unaffected.
  **Re-adjudication is versioned** (slice 11):
  the same `POST .../adjudicate` re-runs on an already-ADJUDICATED claim, writing a **new immutable version**
  (v = prior max + 1) while every prior version is retained and the claim stays ADJUDICATED; the engine first
  **backs out the prior version's benefit-accumulator contribution** (read from that version's own line snapshot,
  `BenefitAccumulator.subtract`) so the deductible/OOP isn't double-counted, then recomputes under current
  coverage/config (a denied claim can flip to covered after a retroactive enrollment). `GET .../adjudication`
  returns the latest version; `GET .../adjudication/versions` lists all, newest first (no status-history row on
  re-adjudication — the status is unchanged; the immutable adjudication row is the record). **Honest MVP
  limitations:** re-adjudication reverses/recomputes *this claim only* (not other claims in the same benefit
  year); no Idempotency-Key (each call is an intentional new version). The version history is surfaced in the
  claims UI (slice 12). Not consent field-masked (claims/benefits data)),
  `priorauth` (Phase 6 — the first advanced-claims aggregate: prior authorization. `GET/POST
  /api/v1/prior-authorizations`, `GET /api/v1/prior-authorizations/{id}`, `PATCH .../{id}/status`,
  `GET .../{id}/history`. A request that a **planned** procedure be pre-approved under a patient's coverage
  before the service is rendered — a single procedure code (FK to the global `medical_code`, must be a PROCEDURE)
  under a `coveragePlan` (FK-with-org, must be in-tenant) for a service window, with a server-allocated
  `auth_number` (`PA-XXXXXXXX`, unique per tenant). **Top-level but gated by its patient** (like `claim`, not
  nested): every read routes through `PatientAccessGuard` and the list scopes via `accessiblePatientIdsIfGated`
  — a provider sees only assigned patients' authorizations, a broad role (coordinator/admin/**claims reviewer**)
  sees the tenant's as a work queue; another tenant's is a secure 404. Carries only coded, claim-relevant data
  (no clinical narrative), so **not consent field-masked** (like a claim). **State machine** driven by the pure
  `PriorAuthTransitions` policy (the 3rd state machine after `RequestTransitions`/`ClaimTransitions`):
  REQUESTED → APPROVED/DENIED (the **CLAIMS_REVIEWER**/ORG_ADMIN decision — a decision stamps
  `decidedBy`/`decidedAt`) or CANCELLED (the requester roles PROVIDER-assigned/CARE_COORDINATOR/ORG_ADMIN);
  APPROVED/DENIED/CANCELLED terminal, reason required to deny/cancel. Same check order as the claim machine
  (exists → legal move → role → reason → optimistic `expectedVersion`), status change + a
  `prior_authorization_status_history` row in one tx (§31.6, null → REQUESTED on creation). Requesting validates
  the procedure (unknown/non-procedure → 400) and the plan (not in-tenant → 400). **Wired into adjudication**
  (slice 2): `existsApprovedCovering(org, patient, plan, system, code, serviceDate)` is the hook the engine calls
  — a covered claim line whose procedure the plan requires prior auth for (see `plan_prior_auth_requirement`),
  with no APPROVED authorization whose window covers the claim's service date, adjudicates `AUTH_REQUIRED`.
  **Honest MVP limitation:** no NEEDS_INFO step, multi-procedure lines, or expiry enforcement. The prior-auth UI
  (queue/detail/decisions + request form) shipped in slices 3–4 (see `src/priorauth/` under Frontend below)),
  `referral` (Phase 6 slice 6 — a care-coordination advanced-claims aggregate: a request that a patient be seen
  by a **specialty**, for a **coded clinical reason** (a `reasonCode` FK to the global `medical_code`, must be an
  ICD-10-CM DIAGNOSIS). `GET/POST /api/v1/referrals`, `GET /api/v1/referrals/{id}`, `PATCH .../{id}/status`,
  `GET .../{id}/history`, with a server-allocated `referral_number` (`REF-XXXXXXXX`, unique per tenant). **Top-level
  but gated by its patient** (like `claim`/`prior_authorization`, not nested): every read routes through
  `PatientAccessGuard` and the list scopes via `accessiblePatientIdsIfGated` — a provider sees only assigned
  patients' referrals, a broad role (coordinator/admin/reviewer) sees the tenant's as a work queue; another
  tenant's is a secure 404. Carries only coded, coordination-relevant data (a specialty + a diagnosis code, **no
  clinical narrative**), so **not consent field-masked** (like a claim). **State machine** driven by the pure
  `ReferralTransitions` policy (the 4th state machine after `RequestTransitions`/`ClaimTransitions`/
  `PriorAuthTransitions`): REQUESTED → APPROVED/DENIED (the **CARE_COORDINATOR**/ORG_ADMIN
  decision — a decision stamps `decidedBy`/`decidedAt`; this deliberately differs from prior auth's CLAIMS_REVIEWER,
  showing the pattern generalizes across roles) or CANCELLED (the requester roles PROVIDER-assigned/
  CARE_COORDINATOR/ORG_ADMIN); APPROVED/DENIED/CANCELLED terminal, reason required to deny/cancel. Same check order
  as the prior-auth machine (exists → legal move → role → reason → optimistic `expectedVersion`), status change + a
  `referral_status_history` row in one tx (§31.6, null → REQUESTED on creation). Requesting validates the reason
  (unknown/non-diagnosis → 400). **Honest MVP limitation:** no named target-provider (`to_provider_id`), no
  SCHEDULED/COMPLETED steps, no expiry. The referral UI (queue/detail/decisions + request form) shipped in
  slice 7 (see `src/referral/` under Frontend below)),
  `appeal` (Phase 6 slice 8 — an advanced-claims aggregate: a dispute of a **claim's** decision. `GET/POST
  /api/v1/appeals`, `GET /api/v1/appeals/{id}`, `PATCH .../{id}/status`, `GET .../{id}/history` (+ `?claimId=`),
  with a server-allocated `appeal_number` (`APL-XXXXXXXX`, unique per tenant). Carries the disputed `claimId`
  (FK-with-org to `claim`), a required free-text `reason` (the rationale — claims-domain data, not PHI), and the
  patient's id **denormalized from the loaded claim** (never the client) so the gate + list scoping reuse the
  patient machinery. **Top-level but gated by its patient** (like `claim`/`prior_authorization`/`referral`): every
  read routes through `PatientAccessGuard` and the list scopes via `accessiblePatientIdsIfGated` — a provider sees
  only assigned patients' appeals, a broad role (coordinator/admin/reviewer) sees the tenant's as a work queue;
  another tenant's is a secure 404. **Not consent field-masked** (like a claim). **State machine** driven by the
  pure `AppealTransitions` policy (the 5th state machine): SUBMITTED → UPHELD/OVERTURNED (the
  **CLAIMS_REVIEWER**/ORG_ADMIN decision — stamps `decidedBy`/`decidedAt`) or WITHDRAWN (the submitter roles
  PROVIDER-assigned/CARE_COORDINATOR/ORG_ADMIN); terminal. **A reason is required on EVERY transition** (a
  per-domain variation — an appeal outcome or withdrawal always needs a rationale). Same check order as the claim
  machine (exists → legal move → role → reason → optimistic `expectedVersion`), status change + an
  `appeal_status_history` row in one tx (§31.6, null → SUBMITTED on creation). **Submit** loads the claim (gated
  by its patient → secure 404), validates it is **appealable** (`ADJUDICATED`/`REJECTED` → else 400
  `VALIDATION_FAILED`) and has **no existing open (SUBMITTED) appeal** (→ 409), then stamps the patient from the
  claim. **Overturn wired into re-adjudication** (slice 10): an OVERTURNED decision on an **ADJUDICATED** claim
  re-runs the adjudication engine (`AdjudicationService.adjudicate`) — appending a new immutable adjudication
  version under current coverage/config — in the **same transaction** as the overturn (§31.6), so the two commit
  or roll back together; the overturning caller is a CLAIMS_REVIEWER/ORG_ADMIN, exactly the roles the engine
  command requires (`AppealService` depends on `AdjudicationService`, no bean cycle). **Honest MVP limitation:** a
  **REJECTED** claim's overturn records the outcome only — re-opening a rejected claim (REJECTED is terminal on the
  claim machine) into the adjudication pipeline is a later slice; no UNDER_REVIEW step. The appeal UI
  (queue/detail/decisions + submit form) shipped in slice 9 (see `src/appeal/` under Frontend below)),
  `anomaly` (Phase 6 slice 11 — claim anomaly signals: an advisory fraud/waste/abuse detection pass over a claim.
  `POST /api/v1/claims/{id}/anomaly-scan` (run the detector) + `GET /api/v1/claims/{id}/anomalies` (read the
  current signals) — nested under the claim it concerns, like adjudication. A scan is the **reviewer's** action
  (CLAIMS_REVIEWER/ORG_ADMIN); the read is open to any same-tenant caller who can reach the claim. **Detection is
  purely additive** — it never touches the claim status or the adjudication math. Tenant-owned + patient-gated via
  the claim: every route routes through `PatientAccessGuard` (another tenant's / an unreachable claim → secure
  404). The decisions live in the pure **`ClaimAnomalyDetector`** (no Spring/DB) — a pure-policy class of a new
  **detector** shape (it emits a list of `DetectedSignal`s rather than gating a transition), with two deterministic,
  explainable heuristics: **`DUPLICATE_CLAIM`** (HIGH — another claim for the same patient shares this claim's
  service date and ≥1 procedure code) and **`HIGH_TOTAL_CHARGE`** (MEDIUM — the backend-computed total exceeds the
  configurable `healthcloud.anomaly.high-total-charge-threshold`, default $5000; a synthetic demo heuristic, not a
  measured fraud model). `ClaimAnomalyService` loads the surrounding facts (sibling claims for the patient, each
  claim's line procedure codes) and applies the detector. Rows (`claim_anomaly_signal`) are **immutable** and carry
  only claims-domain data (type + severity + a PHI-free `detail` naming other claim numbers / procedure codes) — no
  clinical narrative, no patient identifiers — so **not consent field-masked**. A **rescan replaces** the claim's
  signals (delete + insert in one tx), so scanning is **idempotent** (no `@Version`). **Honest MVP limitation:**
  detection is a manual reviewer-triggered scan (no auto-trigger at submit/adjudicate yet). The anomaly UI shipped
  in slice 12 — the **Anomalies card** on the claim detail page (see `src/claims/` under Frontend below)),
  `claimreview` (Phase 6 slice 13 — manual review: a review case a coordinator/reviewer opens on a claim (often
  prompted by anomaly signals) and a reviewer resolves. `GET/POST /api/v1/claim-reviews`, `GET .../{id}`,
  `PATCH .../{id}/status`, `GET .../{id}/history` (+ `?claimId=`, `?status=`), with a server-allocated
  `review_number` (`MRV-XXXXXXXX`, unique per tenant). Carries the reviewed `claimId` (FK-with-org to `claim`), an
  optional `reason` (why opened), a `resolution` (the reviewer's conclusion, set on resolve), and the patient's id
  **denormalized from the loaded claim** (never the client). **Top-level but gated by its patient** (like
  claim/prior_authorization/referral/appeal): every read routes through `PatientAccessGuard` and the list scopes via
  `accessiblePatientIdsIfGated` — a provider sees only assigned patients' reviews, a broad role (coordinator/admin/
  reviewer) sees the tenant's as a work queue; another tenant's is a secure 404. **Not consent field-masked**.
  **State machine** driven by the pure `ClaimReviewTransitions` policy (the 6th state machine): OPEN →
  RESOLVED (the **CLAIMS_REVIEWER**/ORG_ADMIN disposition — stamps `resolvedBy`/`resolvedAt`) or CANCELLED (the
  opener roles **CARE_COORDINATOR**/CLAIMS_REVIEWER/ORG_ADMIN); terminal. **A reason is required on EVERY
  transition** (the resolution conclusion, or a cancellation rationale). Same check order (exists → legal move →
  role → reason → optimistic `expectedVersion`), status change + a `claim_review_status_history` row in one tx
  (§31.6, null → OPEN on creation). **Open** loads the claim (gated by its patient → secure 404) and enforces **at
  most one OPEN review per claim** (a partial unique index `WHERE status='OPEN'` → 409), then stamps the patient
  from the claim. **Honest MVP limitation:** a review is a **tracking** record — opening one neither holds the claim
  nor changes its status (the reviewer still uses accept/reject/adjudicate), and it is not structurally linked to
  specific anomaly signals (signals inform the human). The manual-review UI (queue/detail/decisions + open form)
  shipped in slice 14 (see `src/claimreview/` under Frontend below)),
  `reprocessing` (Phase 6 slice 15 — batch re-adjudication: after a plan-config change (a fixed fee schedule, a
  new exclusion/prior-auth requirement, a retroactive enrollment) an admin/reviewer re-runs a coverage plan's
  claims. `GET/POST /api/v1/reprocessing-batches`, `GET .../{id}`, with a server-allocated `batch_number`
  (`RPB-XXXXXXXX`, unique per tenant). A `reprocessing_batch` (scope = one `coveragePlan`, FK-with-org; status +
  counts) owns one immutable `reprocessing_item` per claim (the claim, `SUCCEEDED`/`FAILED`, the new adjudication
  version on success, a PHI-free message on failure). **It orchestrates only** — it reuses the unchanged slice-11
  re-adjudication path (`AdjudicationService.adjudicate`), changing no adjudication math; each selected claim gets
  a new immutable version. Scope selection = the tenant's ADJUDICATED claims whose **current** adjudication is on
  the plan (via `AdjudicationRepository.findDistinctClaimIdsByCoveragePlan` + a status/current-plan filter). Gated
  to **CLAIMS_REVIEWER/ORG_ADMIN** (the engine command's roles — broad, so every tenant claim is reachable and the
  per-claim re-adjudication's own §21 gate composes cleanly); an in-tenant plan is required (else 400); reads are
  tenant-scoped (another tenant's batch → secure 404). **Not consent field-masked** (claims/benefits data).
  **A job record, NOT a state machine** — MVP runs synchronously and the batch lands `COMPLETED` /
  `COMPLETED_WITH_ERRORS`; there are no client transitions and no status-history table (the batch + its items are
  the record). **Deliberate transaction-shape departure from the one-tx rule** (§31): a batch is many independent
  transactions — `ReprocessingService.createAndRun` runs `@Transactional(propagation = NOT_SUPPORTED)` (no
  surrounding tx), so each `adjudicate(claimId)` (a separate bean's `@Transactional` method) commits/rolls back on
  its own and one claim's failure is caught + recorded, never rolling back the batch or the other claims.
  **Honest MVP limitations:** synchronous (the async/recoverable outbox+worker version is Phase 8 — a crashed
  batch can be left `RUNNING` with no recovery yet); scope is a single coverage plan (no date-range/all-plans); no
  Idempotency-Key (each POST is an intentional new batch); each claim is reversed/recomputed independently (the
  slice-11 limitation carries over). The reprocessing UI (batch queue/detail + Run form) shipped in slice 16 (see
  `src/reprocessing/` under Frontend below)),
  `audit` (Phase 7 slice 1 — the **security audit event log**, the audit-trail foundation for advanced
  security/governance. `GET /api/v1/audit-events` (the tenant's events newest-first, or one resource's history via
  `?resourceType=&resourceId=`) — thin `AuditController`, gated to **AUDITOR/ORG_ADMIN** (a **role-gated list** → a
  disallowed role is a flat **403**, not a secure 404; this is the long-seeded, previously-unused AUDITOR role's first
  job). An `audit_event` row is tenant-owned via `organizationId`, **append-only and immutable** (like
  `claim_anomaly_signal` — no `@Version`, no UPDATE/DELETE path) and carries only PHI-free metadata — a coded
  `action` (`AuditAction`: CLAIM_ADJUDICATED, CONSENT_REVOKED so far), `resourceType`/`resourceId`, an `outcome`
  (`AuditOutcome` SUCCESS/DENIED), the request `correlationId`, and a short non-sensitive `detail` — never a clinical
  narrative or patient identifier (rule 5), so it is **not consent field-masked**. The core is **`AuditService`**:
  `record(action, resourceType, resourceId, outcome, detail)` is called **from inside a domain service's own
  `@Transactional` method**, so the audit row joins that transaction (§31.6) and commits atomically with the change
  it records — or both roll back; it is deliberately **not** `@Transactional` itself (it must join the caller's tx,
  not open its own), and derives tenant + actor from the backend `UserContext` (never the client) and the id from
  `CorrelationId`. This finally realizes the "audit event" the §31.6 one-tx pattern has described aspirationally.
  Wired into two exemplar actions so far — `AdjudicationService.adjudicate` (a money decision) and
  `ConsentDirectiveService.revoke` (a privacy decision); remaining actions get audit events as the log matures in
  later slices. **Tamper-evident (slice 2):** each event is a link in a **per-org HMAC-SHA256 hash chain** — an
  `audit_event` also carries a per-org monotonic `sequenceNo`, the previous event's fingerprint `prevHash`, and its
  own `entryHash = HMAC(orgKey, canonical(event, prevHash))`; change/delete/reorder/insert any row and its fingerprint
  no longer matches and the break cascades to later rows. The **per-org key is derived** (`AuditSigningKeys`:
  `HMAC(masterSecret, orgId)`) from a **master secret held in configuration** (`healthcloud.audit.hmac-secret`,
  env-overridable dev default — a real deployment uses a KMS/HSM, Phase 10), **never in the DB it protects**, so
  tampering with `audit_event` alone can't forge a valid fingerprint. Appends serialize per org via a
  `PESSIMISTIC_WRITE`-locked `audit_chain_head` (insert-if-absent then lock — the `benefit_accumulator` row-lock
  pattern, §31) that holds the chain tip (`lastHash` + `nextSequence`). The canonical serialization + HMAC live in
  the pure, DB-free **`AuditHashChain`** (a policy class, shared by writer and verifier; timestamp fingerprinted as a
  micros-truncated UTC instant so a DB round-trip reproduces it). **`GET /api/v1/audit-events/verify`**
  (AUDITOR/ORG_ADMIN) recomputes the chain in sequence order — position, `prevHash` link, recomputed `entryHash` —
  and cross-checks the head (catching truncation), returning `AuditChainVerificationDto{valid, entriesChecked,
  brokenAtSequence, reason}`. **UI (slice 3):** an auditor-facing **Audit** page — see `src/audit/` under Frontend
  below. The audit trail also records `BREAK_GLASS_INVOKED` / `BREAK_GLASS_REVOKED` events (see the `breakglass`
  package), `RETENTION_PURGED` (see `retention`), and `DEAD_LETTER_REPLAYED` (see `deadletter`, Phase 8 slice 6 —
  `AuditService.RESOURCE_DEAD_LETTER_EVENT`). Audit events are **permanent** — never purged (that would break the hash
  chain); data retention purges only operational data (see the `retention` package)),
  `breakglass` (Phase 7 slice 4 — **break-glass emergency access**, the HIPAA "break the glass" pattern.
  `POST /api/v1/break-glass` (self-grant) + `GET /api/v1/break-glass` (the caller's live grants). A `break_glass_grant`
  is tenant-owned + immutable (created and simply expires — no `@Version`; early admin revocation is a later
  refinement): a **PROVIDER** self-declares **time-boxed** access to a patient they are not assigned to, recording a
  required `reason`; `expiresAt = now + healthcloud.break-glass.grant-duration-minutes` (default 60). It overrides
  **only the object/relationship gate** (§21 layer 6), never tenant isolation. `BreakGlassService.create` is
  PROVIDER-gated and loads the patient **directly** by `(org, id)` — deliberately NOT via `PatientAccessGuard`, since
  the whole point is reaching a patient the guard would 404 on (a cross-tenant/unknown patient is still a secure 404,
  no leak) — then writes the grant + a `BREAK_GLASS_INVOKED` audit event in **one transaction** (§31.6). The audit
  **detail is PHI-free** (grant id + expiry, never the free-text reason, which stays on the grant row for review).
  **`PatientAccessGuard` consults live grants** (see the Authorization-layering convention): a provider-gated caller
  with no assignment is allowed if a live grant exists, and `accessiblePatientIdsIfGated` unions assigned +
  break-glass patient ids — so break-glass reaches the patient's *whole* record (requests/consent/documents/claims),
  since everything patient-gated routes through the guard. The guard depends on `BreakGlassGrantRepository`
  (repository-only → no bean cycle). **Honest limitations:** self-service (no approval — intentional for
  emergencies); audited at invocation, not per subsequent read.
  **Access review (slice 6):** a grant now supports **early revocation** — `revoked_at`/`revoked_by` (V39); "live" =
  `expires_at > now AND revoked_at IS NULL`, and the guard + every active-grant query filter on both, so a revocation
  cuts access off at once. `GET /api/v1/break-glass/all` (AUDITOR/ORG_ADMIN) lists every live grant in the tenant with
  the provider name resolved (`BreakGlassGrantAdminDto`); `POST /api/v1/break-glass/{id}/revoke` (ORG_ADMIN only — an
  auditor is read-only) ends a grant early (409 if not live, secure 404 cross-tenant) and writes a
  `BREAK_GLASS_REVOKED` audit event in one tx. The access-review capability is scoped to break-glass for now (standing
  assignments + role memberships are later slices). **UI (slice 5):** a provider-facing break-glass panel on a
  patient's denied page + a `/break-glass` grants list, plus the **access-review page** (slice 7) — see `src/breakglass/`
  under Frontend below. Long-expired grants are purged by the data-retention job (see the `retention` package); the
  audit events proving break-glass happened are permanent),
  `retention` (Phase 7 slice 8 — **data retention**, the last Phase 7 area: the policy for how long OPERATIONAL data
  lives, in tension with the permanent audit trail. `RetentionService.runBreakGlassPurge()` (ORG_ADMIN, tenant-scoped;
  `POST /api/v1/retention/break-glass/run`) deletes the caller's-tenant `break_glass_grant` rows that expired more than
  `healthcloud.retention.break-glass-days` (default 90) ago — removing the sensitive free-text emergency reason once a
  grant is long expired — and records a `RETENTION_PURGED` audit event in the **same transaction** (§31.6). The audit
  trail is NEVER purged (deleting a row would break the hash chain); a live or recently-expired grant is untouched.
  **Honest limitation:** a manual admin trigger (a scheduled purge is a Phase-8 worker concern); audit-event retention
  is intentionally out of scope. `AuditAction.RETENTION_PURGED` + `AuditService.RESOURCE_BREAK_GLASS_GRANT`),
  `outbox` (Phase 8 slices 1–2 — the **transactional outbox**, the event-driven write side. A Kafka publish cannot
  join a DB transaction (the dual-write problem), so `OutboxService.record(aggregateType, aggregateId, eventType,
  payload)` writes an `outbox_event` row from **inside a domain service's own `@Transactional` method** — it is NOT
  `@Transactional` itself, joining the caller's tx exactly like `AuditService` — so the event commits atomically with
  the domain change (§31.6, the "+ outbox event" half of the one-tx quartet, now real). Payload is Jackson-serialized,
  minimum-necessary + PHI-free (rule 5). First emitter: `AdjudicationService.adjudicate` → a `claim.adjudicated`
  event (`ClaimAdjudicatedEvent` — claim id/number, version, outcome, money split; no patient/clinical data). The
  **`OutboxRelay`** (`OutboxRelayScheduler`, a `@Scheduled` poller gated by `healthcloud.outbox.relay.enabled`,
  `@EnableScheduling` on the app) publishes pending rows (oldest-first, bounded batch) to Kafka AFTER commit — topic =
  `event_type`, key = `aggregate_id`, payload as value, metadata in headers — then stamps `published_at` (a partial
  index backs the poll; `published_at IS NULL` = pending). **At-least-once** delivery; **single-instance** (multi-instance
  needs `SELECT … FOR UPDATE SKIP LOCKED`)),
  `notification` (Phase 8 slices 3–4 — the event-driven **read side**. `ClaimAdjudicatedConsumer` (`@KafkaListener`
  on `claim.adjudicated`, `autoStartup = ${healthcloud.kafka.consumers.enabled:true}`) builds a PHI-free
  `claim_adjudication_notification` feed **purely from the event** (payload + headers), never re-reading the claim
  (loose coupling). **Idempotent** for at-least-once delivery: it skips an event it has already recorded
  (`existsByEventId`) and a `UNIQUE(event_id)` is the backstop (a `DataIntegrityViolationException` is caught → treated
  as processed). `KafkaConsumerErrorConfig` (slice 4) adds a `DefaultErrorHandler` (auto-applied by Boot): a bounded
  `FixedBackOff` retry (`healthcloud.kafka.consumers.retry.max-attempts`/`backoff-ms`) then a
  `DeadLetterPublishingRecoverer` → `<topic>.DLT`; **structural failures** (bad header → `IllegalArgumentException`,
  malformed payload → `JacksonException`) are non-retryable and go straight to the DLT, so a poison record never blocks
  the partition),
  `deadletter` (Phase 8 slices 5–6 — **dead-letter drain + inspection + replay**. `DeadLetterDrainer`
  (`@KafkaListener` on `claim.adjudicated.DLT`) drains failed records into `dead_letter_event` — original
  topic/key/payload, the `eventId`/`organizationId` app headers, and Spring's `kafka_dlt-*` failure metadata
  (exception class + message) — turning "what's dead-lettered" into an ordinary read. Idempotent via
  `UNIQUE(dlt_topic, dlt_partition, dlt_offset)`. `GET /api/v1/dead-letter-events` (ORG_ADMIN, tenant-scoped;
  role-gated list → flat 403). **Replay (slice 6):** an ORG_ADMIN re-drives a stored record back onto its source topic
  via `POST /api/v1/dead-letter-events/{id}/replay`. `DeadLetterReplayService` runs `@Transactional(NOT_SUPPORTED)`
  (no ambient tx around the Kafka send — the reprocessing-orchestrator shape): role-gate, load tenant-scoped (secure
  404), refuse an already-replayed record (409), publish the original key/payload + the consumer's required
  `eventId`/`organizationId` headers, then delegate to `DeadLetterService.finalizeReplay` (a separate bean's
  `@Transactional`, so the annotation is honoured) which stamps the record replayed (`replayed_at`/`replayed_by`, V43,
  a one-way lifecycle stamp — no `@Version`, like break-glass revoke) **and** writes a `DEAD_LETTER_REPLAYED` audit
  event atomically (§31.6). **Publish-first, then mark**: a crash after the send just re-publishes on retry, and the
  consumer's `event_id` dedupe makes the redelivery (and a rare concurrent double-click) harmless. UI in slice 7 (see
  `src/deadletter/` under Frontend). **Honest limitations:** records with a null `organizationId` aren't
  listable/replayable by a tenant admin (a platform-operator view is a future refinement); `eventType`/
  `aggregateType`/`correlationId` weren't captured at drain time so replay doesn't restore them (unneeded by the
  current consumer)),
  `devdata` (DevDataSeeder, local-only — also seeds the global `medical_code` catalog once, then a couple of
  synthetic `clinical_summary` rows per assigned patient, one sample DRAFT `claim` (header + two procedure
  lines + its null→DRAFT status-history row) for the first patient, and two `coverage_plan` rows per org (a PPO
  + an HDHP), enrolls the first patient in the PPO (`patient_eligibility`, open-ended), and prices 80053 on the
  PPO (`plan_fee_schedule`, allowed $40 < the seeded $45.50 charge) so a demo adjudication shows a write-off,
  and requests one sample REQUESTED `prior_authorization` (99213 under the PPO, + its null→REQUESTED
  status-history row) for the first patient so a demo prior-auth queue returns something, and marks 99214 as
  requiring prior auth on the PPO (`plan_prior_auth_requirement`) so a demo 99214 claim adjudicates AUTH_REQUIRED
  until approved — deliberately NOT 99213/80053, which the accumulator/fee-schedule tests assert exact amounts for
  on the seeded PPO, and requests one sample REQUESTED `referral` (to Cardiology, reason I10, + its null→REQUESTED
  status-history row) for the first patient so a demo referral queue returns something, and seeds one additional
  REJECTED `claim` (full null→DRAFT→SUBMITTED→REJECTED history — a claim needs a decision to be appealable) plus a
  SUBMITTED `appeal` on it for the first patient so a demo appeal queue returns something, and opens one OPEN
  `claim_review` on that same claim (+ its null→OPEN status-history row) so a demo manual-review queue returns
  something a reviewer can resolve; reference codes are seeded before the orgs so the
  clinical-summary/claim/referral→catalog FKs are satisfied. **A second PROVIDER per org** (`provider2@`, "Morgan
  Provider", unassigned) plus the **PPO's network = {Dana}** (`plan_network_provider`, Phase 6 slice 19) so a claim
  rendered by Morgan on the PPO adjudicates OUT_OF_NETWORK — the seeded/null-provider claims are unaffected. Plus an
  **AUDITOR** login per org (`auditor@`, "Avery Auditor", Phase 7 slice 1) so the audit-trail read is demoable).
- **Tenant-owned entity pattern (Phase 2+):** hold `organizationId` as the tenant key; repositories expose
  only org-scoped finders (`findByIdAndOrganizationId`, `findByOrganizationId…`) — no bare `findById` in
  business code; services derive the org from `UserContextAccessor.requireOrganizationId()`. `patient` is
  the reference implementation; add composite `UNIQUE(id, organization_id)` so child rows can FK-with-org (§32.10).
- **Write pattern (Phase 2+):** stamp `organizationId` from context on create (never from the client);
  gate writes with `UserContextAccessor.requireAnyRole(...)` (role-based authz on the backend → 403);
  validate request records with Jakarta `@Valid` (→ 400 `VALIDATION_FAILED`); optimistic locking via a
  client-supplied `expectedVersion` compared to the row's `@Version` (mismatch → `ConflictException` 409);
  pre-check uniqueness for a clean 409 rather than surfacing a raw DB-constraint error. Idempotency-Key is
  reserved for the retriable commands §31 names (create request / submit claim / start adjudication).
- **Aggregate + history pattern (§31.6):** an important state change writes the domain row **and** a
  status/history row in **one `@Transactional`** (e.g. `service_request` + `request_status_history`,
  `null → DRAFT` on creation). Child tables carry `organization_id` and FK-with-org back to the parent's
  `UNIQUE(id, organization_id)` so tenancy is structurally enforced. History is append-only; stamp the
  actor and `CorrelationId.current()` on each row. State-machine transitions are validated on the backend.
- **Pagination + filtering + sorting (§Phase 9):** every work-queue list endpoint is **server-side paged** and
  returns a `com.healthcloud.common.PageResponse<T>` (never a bare `List`, never Spring's `PageImpl`). The
  controller takes `page`/`size`/`sort` (+ that queue's own filter param — e.g. `status`, `action`), builds a
  `Pageable` via `PageRequests.toPageable(..., SORTABLE_FIELDS, DEFAULT_SORT)` with a per-controller **sort
  allowlist** of entity property names + a `createdAt DESC` (or `occurredAt DESC`) default; the service takes the
  `Pageable` and returns `PageResponse.of(page, Dto::from)`. **Filtering is pushed into SQL** — a
  `@Query("... and (:status is null or e.status = :status)")` `searchAll` finder (and, for a patient-gated queue,
  `searchForPatients(org, patientIds, filter, pageable)`; for a claim-filtered queue, `searchForClaim(...)`),
  never an in-memory `.filter` over a fetched list. **Authorization is unchanged by paging** — the §21 patient
  gate / role gate / tenant scope run exactly as before; a gated caller with an empty accessible-id set
  short-circuits to `PageResponse.empty(pageable)` (no DB round trip). The claims queue keeps a compatibility
  shim (`api.listClaims()` fetches one large page and returns `.content`) because non-queue callers still need the
  whole list; every other queue's list method was converted directly. All eight queues follow this shape (claims,
  prior-auth, referrals, appeals, claim-reviews, reprocessing, audit, dead-letters).
- **Free-text search (§Phase 9 slices 6–8):** a queue's list endpoint may add a `q` search param alongside the
  filters. Normalize it with `SearchTerms.likeContains(q)` (blank → `null` = no search) and thread it into the
  `searchAll`/`searchForPatients` finder as one more optional in-SQL clause:
  `and (:q is null or lower(<text col>) like lower(cast(:q as string)) escape '\')`. The **`cast(:q as string)`**
  is required — without it Postgres infers the nullable parameter as `bytea` and `lower(bytea)` fails at runtime
  (a real bug the tests caught). **Search a synthetic, PHI-free identifier** — the queue's business number
  (claim/auth/referral/appeal/review/batch number), never patient names (rule 5 + no sensitive data in query
  strings). **All eight work queues now have it** (claims — slice 6; prior-auth, referrals, appeals,
  claim-reviews, reprocessing — slice 7; audit + dead-letters — slice 8). The two non-numbered queues search their
  identifier fields instead — audit matches `correlationId` OR `resourceId`, dead-letters `eventId` OR `messageKey`
  — and, because `resourceId`/`eventId` are **UUID columns**, they are cast to text in SQL
  (`lower(cast(e.resourceId as string)) like …`) so a partial-id paste matches.
- **CSV export with masking (§Phase 9 slice 9):** a list may offer a `GET .../export.csv` download (`produces =
  "text/csv"`, a `Content-Disposition: attachment` header). **The export MUST call the same service read the JSON
  API uses** — never a second finder that reads raw columns — so it inherits every authorization layer *and* the
  field masking (§23.3): a masked field is already `null` in the DTO the read returns, so it serializes as a blank
  cell. Format with the pure `common.Csv` (`row(header)` then `row(cells)` per DTO; `Csv.field` RFC 4180-quotes and
  defuses a leading `= + - @` formula trigger). Export **PHI-minimal** columns — the same ones the JSON read
  already exposes — never a column the read withholds. The reference implementation is the patient list export
  (`PatientService.exportCsvForCurrentTenant()` → `patients/export.csv`), where a consent-masked `dateOfBirth`
  exports blank exactly as it reads. The reusable `Csv` helper is ready to roll out to the work queues (reporting)
  in a later slice. Honest limitation: the whole accessible list is built in one response (no streaming) — fine at
  synthetic scale. The remaining Phase 9 area is WCAG 2.2 AA.
- **Pure policy classes:** keep decision logic (state-machine transition tables, the consent evaluator, the
  anomaly detector) in a pure, unit-testable class with no Spring/DB deps — the exemplars are `RequestTransitions`
  (§14.6 moves), `ClaimTransitions`, `PriorAuthTransitions`, `ReferralTransitions`, `AppealTransitions`,
  `ClaimReviewTransitions` (the six state machines), `ConsentPolicy` (§22.5 consent+purpose),
  `ClaimAnomalyDetector` (a **detector** — it emits a list of findings rather than gating a transition), and
  `AuditHashChain` (§Phase 7 — the canonical serialization + HMAC compute for the tamper-evident audit chain,
  shared by the writer and the verifier); a thin service loads data and applies the policy.
- **State machines:** with the transition table in a pure policy class (`RequestTransitions`, above), the
  service checks, in order, **exists → legal move → role → reason → version**, then
  updates status + appends history in one tx. An illegal move is `INVALID_STATE_TRANSITION` (409), distinct
  from a stale-version `CONFLICT` (409). For state changes, client-supplied `expectedVersion` gives
  double-apply safety, so a separate Idempotency-Key isn't needed there (reserve it for create-type commands).
  A given status may be **owned by a dedicated command** rather than a bare status change: reaching it goes
  through an endpoint that does the extra work (e.g. `ASSIGNED` is reached only by `PUT .../assignment`, which
  records the assignee *and* advances the status in one tx), and a plain `PATCH /status` to that status is
  refused. Prefer this over letting a status be set with no accompanying record.
- **Authorization layering (§21.1; Phase 3+):** protected reads pass through independent backend layers, in
  order — tenant → function/role permission → **object/relationship** (a PROVIDER may read only patients they
  are actively assigned to via `provider_patient_assignment`; a **PATIENT** may read only their own profile —
  the patient row whose `app_user_id` is their login — and, inherited through the guard, only their own requests
  and consent) → **consent + purpose** (§22.5) → **field-level masking** (§23). Each is a separate check that
  can only *narrow* access; a broad role (coordinator/admin — and, until the permission matrix lands, claims
  reviewer) skips the relationship layer but still faces consent/field policy. An object/relationship denial is a **secure
  404** (§21.5), never a 403 that would confirm the row exists. Gate role-agnostically off the caller's actual
  roles from `UserContext`, never the client. **The relationship layer has ONE implementation —
  `PatientAccessGuard.requireAccessibleInTenant(patientId)`** (in `com.healthcloud.patient`) — and **every**
  patient-scoped read routes through it: the patient read itself *and* everything nested under a patient
  (consent directives, the consent decision, provider/coordinator assignments) *and* resources **about** a
  patient that live under their own top-level route — a **service request** is gated by its patient, so request
  reads/writes call `requireAccessibleInTenant(request.getPatientId())` too. **List reads share one scoping
  source:** `accessGuard.accessiblePatientIdsIfGated(caller, org)` returns the patient-id set a gated caller may
  see (provider → assigned; PATIENT → their one linked profile) or `Optional.empty()` for broad roles — both
  `PatientService.list` and `ServiceRequestService.list` filter through it. A new endpoint that exposes a patient
  or patient-linked data MUST call the guard, so the gate can never be side-stepped by a nested or sibling route.
  The guard depends only on repositories (not on the services it protects), so any service can use it with no bean cycle.
  **Break-glass (§Phase 7 slice 4):** the guard also honours a live `break_glass_grant` — a provider-gated caller
  with no assignment is allowed for a patient they hold a time-boxed emergency grant on, and `accessiblePatientIdsIfGated`
  unions assigned + break-glass patient ids. Because this is the one choke point, break-glass thereby reaches the
  patient's whole record. Only the relationship layer is overridden; tenant isolation and consent/field-masking are
  untouched. See the `breakglass` package above.
- **Field-level masking (§23; Phase 3+):** the backend is the only trusted masker — build **field-safe DTOs**,
  never rely on the frontend to hide a value it received (§23.3). Map each resource's fields to a
  §23.1 `DataClassification` (+ a `ConsentDataCategory` when consent-controlled) in a small policy enum (e.g.
  `PatientFieldPolicy`); the read's **purpose is backend-fixed** per action (§21.4), not client-chosen. For each
  consent-controlled field, call `ConsentPolicy` (via `ConsentPolicyService.decideForActor`) for (purpose,
  category) as the calling actor; **deny-by-default** → withhold unless an applicable GRANT exists. A masked read
  returns the field as `null` and names it in a `maskedFields` list; **write responses stay unmasked** (the
  caller supplied the data). Omitted fields must not resurface in logs/exports/events (§23.4). `dateOfBirth` on
  the patient read is the reference implementation.
- **Versioned relationship / supersede pattern (§31.7; `request_assignment` and now `consent_directive`):**
  a mutable relationship is an append-only, `@Version`-locked child table where **at most one row is ACTIVE**
  (a partial unique index `WHERE status='ACTIVE'` enforces it and backstops races). "Changing" it *supersedes*
  the current ACTIVE row (`status→SUPERSEDED`, stamp `ended_at`) and inserts a new ACTIVE one — never mutates
  in place — so history is retained. Flush the supersede **before** the insert so the unique index is honored
  within the tx. Read via `findBy…AndStatus(ACTIVE)`. Validate cross-package participants (e.g. an assignee's
  role) through the owning module's repos, exposing only **minimum-necessary** fields, and return 400 (not a
  leaky 404/403) when the referenced same-tenant user is ineligible. Variations: a table can hold **many
  concurrent** current rows keyed by a natural key (e.g. `consent_directive` per patient×purpose×category×scope)
  — then the "one current" invariant is a partial unique index on that **natural key** (fold nullable key parts
  with `COALESCE(col, <sentinel>)` since Postgres treats NULLs as distinct), the "current" set can be
  `status IN ('ACTIVE','SCHEDULED')`, and a `<thing>_group_id` links the versions of one logical row. When the
  domain needs its own version number, keep it separate from the JPA `@Version` (e.g. `version` vs `lock_version`).
- **Caller/tenant context:** every request's identity is derived on the backend by `UserContextFilter`
  (resolves the session principal → user/org/roles) into a request-scoped `UserContext`. Services read it
  **only** via `UserContextAccessor` (`requireUser()`, `requireOrganizationId()`) — never trust a client-sent
  org/tenant id. Constrain all tenant-owned queries by `requireOrganizationId()`: load rows by
  `(organizationId, id)` so another tenant's row simply isn't found (a secure 404, not a 403). Isolation is
  proven by the `TenantIsolation*` tests — **extend them whenever you add a tenant-owned resource** (Phase 2+).
- **Errors:** one shape `{code, message, correlationId, details}` (`ApiError`). Throw `ApiException`
  subclasses (e.g. `NotFoundException`) or add an `ErrorCode`; `GlobalExceptionHandler` (@RestControllerAdvice)
  + the Security `RestAuthenticationEntryPoint`/`RestAccessDeniedHandler` render them uniformly (controllers
  AND filter-chain 401/403). `CorrelationIdFilter` sets a per-request id (MDC `%X{correlationId}` in logs,
  echoed as `X-Correlation-Id`). Never leak internal/sensitive text in `message`/`details`.
- **Entities:** UUID PKs (`@GeneratedValue(strategy = UUID)`), `@Version` on mutable rows, enums as
  `EnumType.STRING`, `OffsetDateTime` timestamps set via `@PrePersist`/`@PreUpdate`.
- **Schema is owned by Flyway** (`db/migration/V*.sql`); Hibernate is `ddl-auto: validate` (never generates DDL).
- **Auth:** session-based; `SESSION` cookie (HttpOnly), Spring Session JDBC (tables in `spring_session`);
  CSRF via readable `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header; unauthenticated protected requests → 401.
  **Two login paths (Phase 10 slice 12):** (1) the **local dev stand-in** `POST /dev-login` (email only, no
  password/MFA — ADR-018), active only under the `local` profile; (2) the **real Amazon Cognito OIDC login (BFF)** —
  `spring-boot-starter-oauth2-client` + the `cognito`-profile `application-cognito.yml`, wired in `SecurityConfig`
  **only when a `ClientRegistrationRepository` is configured** (`ObjectProvider` guard → no config = no OIDC, so
  offline/CI runs are unaffected). Both establish the same **email-keyed session** the rest of the app reads: the
  Cognito registration sets **`user-name-attribute: email`** so the OIDC principal name is the email, and
  `UserContextFilter.resolveByEmail` + all role/tenant/§21 authorization derive from the **DB by email** exactly as
  before (Cognito supplies identity, never roles — rule 4). `CognitoOidcUserService` rejects a Cognito login with no
  ACTIVE `AppUser`. **Never commit the Cognito client secret** — it's `${COGNITO_CLIENT_SECRET}` from env (locally:
  `terraform output -raw cognito_client_secret`; deploy: Secrets Manager). Frontend sign-in + HTTPS deploy are later
  slices. **MFA** is available in the pool (OPTIONAL/TOTP) but not enforced yet.
  **Sign-in capability probe (UI-polish pass):** a **public** `GET /api/v1/auth/config` (`AuthConfigController`,
  permit-all in `SecurityConfig`) returns `{ cognitoEnabled }` — true only when a `ClientRegistrationRepository`
  with a `cognito` registration exists. It exposes only that boolean (no secrets/ids). The login page reads it
  pre-auth and **disables the "Sign in with Cognito" button with an explanation when Cognito isn't configured**
  (e.g. local dev without the `cognito` profile), instead of letting a click hit `/oauth2/authorization/cognito`
  and its 500. The deployed `demo,cognito` app returns `cognitoEnabled: true`, so the button is live there.
- **Testing pattern:** real PostgreSQL via `TestcontainersConfiguration` (`@ServiceConnection`), imported with
  `@Import(TestcontainersConfiguration.class)`; repository/logic tests use `@SpringBootTest`; full HTTP/session
  flows use a real server (`webEnvironment = RANDOM_PORT`) + JDK `HttpClient` (not MockMvc). No mocks for data access.
  - **State-changing (POST/PATCH) tests must do the CSRF handshake:** log in, `GET /me` to obtain the readable
    `XSRF-TOKEN` cookie, then send it back as the `X-XSRF-TOKEN` header (dev-login itself is CSRF-exempt).
  - A `RANDOM_PORT` test may also `@Autowired` repositories to assert one-transaction side-effects (e.g. that a
    `request_status_history` row was written) — the test runs in the same context as the embedded server.
  - Every new tenant-owned resource gets a cross-tenant test proving another tenant's id → **secure 404**.
  - **Kafka tests (Phase 8)** add a real broker via `KafkaTestcontainersConfiguration` (a `ConfluentKafkaContainer`
    with `@ServiceConnection`), imported ONLY by the Kafka tests so the rest of the suite stays broker-free. The relay
    scheduler and the `@KafkaListener` consumers are disabled across the suite by `src/test/resources/application-local.yml`
    (which augments — never shadows — the main `application.yml`); a Kafka test either drives the relay/consumer path
    directly (`relay.publishPending()`, publish via `KafkaTemplate`) or re-enables consumers with
    `@SpringBootTest(properties = "healthcloud.kafka.consumers.enabled=true")`. Read the broker address from the
    container (`container.getBootstrapServers()`), not the `spring.kafka.bootstrap-servers` property (`@ServiceConnection`
    wires a `ConnectionDetails` bean, leaving the property at its default).
- **Frontend** (`frontend/`, Phase 1 slice 6+): Vite + React + TS, React Router 7, TanStack Query 5, MUI 9.
  Structure: `api/` (typed `fetch` client + `ApiClientError` + CSRF header injection), `auth/` (`useCurrentUser`
  querying `/api/v1/me`, `ProtectedRoute`, `LoginPage`), `layout/AppLayout`, `pages/`, `components/`. Auth =
  session cookie only — the SPA never holds tokens; login state = whether `/me` returns 200. Tests: Vitest +
  React Testing Library (mock the `api` object, keep the real `ApiClientError` for `instanceof`).
  **Login (Phase 10 slice 13):** `LoginPage` offers a primary **"Sign in with Cognito"** button — a **full-page
  link** (`<Button component="a" href="/oauth2/authorization/cognito">`), NOT a `fetch`, because the response is a
  302 to Cognito on another origin. The dev-login dropdown is kept but wrapped in `import.meta.env.DEV` (hidden in
  production builds). The Vite dev server proxies `/oauth2` + `/login/oauth2` to :8080 (scoped — NOT all of `/login`,
  which is the SPA's own route) so the OIDC flow works same-origin in dev; the deployed nginx must add the same two
  proxy locations (slice 14). **The Cognito button is gated on `GET /api/v1/auth/config` (`{ cognitoEnabled }`,
  via a react-query probe):** when Cognito isn't configured (local without the `cognito` profile) the button is
  rendered **disabled with an explanatory note** so a click can't hit the BFF's 500 — it optimistically shows
  enabled while the probe is loading, and only an explicit `false` disables it.

## Boot 4.1 notes (learned; avoid re-discovering)
- Testcontainers is **2.0.x** here → artifacts are `testcontainers-junit-jupiter` / `testcontainers-postgresql`.
- Spring Session needs the **starter** `spring-boot-starter-session-jdbc` (the raw library alone doesn't auto-configure).
- **Kafka** needs the **starter** `spring-boot-starter-kafka` (the raw `spring-kafka` lib alone brings no Boot
  auto-config → no `KafkaTemplate` bean). Inject the auto-configured template as a **raw** `KafkaTemplate` — Boot's
  `KafkaTemplate<?,?>` bean does not satisfy a `KafkaTemplate<String,String>` injection point (wildcard vs specific).
  Testcontainers 2.0.x's `org.testcontainers.kafka.KafkaContainer` (apache/kafka) mis-computes `advertised.listeners`
  on this host → use `ConfluentKafkaContainer` (`confluentinc/cp-kafka`) instead. No `@KafkaListener`/`KafkaAdmin`
  topic beans, so the app makes **no broker connection at startup** — only the relay/consumers connect (and only when
  enabled), so the broker-free tests are unaffected.
- Some test types moved packages: `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure`.
- MockMvc doesn't run the Spring Session filter → test real session cookies with RANDOM_PORT + HttpClient.
- **Jackson 3** here: `ObjectMapper` is `tools.jackson.databind.ObjectMapper` (not `com.fasterxml.jackson.databind`);
  annotations stay under `com.fasterxml.jackson.annotation`. `writeValue(...)` throws unchecked `JacksonException`.

## Frontend notes (learned; avoid re-discovering)
- **MUI 9 `Stack`:** passing `alignItems` (etc.) as a direct prop can fail typecheck when children are a
  mixed/array set — put alignment in `sx={{ alignItems: 'center' }}` instead.
- `tsconfig` uses `verbatimModuleSyntax` → import types with `import type { ... }`.
- **Tests that render a component using `Link`/`useParams`/router hooks must wrap it in a `MemoryRouter`**
  (use `initialEntries` + a `<Routes><Route path="…">` when the component reads a URL param) — otherwise React
  Router throws "Cannot destructure property 'basename' of … null". Adding a router `Link` to an existing page
  breaks that page's older tests until they're wrapped too.
- Dev server binds IPv6 `localhost` and proxies `/api` + `/actuator` to `:8080`; the backend must be running
  or `/me` calls fail.
- **Forms (Phase 2 slice 3+):** React Hook Form 7 + Zod 4 via `@hookform/resolvers/zod` (resolvers **v5**
  supports Zod 4). Pattern: a Zod schema that **mirrors the backend Jakarta validation**, `useForm({ resolver:
  zodResolver(schema) })`, and surface server errors from `ApiClientError` (show `message` + `correlationId`).
  Feature code lives in a feature folder (e.g. `src/patients/`), mirroring `src/auth/`. **Dynamic line-item
  forms** use `useFieldArray` (see `src/claims/CreateClaimForm.tsx`). **Gotcha:** when a Zod schema uses
  `z.coerce`/`z.preprocess` (e.g. number inputs arrive as strings), the schema's *input* and *output* types
  differ, so type `useForm<z.input<...>, unknown, z.output<...>>` (the 3-generic form) or `tsc` rejects the
  resolver; `handleSubmit` then hands you the parsed output.
- **Role-aware UI = convenience, not security.** Gate write UI by `useCurrentUser().roles` to match the
  backend rule (e.g. patient create shown only to CARE_COORDINATOR/ORG_ADMIN), but the backend still enforces it.
  The requests UI mirrors the §14.6 transition table in `src/requests/transitions.ts` purely to choose which
  action buttons to show — the backend re-validates every move, so drift there is a UX bug, never a hole.
- **Feature pages so far:** `src/patients/` (list + create + **detail** `patients/:id`; the DOB column/field
  shows a muted "Restricted" when the backend masks it — the API sends `dateOfBirth: null` + a `maskedFields`
  list, §23; the list a provider sees is also relationship-gated on the backend, so a provider simply gets fewer
  rows — no client logic needed; a PATIENT sees only their own record. The detail page has the
  **consent-directive UI** — record/revoke directives via `src/consent/useConsent.ts`, shown to staff AND to a
  patient on their own record (`CONSENT_WRITE_ROLES = PATIENT + coordinator/admin`; the backend enforces
  own-record-only for a patient); recording invalidates the patient + list queries so a masked field flips live
  — and a **Care team card** — assign/revoke providers and coordinators via `src/relationship/useAssignments.ts`
  (candidate picker + optional effective dates), staff-only (`CARE_TEAM_WRITE_ROLES` = coordinator/admin);
  a care-team change invalidates the assignment lists, the candidate lists, and the patient query, so a masked
  field driven by a PROVIDER/CARE_TEAM directive can flip live — and a **Documents card** (§19) — upload / list /
  download via `src/documents/useDocuments.ts`: a table with a scan-status chip (CLEAN/PENDING/QUARANTINED), a
  Download button only for CLEAN files (fetches the blob via `api.downloadDocument` → object-URL save; a
  quarantined/pending file shows its status, no download), and an upload control shown to `DOCUMENT_WRITE_ROLES`
  = PATIENT-own-record + coordinator/admin. Uploads go through `api.uploadDocument` as multipart `FormData` — no
  explicit `Content-Type` header so the browser sets the boundary; the client's CSRF header still injects) and
  `src/requests/` (list + create + detail with status timeline, transition buttons, comments, assignment), and
  `src/claims/` (Phase 5 slice 5 — the money engine UI: a claims **work queue** `claims`, and a **detail**
  `claims/:id` with the lines table, a status timeline, lifecycle action buttons driven by a client mirror of
  `ClaimTransitions` in `src/claims/transitions.ts` — submit/cancel for submitter roles, accept/reject for
  CLAIMS_REVIEWER/admin — an **Adjudicate** button on an ACCEPTED claim (`canAdjudicate`, the dedicated engine
  command, not a status button, like Assign on a request), and an **Adjudication breakdown** card showing the
  covering plan + per-line allowed/copay/deductible/coinsurance/OOP-applied/plan-paid/member + totals (the §60
  proof, visible). Slice 12 added a **Re-adjudicate** button on an ADJUDICATED claim (`canReadjudicate`,
  CLAIMS_REVIEWER/ORG_ADMIN — reuses the adjudicate command to append a new immutable version), a
  **current-version label** on the breakdown card, and a **Version history** card (all versions newest-first via
  `useAdjudicationVersions` → `GET .../adjudication/versions`, shown once there is more than one version).
  **Phase 6 slice 12** added an **Anomalies card** (shown for any claim status) — the claim's current anomaly
  signals (a severity `Chip` via `anomalySeverityColor` + the type + the PHI-free detail + detected time) via
  `useAnomalies` → `GET .../anomalies`, plus a **Scan** button for CLAIMS_REVIEWER/ORG_ADMIN (`useScanAnomalies`
  → `POST .../anomaly-scan`, a rescan replaces the signals) — the browser view of the slice-11 detector.
  Slice 6 added a **New claim form** on the list page (create roles only) — patient select,
  service date (≤ today, mirroring the backend `@PastOrPresent`), and a `useFieldArray` of lines each with a
  reusable **`MedicalCodePicker`** (an MUI Autocomplete, freeSolo + debounced, searching the catalog via
  `GET /api/v1/medical-codes` filtered to PROCEDURE codes) + units + charge; on create it navigates to the new
  claim. **Slice 21** added an optional **Rendering provider** select to this form (a native `<select>` from
  `useProviders` → `GET /api/v1/providers`; blank → omitted) so an out-of-network claim is producible end-to-end in
  the browser, and the **claim detail page** shows "rendered by <name>" — resolved via `useProviders`, which is
  **role-gated** (an `enabled` flag off `DIRECTORY_ROLES`, so a PATIENT/CLAIMS_REVIEWER viewer doesn't fire the
  gated directory read). The coverage-plan/exclusions/fee-schedule/eligibility admin UIs shipped in later slices —
  see `src/coverage/` next), and
  `src/coverage/` (Phase 5 slice 7 — the **coverage admin UI**: a plans list `coverage-plans` with a New-plan
  form (ORG_ADMIN; reads open to same-tenant staff), and a plan **detail** `coverage-plans/:id` with an
  **Exclusions card** — add via the reusable `MedicalCodePicker` / remove, ORG_ADMIN — reusing the slice-6 picker,
  and (slice 10) a **Fee schedule card** — the priced procedures (code + allowed amount), add via the picker + an
  allowed-amount field / remove, ORG_ADMIN — so the slice-9 fee schedule is manageable in the browser,
  and (Phase 6 slice 5) a **Prior-auth requirements card** — the procedures that require prior auth (a near-twin
  of the Exclusions card: code list + add via the picker / remove, ORG_ADMIN) — so the slice-2
  `plan_prior_auth_requirement` config is manageable in the browser,
  and (Phase 6 slice 20) a **Network providers card** — the plan's in-network PROVIDERs (list by resolved name +
  remove, ORG_ADMIN; add via a **provider `<select>`** populated from `useNetworkProviderCandidates`, not the
  `MedicalCodePicker`, since the participant is a provider not a code) — so the slice-17 `plan_network_provider`
  config is manageable in the browser. Slice 20 also taught the claims adjudication card the **`OUT_OF_NETWORK`**
  line outcome (an `error` chip via `lineOutcomeColor`), surfacing the slice-19 engine rule.
  Slice 8 added **`src/coverage/useEligibility.ts`** and a **Coverage eligibility** card on the *patient detail*
  page (`src/patients/PatientDetailPage.tsx`, after Care team) — lists a patient's enrollments (plan · member ID ·
  effective period, "Open-ended" for a null end) with an **Enroll in a plan** form (CARE_COORDINATOR/ORG_ADMIN;
  plan select from `useCoveragePlans` + member ID + coverage start/end), mirroring `EnrollEligibilityRequest`; the
  in-tenant-plan (400) and non-overlap (409) checks are the server's. No edit/terminate-eligibility UI yet (no
  backend update endpoint). All
  follow the feature-folder + hooks + RHF/Zod pattern.
  Plus **`src/priorauth/`** (Phase 6 slice 3 — the prior-authorization UI, mirroring the claims UI): a work
  queue `prior-authorizations` (auth # · patient · procedure · requested-from · status) and a **detail**
  `prior-authorizations/:id` with the header (procedure · plan · service window · decision reason when present), a
  status timeline, and **decision action buttons** driven by a client mirror of `PriorAuthTransitions` in
  `src/priorauth/transitions.ts` — **Approve/Deny** for CLAIMS_REVIEWER/ORG_ADMIN (reason prompt on Deny) and
  **Cancel** for the requester roles (reason prompt), optimistic-locked via the loaded `version` through
  `useChangePriorAuthStatus`. A **Prior auth** nav button (staff roles). Slice 3 also taught the claims
  adjudication card the new **`AUTH_REQUIRED`** line outcome (a `warning` chip via `lineOutcomeColor`), so slice
  2's effect is visible. Slice 4 added a **New request** form on the queue page (`CreatePriorAuthForm`, shown to
  the requester roles PROVIDER/CARE_COORDINATOR/ORG_ADMIN, mirroring `CreateClaimForm`) — patient select, plan
  select (`useCoveragePlans`), the reusable `MedicalCodePicker`, and service from/to dates; RHF+Zod mirroring
  `CreatePriorAuthorizationRequest` (all string fields, so no `z.coerce`/3-generic needed), on create navigates to
  the new auth. Slice 5 added the **plan-prior-auth-requirement admin card** on the coverage-plan detail page (see
  the `src/coverage/` Prior-auth requirements card above), so an admin sets which procedures require prior auth in
  the browser.
  Plus **`src/referral/`** (Phase 6 slice 7 — the referral UI, mirroring the prior-auth UI): a work queue
  `referrals` (Ref # · patient · specialty · reason code · status) and a **detail** `referrals/:id` with the header
  (specialty · reason code · decision reason when present), a status timeline, and **decision action buttons**
  driven by a client mirror of `ReferralTransitions` in `src/referral/transitions.ts` — **Approve/Deny** for
  **CARE_COORDINATOR/ORG_ADMIN** (not CLAIMS_REVIEWER — referral routing is coordination's call, reason prompt on
  Deny) and **Cancel** for the requester roles (reason prompt), optimistic-locked via the loaded `version` through
  `useChangeReferralStatus`. A **New request** form on the queue (`CreateReferralForm`, requester roles) — patient
  select, a **specialty** text field, and the reusable `MedicalCodePicker` in **`category="Diagnosis"`** mode
  (ICD-10-CM, the coded reason); RHF+Zod, on create navigates to the new referral. A **Referrals** nav button
  (staff roles, no reviewer). The reusable `MedicalCodePicker` gained an optional `category?: 'Procedure' |
  'Diagnosis'` prop (default Procedure, so claims/prior-auth callers are untouched).
  Plus **`src/appeal/`** (Phase 6 slice 9 — the appeal UI, mirroring the referral UI): a work queue `appeals`
  (Appeal # · patient · claim # · status — resolving `patientId → name` via `usePatients` and
  `claimId → claimNumber` via `useClaims`) and a **detail** `appeals/:id` with the header (a link to the disputed
  claim + the reason + decision reason when present), a status timeline, and **decision action buttons** driven by
  a client mirror of `AppealTransitions` in `src/appeal/transitions.ts` — **Uphold/Overturn** for
  **CLAIMS_REVIEWER/ORG_ADMIN** and **Withdraw** for the submitter roles, **each with a reason prompt** (every
  appeal transition needs a rationale), optimistic-locked via the loaded `version` through `useChangeAppealStatus`.
  A **New appeal** form on the queue (`CreateAppealForm`, submitter roles) — a claim select populated from
  `useClaims()` filtered client-side to **appealable** (ADJUDICATED/REJECTED) claims + a multiline reason; RHF+Zod,
  on create navigates to the new appeal. An **Appeals** nav button gated to
  PROVIDER/CARE_COORDINATOR/CLAIMS_REVIEWER/ORG_ADMIN (the reviewer is included — appeals are a claims-review
  function — unlike the referrals nav).
  Plus **`src/claimreview/`** (Phase 6 slice 14 — the manual-review UI, mirroring the appeal UI): a work queue
  `claim-reviews` (Review # · patient · claim # · status — resolving `patientId→name` via `usePatients` and
  `claimId→claimNumber` via `useClaims`) and a **detail** `claim-reviews/:id` with the header (a link to the
  reviewed claim + why opened + the resolution when present), a status timeline, and **decision action buttons**
  driven by a client mirror of `ClaimReviewTransitions` in `src/claimreview/transitions.ts` — **Resolve** for
  **CLAIMS_REVIEWER/ORG_ADMIN** and **Cancel** for the opener roles (CARE_COORDINATOR/CLAIMS_REVIEWER/ORG_ADMIN),
  **each with a reason prompt** (every review transition needs a rationale), optimistic-locked via the loaded
  `version` through `useChangeClaimReviewStatus`. A **New review** form on the queue (`CreateClaimReviewForm`,
  opener roles) — a claim select populated from `useClaims()` (any claim) + an optional reason; RHF+Zod, on create
  navigates to the new review. A **Reviews** nav button gated to CARE_COORDINATOR/CLAIMS_REVIEWER/ORG_ADMIN (the
  opener/resolver audience; no PROVIDER — providers can't open, though an assigned provider can still reach one by
  link).
  Plus **`src/reprocessing/`** (Phase 6 slice 16 — the reprocessing UI; a **job**, not a state machine, so it is
  simpler than the decision-aggregate UIs — no transition buttons, no reason prompts, no optimistic locking):
  a work queue `reprocessing` (Batch # · plan name from `coveragePlanName` · status chip · succeeded/failed/total
  counts · run time) and a **detail** `reprocessing/:id` (header — plan · status · counts · started/finished — plus
  a per-claim **items table**: claim # resolved via `useClaims` and linked to `/claims/:id` · an outcome chip
  (`itemOutcomeColor`) · the new adjudication version on success · the PHI-free message on failure). A **Run batch**
  form (`CreateReprocessingBatchForm`, RHF+Zod, a single coverage-plan `<select>` from `useCoveragePlans`) shown to
  **CLAIMS_REVIEWER/ORG_ADMIN**; on run it navigates to the new batch (the button shows a running state — the
  backend batch is synchronous). `statusColor.ts` (`reprocessingStatusColor` COMPLETED→success /
  COMPLETED_WITH_ERRORS→warning / RUNNING→info; `itemOutcomeColor` SUCCEEDED→success / FAILED→error),
  `useReprocessing.ts` (`useReprocessingBatches`/`useReprocessingBatch`/`useRunReprocessingBatch`), and `api`/`types`
  additions. A **Reprocessing** nav button gated to CLAIMS_REVIEWER/ORG_ADMIN (the run/monitor audience).
  Plus **`src/audit/`** (Phase 7 slice 3 — the security audit-trail UI; read-only, no forms/mutations except the
  on-demand verify): an **Audit** page `audit` with a **Verify integrity** button (`useVerifyAuditChain` → the
  `GET /api/v1/audit-events/verify` mutation) that renders the verdict as a green *"Chain intact — N verified"* or
  red *"Tampering detected at sequence X — reason"* `Alert`, and a recent-events table (`useAuditEvents` →
  `GET /api/v1/audit-events`): When · Seq · Action (chip via `auditActionColor`) · Resource (type + short id) ·
  Outcome (chip via `auditOutcomeColor`) · Actor (short id, full in a tooltip — no name resolution yet) · Detail ·
  **Fingerprint** (truncated `entryHash`, full in a tooltip, so the hash chain is visible), with a client-side
  **Action** filter. `statusColor.ts` (`auditOutcomeColor`/`auditActionColor`), `useAudit.ts`, and `api`/`types`
  additions (`AuditEvent`/`AuditChainVerification`/`AuditAction`/`AuditOutcome`). An **Audit** nav button gated to
  **AUDITOR/ORG_ADMIN** — the previously-unused AUDITOR role's first appearance in the UI.
  Plus **`src/breakglass/`** (Phase 7 slice 5 — the break-glass UI): because a provider breaks glass to reach a
  patient they cannot see, the entry point is the **denied state** — `PatientDetailPage`'s error branch renders a
  **`BreakGlassPanel`** (a reason form + "Break glass" button, RHF+Zod, id from the URL) instead of the generic
  `ErrorScreen` when the patient query is a **404** and the caller is a **PROVIDER** (detected via
  `ApiClientError.status`); `useBreakGlass(patientId)` (`POST /api/v1/break-glass`) invalidates `patientKey(id)` +
  the patients list + the grants list on success, so the page reloads with access. A **`MyBreakGlassPage`**
  (`/break-glass`) lists the caller's live grants (`useMyBreakGlassGrants` -> `GET /api/v1/break-glass`), each
  linking to the now-reachable patient. An **Emergency access** nav button gated to **PROVIDER**. Honest limitation:
  the entry point is the patient's URL/id (a search-by-MRN break-glass flow needs a backend MRN lookup that bypasses
  the gate - not built). **Access review (slice 7):** an **`AccessReviewPage`** (`/access-review`) lists every live
  grant in the tenant (`useAllBreakGlassGrants` -> `GET /api/v1/break-glass/all`) — provider (resolved name) · patient
  (id -> link) · reason · window — with a **Revoke** button (inline Confirm/Cancel, no `window.confirm`) shown only to
  ORG_ADMIN (`useRevokeBreakGlass` -> `POST /api/v1/break-glass/{id}/revoke`); an auditor sees it read-only. An
  **Access review** nav button gated to AUDITOR/ORG_ADMIN.
  Plus **`src/deadletter/`** (Phase 8 slice 7 — the dead-letter / replay UI, the browser view of the slice-5/6
  backend): a **`DeadLetterEventsPage`** (`/dead-letters`) lists the tenant's dead-lettered messages
  (`useDeadLetterEvents` -> `GET /api/v1/dead-letter-events`) — When · Source topic · Event ID · Failure
  (exceptionType, message in a tooltip) · Payload (truncated, full in a monospace tooltip) · Status — with a
  **Replay** button (inline Confirm/Cancel, mirroring Access-review's Revoke) on an un-replayed record
  (`useReplayDeadLetter` -> `POST .../{id}/replay`, invalidates the list so the row flips to a green **Replayed**
  chip); an already-replayed record shows the chip, no button. A **Dead letters** nav button gated to **ORG_ADMIN**
  only (matching the backend list gate — narrower than the AUDITOR+ORG_ADMIN audit/access-review nav, so an auditor
  never lands on a 403 page). Verified by Vitest + typecheck + build (no live browser demo — no demo seed for poison
  messages, and the page is admin-gated). Honest limitation: no pagination/filtering yet (a Phase-9 concern).
- **Consent/field masking in the UI (Phase 3+):** the backend already withholds masked values, so the SPA only
  *displays* the state — render a "Restricted"/placeholder for a `null` consent-controlled field (named in
  `maskedFields`); never assume a field is present. This is display-only, not a security control.
- **Native `<input type="date">` in tests/automation:** set its value directly (ISO `yyyy-mm-dd`), not by typing.
- **Paged work-queue UI (§Phase 9):** a queue page holds `page`/`size`/`sort`(+filter) in local `useState` and
  calls a paged hook `useXxx(params)` (query key `[...KEY, 'page', params]` so a change refetches, still prefixed
  with the base key so a create/decision invalidation catches it; `placeholderData: keepPreviousData` avoids a
  loading-screen flash). The `api.listXxx(params)` returns the `PageResponse` envelope; the page renders
  `data.content` in the table plus MUI `<TablePagination component="div">` (rows-per-page 10/20/50) and
  `<TableSortLabel>` on the **server-sortable** columns only (client-resolved columns like a patient/claim name
  are not sortable). A column header toggles asc↔desc; any sort/size/filter change resets to page 0. The sort
  field strings and the filter values mirror the backend allowlist/enum exactly. Only the **claims** list keeps a
  non-paged `useClaims()` array hook as well (its name-resolution/`<select>` consumers need the whole list).
- **Free-text search box (§Phase 9 slices 6–8):** a queue page that supports search holds the raw box in one
  `useState` and a **debounced** copy in another (a 300ms `setTimeout` in a `useEffect` on the raw value), and
  passes the debounced value as `q` to the paged hook — so we query once the typing settles, not per keystroke.
  Typing resets to page 0 (like the other filters). **All eight queue pages now have one**: the six numbered
  queues search their business number (claim/auth/referral/appeal/review/batch), audit searches the correlation /
  resource id, and dead-letters the event id / message key — the label names what each box matches.
- **Accessibility (§Phase 9 slice 10, WCAG 2.2 AA-aligned):** the app shell (`AppLayout`) provides the page's
  accessibility scaffolding — a **skip-to-content link** (first focusable, hidden until `:focus`, `href="#main"`), a
  **`<nav aria-label="Primary">`** landmark, a **`<main id="main" tabIndex={-1}>`** target, and a brand that is
  **not a heading** (`component="div"`). Every page's single top-level title uses the shared **`PageHeading`**
  (`components/PageHeading.tsx` — `variant="h5" component="h1"`), so each route has exactly one `<h1>` and a correct
  heading order; card/section subheadings stay at their own lower level. New pages MUST use `PageHeading` for their
  title (not a bare `Typography variant="h5"`). Icon-only controls need an `aria-label` (e.g. `CreateClaimForm`'s
  remove-line button); tables get an `aria-label`. **Automated gate:** `src/test/axe.ts`'s `expectNoAxeViolations
  (container)` runs axe-core over rendered markup with the WCAG A/AA rule tags — add it to page/shell tests.
  **Known limitation (rules 2–3):** axe under jsdom **cannot check color contrast** (no rendering engine — the rule
  is disabled in the helper), so contrast is a **browser** check; we verify it in the preview on the core screens
  and rely on our AA-designed MUI theme. So this is AA-*aligned*, not certified — a full page-by-page audit +
  a Playwright+axe E2E gate (per the §29 test stack) are the documented follow-ups.
- **Design system — "Care Constellation" (UI/Design track, slices 1–9 COMPLETE ✅).** The app's visual identity;
  the durable token spec is **`docs/design/design-system.md`** (read it before any UI styling work). Conventions:
  - **The MUI theme is the single styling source** — `frontend/src/theme/index.ts` (`createTheme`). Teal primary
    `#0d9488` (dark `#0f766e`) + indigo accent `#4f46e5`, soft `#f6f8fb` background, AA status colors; **Space
    Grotesk** headings / **Inter** body / **IBM Plex Mono** (exported `MONO`) for codes·IDs·money; radius 12; soft
    elevation. Component defaults live here (light `AppBar`, bordered cards, no-uppercase buttons, tinted uppercase
    table heads, **soft tinted status chips** — filled colored `Chip`s render light-bg+strong-text via a global
    `MuiChip` override, so every status chip restyles at once). **Style via theme tokens/overrides, not per-page CSS.**
  - **Light + Dark color schemes (slice 9)** — the theme is built with MUI **CSS variables + `colorSchemes`
    (`{ light, dark }`)** and `cssVariables: { colorSchemeSelector: 'class' }`. `main.tsx` sets
    `defaultMode="system"` (follows `prefers-color-scheme`), a **Light/Dark/System toggle** (`components/ThemeToggle`,
    via `useColorScheme`) in the sidebar footer lets the viewer override, and the choice persists in localStorage.
    `index.html` has a tiny pre-hydration script that sets the `<html>` color-scheme class from stored mode / device
    so a dark viewer sees **no light flash**. The **dark scheme echoes the Constellation hero** (deep navy `#070b18`
    surfaces, brighter teal `#2dd4bf` / indigo `#818cf8`) so the whole app feels like the hero world. Scheme-varying
    component overrides use **theme vars** + **`theme.applyStyles('dark', …)`** (AppBar, Card, TableCell, the canvas
    wash, the zebra, the soft chips — the chip tint uses the CSS-var `--mui-palette-<color>-mainChannel` so it follows
    the active scheme). **New scheme-aware styling MUST go through theme vars / `applyStyles`, never a hardcoded hex.**
    `useColorScheme` returns `undefined` mode without a CSS-vars provider, so `ThemeToggle` renders nothing in unit
    tests (they don't wrap in `ThemeProvider`) — keep that guard.
  - **Depth & color pass (slice 8)** — a subtle fixed radial **canvas wash** (`MuiCssBaseline` body), a soft layered
    **card shadow**, and a faint **zebra** on even table rows (head row untinted) so the app isn't flat white; the
    dashboard stat cards carry a brand-gradient top accent + teal number + hover lift, tiles get a hover lift.
  - **Dark-hero tokens** are exported as `constellation` from the theme (bg `#070b18`, teal `#5eead4`, indigo
    `#7c9cff`, gradient) for the bespoke **always-dark** surfaces (login hero, dashboard hero band) — they use these
    literal tokens directly, so they render identically in both schemes (mode-independent by design).
  - **Shared UI components** (`src/components/`): `Brand` (gradient glyph + Space-Grotesk wordmark, `compact`/`onDark`),
    `ConstellationBackground` (the animated network `<canvas>` — `aria-hidden`, resize-aware, **static under
    `prefers-reduced-motion`**, bails cleanly in jsdom; reused by the login hero + dashboard hero band), `PageHeading`
    (the single `<h1>`), `EmptyState` (centered inbox-icon + message — use in every work-queue's empty table cell),
    `BackLink` (arrow icon + label — the consistent detail-page back affordance).
  - **The shell** (`AppLayout`): a grouped, role-gated **sidebar** (permanent on desktop via
    `useMediaQuery(up('md'), { defaultMatches: true })` so it renders in jsdom tests; temporary drawer + hamburger on
    mobile), groups **Care / Claims & coverage / Governance**, brand at top, user identity + Log out in the footer.
  - **Login** (`LoginPage`) = the bespoke dark **Constellation hero** (animated network + gradient headline + trust
    chips) with a white sign-in card; it's the deployed URL's first impression (`/` → `/login` when unauthenticated).
  - **Dashboard** (`HomePage`) = a constellation hero band + an "At a glance" stat row of **real, role-gated counts**
    (from the paged endpoints' `totalElements` via `useQueries` — never fabricated, rule 2) + a role-aware launchpad.
  - **Native `<select>` forms (slice 7)** — every `<TextField select slotProps={{ select: { native: true } }}>`
    also passes **`inputLabel: { shrink: true }`** so the floating label never overlaps the option text (a native
    select always shows text). Any new native select MUST include the shrink flag.
  - **Surfaces are elevated cards — do NOT use `variant="outlined"` on `Card` / `TableContainer`** (UI-polish
    pass). The outlined variant sets `box-shadow: none`, which strips the soft shadow the theme's `MuiCard` /
    `MuiTableContainer` overrides add — that was the root cause of the "flat / pale" look. Use a plain `<Card>`
    (defaultProps `elevation={0}` + the root override give it a bordered, softly-shadowed surface) and
    `<TableContainer component={Paper} elevation={0}>` (the `MuiTableContainer` override owns the surface: paper
    bg, radius 14, hairline border, soft shadow, clipped corners). So a **table reads as a contained, lifted card**,
    not a flat sheet on the washed canvas.
  - **Status chips have presence** (theme `MuiChip`): a filled palette chip renders as a tinted pill with a
    **same-color ring** + label padding (a `default` filled chip gets a defined neutral) so short labels
    (ACTIVE / REQUESTED / DRAFT) don't look cramped or washed out. Keep using `<Chip size="small" color={...}>`;
    the look is global.
  - **Form inputs** (theme `MuiOutlinedInput`) have a soft filled resting state + a clear **teal focus ring**, so
    forms read as deliberate rather than bare fields on white. No per-field styling needed.
  - **Filter selects need a `minWidth`.** A `<TextField select size="small">` in a flex filter row can collapse
    to its (empty) content and truncate its label to "S.." — every queue's status filter uses
    `sx={{ minWidth: 200 }}` and the search boxes `sx={{ minWidth: 220, maxWidth: 340 }}`. Any new filter select
    MUST set a minWidth.
  - **Former UI follow-ups — now DONE:** the native-select label overlap (slice 7), the depth/color pass (slice 8),
    Light/Dark/System theme mode (slice 9), and the surface/chip/input polish (the "pale/generic" fix) are all
    complete. Remaining (documented, lower priority): the durable `NativeSelectField` wrapper so a native select
    can't forget the shrink flag; a full page-by-page dark-mode sweep of the less-trafficked screens.

## Infrastructure & deployment conventions (Phase 10; learned)
- **⚠️ AWS cost/approval boundary (hard rule).** Never create, modify, or destroy AWS resources — no
  `terraform apply`, no `aws` CLI mutations, no console changes — **without an explicit, per-action user go-ahead**,
  and tell the user first when a step will need an AWS account or incur cost. Read-only/offline commands
  (`terraform fmt`/`validate`/`plan` with no resources, provider downloads from the Terraform registry) are fine.
  Everything in Phase 10 so far is deliberately **local-only and $0**; the first cost/account-needed step (S3
  remote-state bootstrap, then real resources) is a future slice that must be announced and approved. This mirrors
  the user's standing instruction to flag any AWS entry.
- **Container images are the deploy artifacts.** Backend: multi-stage `backend/Dockerfile` (build on JDK 25, run
  on a slim JRE) as a **non-root** user with an `/actuator/health` HEALTHCHECK; tests are skipped in the image
  build (they need Testcontainers and already gate in CI). Frontend: multi-stage `frontend/Dockerfile` (build the
  SPA with Node, serve from a **non-root nginx**) that **reverse-proxies `/api`+`/actuator` to the backend
  same-origin** (`frontend/default.conf.template`, `${BACKEND_UPSTREAM}` env-templated + SPA deep-link fallback) —
  the production mirror of the Vite dev proxy, so the session + CSRF cookies stay first-party (no CORS). Both are
  **opt-in in docker-compose behind the `full` profile** so everyday `up -d postgres kafka` is untouched; the whole
  app runs as containers (frontend :8081 → backend :8080 → postgres). Keep the Dockerfiles self-contained (build
  the artifact inside the image) so `docker build ./<module>` works standalone.
- **CI builds + publishes both images to GHCR** (`backend-image`/`frontend-image` jobs, each `needs:` its test
  job so only a tested image ships): build on every change, **push only on `main`** (PRs build, don't push), tags
  `sha-<short>` (immutable — deploys pin this) + `latest`, auth via the automatic `GITHUB_TOKEN` (no secrets),
  `linux/amd64`, per-image GHA cache scopes. Keep all four CI jobs green.
- **Terraform (IaC) conventions.** Lives in `infrastructure/terraform/`; pin `required_version` + the AWS provider
  and **commit `.terraform.lock.hcl`**; the `aws` provider's **`default_tags`** stamps `common_tags`
  (Project/Environment/ManagedBy) so tagging is automatic; reuse the `name_prefix` (`${project}-${environment}`)
  local for resource names. **State lives in an S3 remote backend** (Phase 10 slice 6 — bucket
  `healthcloud-tfstate-927747714796`, versioned + encrypted + private) with **S3-native locking** (`use_lockfile`,
  no DynamoDB on TF ≥1.10); state files stay **git-ignored** (never commit `*.tfstate` or `.terraform/`). The bucket
  was created once by the small local-state `infrastructure/terraform/bootstrap/` config (chicken-and-egg: it makes
  the very bucket the main config stores state in) — run once, then left alone. All deployed data stays **synthetic only**.
- **AWS network topology (Phase 10 slice 7, `network.tf`).** One VPC (`10.0.0.0/16`) across **2 AZs**;
  **public subnets** host the ALB + Fargate (they get public IPs), **private subnets** host RDS. **No NAT
  Gateway** — the biggest cost saving: Fargate reaches the internet via its public subnet, and RDS needs no
  egress. So the public route table sends `0.0.0.0/0` to the internet gateway while the private route table is
  local-only. Later slices consume the `vpc_id`/`public_subnet_ids`/`private_subnet_ids` outputs. (Cost note:
  the VPC itself is free; billable pieces begin with the ALB, public IPv4 addresses, and Fargate in later slices,
  all drawn from credits and removed by `destroy`.)
- **RDS / database (Phase 10 slice 8, `rds.tf`).** Managed **Postgres 17** (`db.t4g.micro`, 20 GB gp3, encrypted)
  in the **private subnets** via a DB subnet group, **not publicly accessible**; its SG allows 5432 from the VPC
  (tighten to the app SG when Fargate lands). The **master password is managed in Secrets Manager**
  (`manage_master_user_password = true`) — **never** hardcode a DB password in `.tf` or let it land in state; the
  app reads the secret via IAM at runtime (`db_master_secret_arn` output). Demo settings for a clean teardown:
  single-AZ, `backup_retention_period = 0`, `skip_final_snapshot = true`, `deletion_protection = false`. Real data
  stays **synthetic**; Flyway migrates the schema on app startup (the container uses the default Spring profile — no
  demo seed / dev-login).
- **ECR / images (Phase 10 slice 9, `ecr.tf`).** Two repos (`healthcloud-dev-backend`/`-frontend`, scan-on-push,
  keep-last-5 lifecycle, `force_delete`) — ECS Fargate pulls from here via IAM. **Images were built locally on the
  Apple-Silicon Mac, so they are `linux/arm64` → the ECS task MUST set `cpu_architecture = "ARM64"`** (Graviton,
  cheaper; a mismatch = the container won't start). **Push large images with `crane`, not `docker push`:** on a slow
  home uplink `docker push` times out (`net/http: timeout awaiting response headers`) because it parallelizes layer
  uploads; `crane push <docker-save tarball> <ecr-ref>` (after `aws ecr get-login-password | crane auth login AWS`)
  retries/streams and succeeds, and `crane tag` adds a tag with no re-upload. (The CI→ECR-via-OIDC alternative —
  fast runner, amd64 → x86_64 Fargate — is a documented option we did not take.) Re-pushing after a code change:
  rebuild → `docker save` → `crane push` (or wire it into CI later).
- **ECS Fargate + ALB (Phase 10 slice 10, `ecs.tf` + `alb.tf`) — the running app.** ONE Fargate task holds **both
  containers** (backend + frontend nginx) talking over **`localhost`** — cheapest (one task) and the images run
  unchanged. awsvpc containers share a network namespace, and nginx is hard-wired to 8080, so the **backend runs on
  8081** (`SERVER_PORT` env) and nginx proxies to `http://localhost:8081` (`BACKEND_UPSTREAM` env). **Runtime platform
  MUST be `ARM64`** (the images are arm64 — a mismatch won't start). The **DB password is injected from Secrets
  Manager** via the container `secrets` block (`<secretArn>:password::` / `:username::`), read by the **execution
  role** (managed `AmazonECSTaskExecutionRolePolicy` + an inline `secretsmanager:GetSecretValue`) — **never** put a
  secret in the task def or env plaintext; the **task role** is the app's own runtime identity (empty until S3 slices
  need it). The **Cognito client secret** is handled the same way (Phase 10 slice 14): stored in an
  `aws_secretsmanager_secret` (`recovery_window_in_days=0` for clean teardown) and injected as
  `COGNITO_CLIENT_SECRET`; the execution role's inline policy lists **both** the RDS + Cognito secret ARNs.
  **ECR images are pushed manually (crane), NOT from CI** (CI publishes to GHCR): after any backend/frontend code
  change, the deployed app is stale until you **rebuild the arm64 image + `crane push` to ECR**, then re-apply (a
  fresh `apply` on a torn-down service pulls the new `:latest`; a running service needs `--force-new-deployment`). Tasks run in the **public subnets with `assign_public_ip = true`** (no NAT
  → they need a public IP for ECR/Secrets Manager egress). The **app SG** allows :8080 only from the **ALB SG**, and
  the **RDS SG** now allows 5432 only from the app SG (least privilege; the SG-level `description` is immutable in AWS
  so it was left byte-identical to avoid forcing a replacement). The ALB listens on **HTTP :80**, but its SG ingress is
  **locked to CloudFront's `com.amazonaws.global.cloudfront.origin-facing` managed prefix list** (auth-hardening slice)
  — CloudFront (which forces HTTPS) is the only path in; a request straight to the `*.elb.amazonaws.com` name is
  dropped (the SESSION cookie isn't forced `Secure` because the viewer hop is always HTTPS via CloudFront). For a
  demoable deploy the backend runs the **`demo,cognito` profile** — synthetic seed so Cognito logins map to real app
  users, **without** the `local` profile's `dev-login` bypass (Cognito is the only login path) — with **Kafka off**
  (`HEALTHCLOUD_OUTBOX_RELAY_ENABLED=false` + `HEALTHCLOUD_KAFKA_CONSUMERS_ENABLED=false` — no MSK). **This slice starts the hourly meter** (~$0.08–0.10/hr: ALB + Fargate + public
  IPv4 + RDS) — strictly **on-demand: apply → capture evidence → `terraform destroy`** back to ~$0 (the S3 state
  bucket is kept). Never `apply`/`destroy` without an explicit user go-ahead (the AWS boundary rule).
- **Cognito user pool (Phase 10 slice 11, `cognito.tf`) — real auth (ADR-004).** The identity provider that replaces
  the local dev-login stand-in, introduced **infra-first** (pool + client + hosted UI + synthetic users this slice;
  the Spring BFF wiring, frontend, and HTTPS deploy are later slices). The app client is **confidential**
  (`generate_secret = true`) because the Spring backend is a server-side **BFF** that holds the session — the secret
  lands in the (private, encrypted) state and the backend reads it from config/Secrets Manager, never hardcoded.
  OIDC **authorization-code** flow, scopes `openid`/`email`/`profile`. Callback/logout URLs are **`http://localhost`**
  for local dev now; the deployed **HTTPS** URLs are added with the ACM/CloudFront slice (Cognito rejects non-HTTPS
  redirect URLs for anything but localhost — so Cognito-on-AWS *needs* HTTPS). Sign-in is by **email**
  (`username_attributes=["email"]`) so Cognito users map onto the app's existing user emails. **MFA is OPTIONAL**
  (TOTP available, not enforced) for demo simplicity — enforcing it is a hardening follow-up. **Never put a user
  password in Terraform** (it would land in state) — `aws_cognito_user` creates the record with the invite
  suppressed, and a permanent synthetic password is set out-of-band via `aws cognito-idp admin-set-user-password
  --permanent`. **$0** (free tier ≤ 50k MAU), safe to leave up. **Apply Cognito with `-target`** while the ALB/ECS
  are torn down, or a bare `apply` recreates them (they're still in config) and restarts the hourly meter.
- **CloudFront HTTPS (Phase 10 slice 15, `cloudfront.tf`) — the public HTTPS entry.** A distribution in front of the
  ALB gives a free trusted `https://<id>.cloudfront.net` endpoint (ACM can't issue for the ALB's `*.elb.amazonaws.com`
  name, and Cognito requires HTTPS callbacks) — **no domain needed**. Origin = the ALB `http-only`;
  `viewer_protocol_policy = redirect-to-https`; all HTTP methods; managed **`CachingDisabled`** + **`AllViewer`**
  policies so cookies/headers/query strings forward to the origin (the app is a dynamic BFF — the session + CSRF
  cookies and the OAuth `code`/`state` must pass through); default CloudFront cert; `PriceClass_100`. **Pin the OIDC
  redirect_uri** to the CloudFront HTTPS callback via the env var
  `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_COGNITO_REDIRECTURI` (Spring relaxed binding overrides
  application-cognito.yml, no rebuild) — CloudFront→ALB is an HTTP hop, so Spring's `{baseUrl}` would otherwise yield
  an `http://` redirect_uri Cognito rejects. Register that same HTTPS callback on the Cognito client. **After an ECS
  task-def change, verify only once the deployment reaches `rolloutState=COMPLETED`** — the old task serves the
  previous behavior until it drains (this bit us: an `http` redirect_uri lingered mid-rollout). CloudFront ~$0 (free
  tier), ~3–15 min to create/update. Follow-ups: logout is local-only (no RP-initiated Cognito logout).
- **Auth-hardening slice (Phase 10, post-slice-15) — the deployed auth boundary is real.** Three fixes from the
  parallel code/security review of the Phase 10 diff: (1) **closed the dev-login bypass on the deploy** — the app
  ran `local,cognito`, and the `local` profile keeps `/api/v1/dev-login` (unauthenticated email→ORG_ADMIN session)
  live; split "seed" from "dev-login" so the deploy seeds without it (`DevDataSeeder` → `@Profile({"local","demo"})`,
  `DevLoginController` stays `local`-only, `SecurityConfig` permits/CSRF-exempts `/dev-login` only under `local`,
  `ecs.tf` profile → **`demo,cognito`**); (2) **locked the ALB SG :80 ingress to the CloudFront origin-facing prefix
  list** (`alb.tf`, no more `0.0.0.0/0`); (3) **trimmed the Cognito client `explicit_auth_flows` to just
  `ALLOW_REFRESH_TOKEN_AUTH`** (`cognito.tf` — dropped the unused `ALLOW_USER_PASSWORD_AUTH`/`ALLOW_USER_SRP_AUTH`;
  the hosted-UI auth-code flow + `admin-set-user-password` are unaffected). Local dev + all tests keep using `local`
  unchanged. Proven by `DeployProfileNoDevLoginTest` (boots under `demo`: seeder present, `DevLoginController` absent,
  `/dev-login` denied — never a 200 session). Remaining follow-ups: a per-distribution secret origin-verify header
  (the prefix list still admits any account's CloudFront), RP-initiated Cognito logout, a custom domain, enforce MFA.

## Observability conventions (Phase 11; learned)
- **Metrics = Micrometer + Prometheus (slices 1–2).** `spring-boot-starter-actuator` + a
  `micrometer-registry-prometheus` runtime dep expose `/actuator/prometheus` (auto-instrumented JVM / HTTP /
  HikariCP metrics). `application.yml` adds `prometheus`,`metrics` to the actuator exposure, a common
  `management.metrics.tags.application: healthcloud`, and `percentiles-histogram.http.server.requests: true` (so
  p95 latency is computable). **`/actuator/prometheus` is permitted WITHOUT auth ONLY under the `local` profile**
  (SecurityConfig, alongside the dev-login gating) so a local scraper works; the deployed `demo,cognito` app keeps
  it authenticated (`DeployProfileNoDevLoginTest` asserts this). Only `health`,`info` are ever public.
- **Domain metrics** are added with an injected `MeterRegistry`, incremented on the transaction's **afterCommit**
  (`TransactionSynchronizationManager`) so a rolled-back change is never counted; keep them PHI-free (counts +
  coded outcomes only, rule 5). The exemplar is `AdjudicationService` → `healthcloud.adjudications` (rendered
  `healthcloud_adjudications_total`, tagged `outcome`+`type`). Name a counter WITHOUT a `.total` suffix — the
  Prometheus registry appends `_total`. That afterCommit pattern is the template for future domain counters.
- **Dashboards (slice 2)** live in `infrastructure/observability/` — `prometheus.yml` (scrape config) + Grafana
  provisioning (datasource + the "HealthCloud Overview" dashboard JSON). Run them via the `observability` compose
  profile (see How-to-run). Every panel shows **measured** values (rule 2 — no fabricated numbers).
- **Tracing = Micrometer Tracing + OpenTelemetry → Jaeger (slice 3).** In Boot 4 tracing is opt-in via dedicated
  modules — `spring-boot-micrometer-tracing-opentelemetry` (bridge + `OpenTelemetryTracing`/`OtlpTracing`
  autoconfig) **plus an explicit `micrometer-tracing-bridge-otel`** (it's only a runtime transitive, so
  `@ConditionalOnClass(OtelTracer)` won't see it otherwise — this bit us) + `opentelemetry-exporter-otlp` +
  `aspectjweaver` (for `@Observed`; Boot 4 dropped `spring-boot-starter-aop`). **Boot 4.1 renamed the OTLP tracing
  endpoint property to `management.opentelemetry.tracing.export.otlp.endpoint`** (NOT the Boot-3
  `management.otlp.tracing.endpoint`; with the old name spans are created but silently never exported — this also
  bit us). Sampling is 1.0 locally; the log pattern carries `traceId`/`spanId` beside `correlationId`. A custom
  span is a method `@Observed(...)` (needs the `ObservedAspect` bean — see `observability/ObservabilityConfig`);
  the exemplar is `AdjudicationService.adjudicate` → an `adjudicate-claim` span nested under the HTTP span. Kafka
  `template`/`listener` observation is enabled so the trace context propagates through Kafka headers.
- **Tests stay tracing-free:** `management.tracing.enabled=false` in the test `application-local.yml` (and as a
  property on the `demo`-profile test) so CI emits no OTLP export noise. Metrics export over OTLP is off (Prometheus
  is the metrics path); `spring-boot-starter-opentelemetry` is deliberately NOT used (its SDK autoconfigure builds a
  competing tracer provider with no exporter + an unwanted OTLP metrics registry).
- **Health & probes (slice 4).** `management.endpoint.health.probes.enabled: true` exposes the Kubernetes-style
  `/actuator/health/liveness` + `/actuator/health/readiness` (both public — `SecurityConfig` permits
  `/actuator/health/**`). The split is deliberate: **liveness** = "is the process alive?" → default
  `livenessState` only, so an external-dependency blip NEVER restarts the app; **readiness** = "can I serve
  traffic?" → `management.endpoint.health.group.readiness.include: readinessState,db` so the pod leaves the load
  balancer when Postgres is unreachable; **root `/actuator/health`** = the full monitoring aggregate (db + the
  custom outbox indicator + …), which may go 503 to *signal* degradation without killing or de-pooling anything.
  A **custom domain `HealthIndicator`** follows `OutboxHealthIndicator` (`outbox` package): implement
  `org.springframework.boot.health.contributor.HealthIndicator` (Boot-4 package), report PHI-free details (counts/
  ages only, rule 5), map a domain problem to `OUT_OF_SERVICE` (degraded) not `DOWN`, and **keep it out of the
  readiness group** unless the app genuinely can't serve without it (a relay backlog can still serve requests, so
  it's root-health-only — never gates traffic). The **container HEALTHCHECK targets `/actuator/health/liveness`**
  (`backend/Dockerfile`), so only a real process failure restarts the container. **Health detail is
  `show-details: when-authorized`** (review fix) so an anonymous caller on the public `/actuator/health[/**]`
  (reachable via nginx→CloudFront on deploy) sees only `{"status":"UP"}`, never the `db` component / exception
  text; the `local` profile overrides it back to `always` (a profile document in `application.yml`) for dev
  convenience, and `DeployProfileNoDevLoginTest` asserts the hardened behavior under `demo`. Follow-ups: point the
  ECS/ALB health check at `/actuator/health/readiness` (a Terraform change, AWS boundary); and role-gate
  `/actuator/prometheus`+`/actuator/metrics` (today authenticated but not restricted to ORG_ADMIN like the other
  ops surfaces — PHI-free, so a consistency/hardening item, not a hole).
- **Alerting (slice 5).** Prometheus alert rules live in `infrastructure/observability/alert-rules.yml` (loaded
  via `rule_files` in `prometheus.yml`, mounted into the prometheus container). Prometheus **evaluates** them and
  exposes their state (`/api/v1/rules`, `/api/v1/alerts`, the `ALERTS` metric) with **no Alertmanager** wired
  locally — Alertmanager is only the routing/notification layer, and routing to a real destination needs external
  services + secrets, so it's a documented follow-up (fits the runbooks slice). Rules: `BackendTargetDown`
  (`up==0`, critical), `OutboxBacklogHigh`, `HighHttp5xxRate`, `HighRequestLatencyP95`, `JvmHeapHigh` (warning).
  **Every expression is over a metric the app actually exports** (rule 2 — thresholds are demo *targets*, not
  measured SLOs) and annotations are PHI-free. The outbox alert is backed by a Micrometer **gauge**
  `healthcloud.outbox.pending` (`OutboxMetrics`, outbox package) that promotes slice 4's health signal into an
  alertable metric — the pattern for making a domain signal alertable (a gauge, polled from a cheap repository
  count at scrape time). Validate rule changes with `promtool check rules`/`check config` (run via the
  `prom/prometheus` image); a rule genuinely fires end-to-end (e.g. stop the backend → `BackendTargetDown`
  pending→firing).
- **Backup & restore (slice 6).** "A backup you have never restored is not a backup" — so we ship an automated
  **restore drill**, not just a dump. `scripts/db-backup.sh` writes a custom-format `pg_dump -Fc` archive to
  `var/backups/` (git-ignored) via `docker compose exec` (pg tools run **inside** the postgres container — no host
  psql needed). `scripts/db-restore-drill.sh` rehearses recovery end to end **without touching the live DB**:
  backup → restore into a scratch DB → **verify `count(*)` of every public table matches source vs restored** →
  drop scratch → PASS/FAIL. The dump includes `flyway_schema_history`, so a restored DB passes `ddl-auto:
  validate`. **Bash gotcha (fixed & documented):** any `docker compose exec` inside a `while read` loop must
  detach stdin (`</dev/null`) or it swallows the loop's input (only the first item is processed). The runbook is
  `docs/runbooks/backup-and-restore.md`; the production RDS equivalent (automated backups + PITR + snapshot
  restore) is an on-demand, approval-gated follow-up (RDS `backup_retention_period` is 0 for cheap teardown).
- **Runbooks (slice 7).** Operational playbooks live in `docs/runbooks/`: `README.md` (the entry point — the
  observability stack, the three-signals model, and the metric→alert→`correlationId`/`traceId`→trace triage
  workflow), `alert-response.md` (one section per slice-5 alert — meaning · confirm · likely causes · recovery,
  all with real queries/endpoints), and `backup-and-restore.md` (slice 6). Each alert rule carries a `runbook`
  annotation linking to its `alert-response.md` section, so a firing alert points at its playbook. Keep runbook
  steps grounded in tooling that actually exists (rule 2), and note the production (AWS) equivalent where it
  differs from the local stack.
- **Deploy:** no OTLP collector / Prometheus scrape is wired on AWS yet (export no-ops there) — an on-demand
  documented follow-up, consistent with the Phase-10 AWS-cost boundary.

## Repo layout
`backend/` `frontend/` `worker/` `infrastructure/{terraform,environments}` `api/openapi/`
`docs/{architecture,er-diagram,events,threat-model,adr,runbooks,evidence,learning,design,source-of-truth}/`
`synthetic-data/` `scripts/` `.github/workflows/` · plus `CLAUDE.md`, `docs/PLAN.md`,
`docs/PROGRESS.md`, `docker-compose.yml`, `README.md`.

## Version control (learned; avoid re-discovering)
- **Never put a bare directory name in a `.gitignore`.** A rule like `coverage` (or `build`, `dist`, `target`)
  matches a directory of that name **anywhere** in the tree, so it silently swallows a same-named *source* folder
  and `git add -A` skips it with no error — the commit builds locally but breaks CI (missing files). This has
  bitten twice: the backend `com.healthcloud.coverage` package (root `coverage/` → fixed to `frontend/coverage/`)
  and the frontend `src/coverage/` feature folder (`frontend/.gitignore` `coverage` → fixed to `/coverage/`).
  **Anchor location-specific rules** (leading `/`, or a path prefix like `frontend/coverage/`), and after adding a
  new feature folder run `git status --short` (and `git check-ignore -v <path>` if unsure) to confirm its files
  are actually staged before committing. When the ignore is meant for tooling output (Vitest `coverage/`, Maven
  `target/`), anchor it so it can't collide with a feature folder of the same name.

## Custom tooling (see docs/PLAN.md Part C for the full plan)
- **Exists today:**
  - `.claude/launch.json` — the `frontend` dev-server config for the browser preview.
  - slash command **`/learning-module`** (`.claude/commands/learning-module.md`) — appends a per-session
    learning + interview-prep note to `docs/learning/learning-module.md` (never overwrites; based on what
    we actually built). This is the `/capture-module` idea from PLAN.md Part C, realized.
  - **subagents** (`.claude/agents/`, the Phase-3 review subagents from PLAN.md Part C, realized) — both
    **read-only** (tools `Read, Grep, Glob, Bash`; no `Edit`/`Write`; `Bash` for inspection only —
    `git diff`/`log`/`show`, `grep`, `cat` — never mutating): **`code-reviewer`** (bugs + quality vs the
    project's own invariants — tenant scoping, thin controllers, §21 authz layering, one-tx + history,
    engine-command statuses, pure policy classes, optimistic/row-lock concurrency, BigDecimal money, Flyway
    discipline, frontend RHF/Zod + router-test rules, .gitignore anchoring) and **`security-reviewer`**
    (exploitable issues in the real threat model — tenant isolation, `PatientAccessGuard`, secure 404, role
    gates, consent/purpose, field-masking leaks, SQL/path-traversal injection, CSRF/session/`SecurityConfig`,
    PHI-in-logs, financial double-apply; tuned for low false positives). They pull their own
    `git diff main...HEAD`; invoke by name (e.g. "use the code-reviewer subagent on my current changes").
    **Agent files load at session start** — a newly created/edited agent is spawnable only in the next session.
- **Planned, NOT yet created** (don't assume these exist): commands `/status` (session start),
  `/wrap` (session end), `/adr`; Phase-1+ hooks (format/compile after edits; later a synthetic-data guard).
- Built-ins remain available too: `/code-review`, `/security-review` (generic), and read the 3 files manually.
