# PROGRESS.md — HealthCloud running log

> The **diary** of the project. Updated at the end of every session (by the `/wrap` command once it
> exists, or manually). Read this + `CLAUDE.md` + `docs/PLAN.md` at the start of every session.

## Current position
- **Status:** Phases 0–9 COMPLETE ✅ · **Phase 10 COMPLETE ✅** (cloud deployment & CI/CD — slices 1–15 done: containerize → CI images → Terraform/remote state → VPC → RDS → ECR → ECS/ALB → Cognito OIDC login live over HTTPS via CloudFront) — slice 1 ✅ **containerize the backend** (multi-stage `Dockerfile` → the jar on a slim JRE as non-root `spring`, `/actuator/health` HEALTHCHECK; opt-in `backend` service in docker-compose behind the `full` profile; the artifact ECS Fargate will run — local-only, zero AWS/cost; verified: image builds, container boots against Postgres with health UP, runs non-root). · slice 2 ✅ **build + publish the image in CI** (a `backend-image` job in ci.yml, `needs: backend`, builds on every change and pushes to **GHCR** — `ghcr.io/nikhil-oggu/healthcloud-backend`, tags `sha-<short>` + `latest` — only on push to `main`; PRs build but don't push; `GITHUB_TOKEN` auth, no secrets; still zero AWS/cost). · slice 3 ✅ **containerize the frontend** (multi-stage `frontend/Dockerfile` → SPA built with Node, served by a **non-root nginx** that reverse-proxies `/api`+`/actuator` to the backend same-origin; opt-in `frontend` service in docker-compose behind `full`, :8081; the whole app now runs as containers; local-only, zero AWS/cost; verified: 77 MB image, SPA served + API proxied + deep-link fallback + non-root). · slice 4 ✅ **publish the frontend image in CI** (a `frontend-image` job in ci.yml, `needs: frontend`, mirrors slice 2 → pushes to **GHCR** `ghcr.io/nikhil-oggu/healthcloud-frontend`, tags `sha-<short>` + `latest`, only on push to `main`; distinct GHA cache scopes per image; **both images now build + publish in CI**; still zero AWS/cost). · slice 5 ✅ **Terraform skeleton** (`infrastructure/terraform/` — version pins + AWS provider `default_tags` + region/project/environment vars + `name_prefix`/`common_tags` locals + outputs + a local-backend-now/S3-later `backend.tf` + README; **declares NO resources → no AWS account, no credentials, $0**; verified `fmt`/`init -backend=false`/`validate`/`plan` clean). · slice 6 ✅ **Terraform remote-state backend (S3)** (a small local-state `bootstrap/` config created the state bucket `healthcloud-tfstate-927747714796` — versioned/encrypted/private; main `backend.tf` now uses S3 with native `use_lockfile` locking, **no DynamoDB**; state migrated to S3 — the **first applied AWS resource**, on the user's new Free-Plan account 927747714796 with a $5 budget alarm, cost ~$0). · slice 7 ✅ **VPC & networking** (`network.tf` — VPC `10.0.0.0/16` + IGW + 2 public subnets (ALB/Fargate) + 2 private subnets (RDS) across 2 AZs + public/private route tables; **deliberately NO NAT Gateway** → ~$32/mo saved; 13 free resources applied, $0, verified via `aws ec2` — VPC `vpc-0f5ae5185f6331cdb`, no NAT). · slice 8 ✅ **RDS PostgreSQL** (`rds.tf` — managed Postgres 17 `db.t4g.micro` 20 GB gp3 encrypted, in the private subnets, **not public**, master password in **Secrets Manager**; SG allows 5432 from the VPC; 3 resources applied, verified `available`/`postgres 17.9`/public=false/encrypted=true; **free-tier-eligible** ~$0 + ~$0.40/mo secret, on-demand). · slice 9 ✅ **ECR + images pushed** (`ecr.tf` — 2 registries `healthcloud-dev-backend`/`-frontend`, scan-on-push, keep-last-5 lifecycle, force_delete; applied, $0). **Both images in ECR** (`sha-c1e42ab` + `latest`), built **`linux/arm64`** → ECS Fargate must run ARM64. Pushed with **`crane`** (not `docker push`, which timed out on the home uplink; Docker Desktop needed a Mac restart to clear a stale `Docker.raw` VM lock). · slice 10 ✅ **ECS Fargate + ALB — the app is LIVE on AWS** (`ecs.tf` + `alb.tf` — one Fargate task holds both containers over localhost, backend on 8081 + nginx proxy, **ARM64**, DB password **injected from Secrets Manager**, `local` profile for a demoable URL, Kafka off; RDS SG tightened to the app SG in-place; ALB HTTP :80 → frontend :8080; `app_url` output; **13 added, 1 changed, 0 destroyed**; verified live end-to-end: `/actuator/health` UP w/ db UP, dev-login + `/me`, seeded synthetic data relationship-gated + consent-masked, browser Patients page renders. ~$0.08–0.10/hr from credits — **on-demand: `destroy` to return to ~$0**; torn down after verification via targeted destroy — app OFFLINE, VPC/RDS/ECR kept). · slice 11 ✅ **Amazon Cognito user pool — real-auth infrastructure** (`cognito.tf` — user pool (email sign-in, MFA optional), a **confidential app client** for the BFF, the free hosted login page, + 2 synthetic users matching seeded emails; **INFRA ONLY**, applied via `-target` so ALB/ECS stayed down; verified — pool/client/domain live, OIDC discovery works, hosted login renders over HTTPS; **$0**, safe to leave up; passwords set out-of-band via CLI. Next: backend BFF wiring). · slice 12 ✅ **backend Spring Security OAuth2 BFF — real Cognito login, proven locally** (`spring-boot-starter-oauth2-client` + `application-cognito.yml` profile + `SecurityConfig` conditional `.oauth2Login` (via `ObjectProvider<ClientRegistrationRepository>` — off when Cognito unconfigured, so CI/offline unaffected) + `CognitoOidcUserService` rejecting logins with no ACTIVE app user; `user-name-attribute: email` so `UserContextFilter.resolveByEmail` + roles/tenant from DB work unchanged; client secret from env, never committed; dev-login kept under `local`. Verified: 3/3 unit tests + app boots with cognito profile + `/oauth2/authorization/cognito`→302 to Cognito w/ PKCE + dev-login regression intact. **$0**, backend-only). · slice 13 ✅ **frontend "Sign in with Cognito" — the SPA login button (proven locally E2E, $0)** (`LoginPage.tsx` primary Cognito button = full-page link to `/oauth2/authorization/cognito`; dev-login moved under a `import.meta.env.DEV`-gated "Developer sign-in" section so prod builds show only Cognito; `vite.config.ts` proxies `/oauth2`+`/login/oauth2`→:8080; `cognito.tf` adds the :5173 dev callback/logout URLs, applied via `-target`; new `LoginPage.test.tsx`. Verified: typecheck + 184 tests + build green; clicking the button in the browser redirected SPA→proxy→backend→real Cognito login with `redirect_uri=…:5173/login/oauth2/code/cognito`. Deployed nginx proxy + prod HTTPS callback = slice 14). · slice 14 ✅ **redeploy the Cognito-capable app on the ALB (HTTP) + OAuth proxy** (rebuilt+re-pushed both images via crane so ECR is current — backend has the OAuth2 code+`application-cognito.yml`, frontend the button+nginx proxy; nginx now proxies `/oauth2`+`/login/oauth2`; Cognito client secret→**Secrets Manager**→injected; task env `local,cognito`+`COGNITO_CLIENT_ID`/`ISSUER`+`SERVER_FORWARD_HEADERS_STRATEGY=framework`; `terraform apply` 7 add/2 destroy recreated ALB/ECS. Verified: health UP, `/oauth2/authorization/cognito`→302 to Cognito via nginx (PKCE), deployed login page shows only "Sign in with Cognito", dev-login regression intact. Login completes over HTTPS in slice 15 (CloudFront). Restarts ~$0.08/hr). · slice 15 ✅ **CloudFront HTTPS — the live Cognito login completes over HTTPS** (`cloudfront.tf` distribution over the ALB — free `*.cloudfront.net` cert, redirect-to-https, CachingDisabled+AllViewer; `cognito.tf` adds the CloudFront callback; `ecs.tf` pins the redirect_uri to the https callback via the `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_COGNITO_REDIRECTURI` env — no rebuild. Applied 2 add/2 change/1 destroy, app at **`https://d3rj9qdohmthpy.cloudfront.net`**. Verified over HTTPS: health UP, `/oauth2/authorization/cognito`→302 with the pinned https redirect_uri, browser click reaches the Cognito login page. **Cognito arc (11–15) COMPLETE** — real OIDC login on the cloud URL). · **Phase 9 COMPLETE ✅** — search / reporting / accessibility (pagination + free-text search across all eight queues, CSV export with masking, WCAG 2.2 AA-aligned accessibility pass). Slice 1 ✅ (server-side pagination + filtering, backend + reusable `common` foundation) · slice 2 ✅ (paged claims work-queue UI) · slice 3 ✅ (paged prior-authorizations queue) · slice 4 ✅ (paged referrals + appeals + claim-reviews queues) · slice 5 ✅ (paged reprocessing + audit + dead-letter queues — **every work queue is now paginated**) · slice 6 ✅ (free-text search on the claims queue — backend `SearchTerms` foundation + `q` param + debounced search box) · slice 7 ✅ (free-text search rolled out to the five other numbered queues — prior-auth, referrals, appeals, claim-reviews, reprocessing) · slice 8 ✅ (free-text search on the last two queues — audit + dead-letters — so **all eight work queues are searchable**) · slice 9 ✅ (**CSV export with masking** — reusable `common.Csv` formatter + `GET /api/v1/patients/export.csv` reusing the field-masked list read + an Export CSV button) · slice 10 ✅ (**WCAG 2.2 AA-aligned accessibility pass** — axe-core test gate + app-shell skip link/nav landmark/heading semantics via a shared `PageHeading`; **Phase 9 COMPLETE**). The MVP (Phases 0–5) is feature-complete — engine AND UI. · **Phase 11 COMPLETE ✅** (observability & recovery) — slice 1 ✅ metrics foundation (Micrometer + `/actuator/prometheus` + first domain counter) · slice 2 ✅ local Prometheus + Grafana dashboards · slice 3 ✅ distributed tracing (Micrometer Tracing + OTLP → Jaeger) · slice 4 ✅ health & readiness probes + a custom outbox health indicator · slice 5 ✅ alert rules (Prometheus alerting rules + a `healthcloud.outbox.pending` gauge) · slice 6 ✅ backup & restore drill · slice 7 ✅ **runbooks** (`docs/runbooks/` — index + triage workflow + an alert-response playbook per alert, wired from each rule's `runbook` annotation). Documented follow-ups: wire Prometheus/Jaeger to AWS, Alertmanager routing, RDS PITR/snapshot DR (all on-demand + approval-gated).
- **At a glance** (newest first; the detailed per-phase bullets and the dated log below carry the full record):
  - **Phase 11 COMPLETE ✅** Observability & recovery — slice 1 ✅ **metrics foundation** (Micrometer + `micrometer-registry-prometheus` → `/actuator/prometheus`, `local`-only unauthenticated scrape, a common `application` tag, first domain counter `healthcloud_adjudications_total` on afterCommit) · slice 2 ✅ **local Prometheus + Grafana dashboards** (the `observability` compose profile + an auto-provisioned "HealthCloud Overview" dashboard, HTTP p95 histograms) · slice 3 ✅ **distributed tracing** (Micrometer Tracing + OpenTelemetry → local **Jaeger** over OTLP, `@Observed` `adjudicate-claim` span nested under the HTTP span, traceId/spanId in logs; two Boot-4 gotchas found — the explicit `micrometer-tracing-bridge-otel` dep + the renamed `management.opentelemetry.tracing.export.otlp.endpoint` property) · slice 4 ✅ **health & readiness probes + a custom outbox health indicator** (readiness = `readinessState`+`db`; liveness stays process-only; a custom `OutboxHealthIndicator` surfaces the relay backlog on root health only — never gating readiness; container HEALTHCHECK moved to `/actuator/health/liveness`) · slice 5 ✅ **alert rules** (5 Prometheus alerting rules in `infrastructure/observability/alert-rules.yml` — `BackendTargetDown`/`OutboxBacklogHigh`/`HighHttp5xxRate`/`HighRequestLatencyP95`/`JvmHeapHigh`, each over a real exported metric; a Micrometer gauge `healthcloud.outbox.pending` promotes slice 4's backlog signal into an alertable metric; no Alertmanager locally — routing is a documented follow-up; verified `BackendTargetDown` fires end-to-end when the backend is stopped) · slice 6 ✅ **backup & restore drill** (`scripts/db-backup.sh` writes a custom-format `pg_dump` to `var/backups/` via the postgres container; `scripts/db-restore-drill.sh` rehearses recovery non-destructively — backup → restore into a scratch DB → verify `count(*)` of every public table matches → drop scratch → PASS/FAIL; runbook `docs/runbooks/backup-and-restore.md`; RDS PITR/snapshot is the on-demand follow-up; verified locally: 51 tables / 283 rows restored identically) · slice 7 ✅ **runbooks** (`docs/runbooks/README.md` — observability-stack overview + the metric→alert→`correlationId`/`traceId`→trace triage workflow; `alert-response.md` — a playbook per slice-5 alert (meaning · confirm · causes · recovery); each alert rule now carries a `runbook` annotation linking to its section). **Phase 11 COMPLETE ✅** (metrics → dashboards → tracing → probes → alerts → backup/restore → runbooks). Documented follow-ups: wire Prometheus/Jaeger to AWS, Alertmanager routing, RDS PITR/snapshot DR (all on-demand + approval-gated).
  - **UI/Design track COMPLETE ✅** "Care Constellation" — slices 1–6 (design-system theme → grouped sidebar shell → dark animated login hero → role-aware dashboard → work-queue/table polish → detail-page finish) **plus the UI-feedback track slices 7–9**: slice 7 ✅ **native-select label fix** (pinned `inputLabel: { shrink: true }` on every `native: true` select — the garbled "Select a…" dropdown fix, 16 sites/10 files); slice 8 ✅ **depth & color pass** (subtle fixed canvas wash + soft layered card shadow + faint table zebra + a live dashboard: gradient-accent stat cards with teal numbers + hover lift); slice 9 ✅ **Light/Dark/System theme mode** (MUI CSS-variable `colorSchemes`, `defaultMode="system"` follows the device, a Light/Dark/System toggle in the sidebar footer persisted in localStorage, a pre-hydration script so no light flash; the **dark scheme echoes the Constellation hero** so the whole app feels like the hero world). All verified in-browser (both modes) + typecheck/187 tests/build + CI green.
  - **Phase 10 COMPLETE ✅** Cloud deployment & CI/CD — slice 15 ✅ **CloudFront HTTPS — the live Cognito login completes over HTTPS** (Cognito arc 11–15 COMPLETE): `cloudfront.tf` puts a CloudFront distribution in front of the ALB — free trusted `*.cloudfront.net` cert (no domain/ACM, since ACM can't cert the ALB name), `redirect-to-https`, all HTTP methods, managed `CachingDisabled` + `AllViewer` (forward all cookies/headers/query for the session+CSRF+OAuth params), `PriceClass_100`. `cognito.tf` registers the CloudFront HTTPS callback/logout; `ecs.tf` **pins** the OIDC redirect_uri to the https CloudFront callback via the Spring relaxed-binding env `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_COGNITO_REDIRECTURI` (**no image rebuild** — behind CloudFront→ALB the ALB hop is HTTP, so Spring would otherwise compute an http redirect_uri Cognito rejects). `terraform apply` = **2 add / 2 change / 1 destroy** (ALB/ECS not recreated; CloudFront ~3 min; ECS rolled to task-def rev 3). App at **`https://d3rj9qdohmthpy.cloudfront.net`**. Verified over HTTPS: health UP w/ db UP; after the rollout completed, `/oauth2/authorization/cognito` → 302 with `redirect_uri=https://…cloudfront…/login/oauth2/code/cognito` (registered in Cognito); browser — app loads over HTTPS and "Sign in with Cognito" reaches the Cognito hosted login. Final password step is the user's. Limitations: local-only logout, ALB still HTTP-reachable directly, `local,cognito` profile (seeded users back the logins) — all documented follow-ups. CloudFront ~$0; app ~$0.08/hr; on-demand destroy. · slice 14 ✅ **redeploy the Cognito-capable app on the ALB (HTTP) + OAuth proxy**: the deployed images were pre-Cognito (slice 9), so both were **rebuilt (arm64) + re-pushed via crane** (`latest` + `sha-d3b6ac9`) — backend now carries the slice-12 OAuth2 code + `application-cognito.yml` (verified in the jar), frontend the slice-13 button + the new nginx proxy. `frontend/default.conf.template` proxies **`/oauth2/`+`/login/oauth2/`** to the backend; `cognito.tf` puts the **client secret in Secrets Manager** (injected into the task like the DB password); `ecs.tf` sets `SPRING_PROFILES_ACTIVE=local,cognito` + `COGNITO_CLIENT_ID`/`COGNITO_ISSUER_URI` + `SERVER_FORWARD_HEADERS_STRATEGY=framework` and injects `COGNITO_CLIENT_SECRET`. `terraform apply` = **7 add / 0 change / 2 destroy** (recreated ALB/ECS pulling the new images + new task-def/policy + the secret). Verified: health UP; **`/oauth2/authorization/cognito` → 302 to Cognito through the deployed nginx** (PKCE); the deployed login page shows **only "Sign in with Cognito"** (prod build hides dev-login); dev-login/`/me` regression intact. The redirect_uri is the external ALB host (forwarded-headers working). Login can't complete yet — HTTP + unregistered callback — that's slice 15 (CloudFront HTTPS). Restarts ~$0.08/hr from credits; on-demand destroy. · slice 13 ✅ **frontend "Sign in with Cognito" (SPA login button; proven locally E2E, $0)**: `LoginPage.tsx` gains a primary **"Sign in with Cognito"** button — a full-page link to `/oauth2/authorization/cognito` (a redirect, not a fetch) — and the dev-login dropdown moves under a **"Developer sign-in (local only)"** section gated by `import.meta.env.DEV`, so the **production bundle shows only Cognito**. `vite.config.ts` proxies `/oauth2` + `/login/oauth2` → :8080 (scoped, not the SPA's `/login` route). `cognito.tf` adds the Vite dev-server callback/logout URLs (`http://localhost:5173/…`) — needed because through the SPA proxy the backend computes the :5173 callback — applied **via `-target` on the client** ($0, in-place). New `LoginPage.test.tsx`. Verified: typecheck clean, **184 tests** (39 files) pass, build OK; and end-to-end in the browser — clicking the button redirected SPA → vite proxy → backend → the **real Cognito hosted login page** with `redirect_uri=http://localhost:5173/login/oauth2/code/cognito`. The interactive password step + deployed nginx proxy + prod HTTPS callback are slice 14. · slice 12 ✅ **backend Spring Security OAuth2 BFF — real Cognito login (proven locally, backend-only, $0)**: the backend now authenticates via the slice-11 Cognito pool (ADR-004 BFF). Added `spring-boot-starter-oauth2-client`; a `cognito`-profile `application-cognito.yml` (issuer/client-id/scopes/redirect + `user-name-attribute: email`; **client secret from `${COGNITO_CLIENT_SECRET}` env, never committed**); `SecurityConfig` conditionally enables `.oauth2Login` **only when a `ClientRegistrationRepository` is configured** (via `ObjectProvider`, so offline/local/CI boot on dev-login alone); and `CognitoOidcUserService` which **rejects a Cognito login with no ACTIVE `AppUser`** (matched by email) so no orphan session forms. **Because `user-name-attribute: email` makes the OIDC principal name = email, the whole app (roles, tenant, §21 gate) works unchanged** — Cognito only establishes the session; roles still come from the DB (rule 4). dev-login stays under `local`. Verified: `CognitoOidcUserServiceTest` 3/3 green; ran locally (`SPRING_PROFILES_ACTIVE=local,cognito`) against the real pool → app boots (OIDC discovery loaded), `GET /oauth2/authorization/cognito` → **302 to Cognito** (`response_type=code`, scopes, redirect_uri, **PKCE** S256), and dev-login regression intact (`/me` = Dana Provider). Interactive login round-trip is a manual step (needs `admin-set-user-password`; the assistant doesn't authenticate with passwords). **$0**, no AWS changes. Next: frontend sign-in (13) → deploy w/ HTTPS (14). · slice 11 ✅ **Amazon Cognito user pool (real-auth infrastructure; INFRA ONLY, $0)**: `infrastructure/terraform/cognito.tf` stands up the identity provider that will replace the local dev-login (ADR-004: Cognito + Spring BFF) — an `aws_cognito_user_pool` (email sign-in, auto-verified, MFA **OPTIONAL**/TOTP not enforced, `deletion_protection=INACTIVE`), a **confidential** `aws_cognito_user_pool_client` (`generate_secret=true` for the BFF; OIDC **authorization-code** flow, scopes `openid`/`email`/`profile`; local-dev callback `http://localhost:8080/login/oauth2/code/cognito` — HTTPS URLs added in the deploy slice), a free hosted-UI domain (`healthcloud-dev-927747714796.auth.us-east-1.amazoncognito.com`), and **2 synthetic users** (`provider@`/`admin@northcare.example.org`) with **no password in state** (set post-apply via `admin-set-user-password`). Applied **via `-target`** (Cognito only) so the torn-down ALB/ECS were **not** recreated. Verified: pool `us-east-1_YA95ksq5k`, client secret present, OIDC discovery + authorize/token endpoints resolve, hosted login page renders over HTTPS. Outputs `cognito_user_pool_id`/`cognito_client_id`/`cognito_client_secret`(sensitive)/`cognito_issuer_url`/`cognito_hosted_ui_domain`. **$0** (free tier) — safe to leave up. Next: backend Spring Security OAuth2 BFF wiring (map Cognito login → app user/org/roles), proven locally. · slice 10 ✅ **ECS Fargate + ALB — the app is LIVE on AWS**: `infrastructure/terraform/ecs.tf` + `alb.tf` run the app as ONE Fargate task holding both containers (backend + frontend nginx) over `localhost` — the cheapest shape, images reused unchanged (only env vars). Backend on **8081** (`SERVER_PORT`) so nginx (8080) can proxy `/api`+`/actuator` to it; **ARM64** runtime (the images are arm64); the RDS master password is **injected from Secrets Manager** by the ECS agent (never in state); backend runs the `local` profile (dev-login + synthetic seed → the URL is demoable before Cognito) with Kafka off (no MSK). An ALB (public subnets, HTTP :80) forwards to the frontend target group (:8080). `rds.tf` **tightened** in-place: Postgres 5432 now only from the app SG. **13 added, 1 changed, 0 destroyed**; task healthy ~60 s. Verified live at `http://healthcloud-dev-alb-604958717.us-east-1.elb.amazonaws.com`: `/actuator/health` UP + db UP (ALB→nginx→backend→RDS), SPA served, `dev-login`+`/me` (Dana/NorthCare/PROVIDER) over HTTP, seeded patients **relationship-gated + consent-masked** (`dateOfBirth` Restricted), browser Patients page renders. **~$0.08–0.10/hr from credits (card untouched) — on-demand: `terraform destroy` returns to ~$0** (state bucket kept). HTTP only this slice (HTTPS with the CloudFront/Cognito slices). Output `app_url`. · slice 9 ✅ **ECR + images pushed**: `infrastructure/terraform/ecr.tf` created 2 container registries — `healthcloud-dev-backend`, `healthcloud-dev-frontend` (scan-on-push, a keep-last-5 lifecycle policy, `force_delete`) — applied ($0); output `ecr_repository_urls`. **Both images are now in ECR** (tags `sha-c1e42ab` + `latest`), built **natively as `linux/arm64`** on the Apple-Silicon Mac → **the ECS Fargate slice must run ARM64** (Graviton, ~20% cheaper). Pushed with **`crane`** after `docker push` repeatedly timed out on the home uplink and Docker Desktop wedged on a stale `Docker.raw` VM lock (cleared by a Mac restart); `crane push` (from a `docker save` tarball) retries/streams instead of timing out — backend ~2 min, frontend seconds. · slice 8 ✅ **RDS PostgreSQL**: `infrastructure/terraform/rds.tf` stands up a managed **Postgres 17** database (`db.t4g.micro`, 20 GB gp3, **encrypted at rest**) in the **private subnets** (DB subnet group across 2 AZs), **not publicly accessible**, with the **master password managed in Secrets Manager** (`manage_master_user_password` — never in code/state; the app reads it via IAM later). A security group allows Postgres 5432 **only from the VPC CIDR**. Single-AZ + backups off + `skip_final_snapshot` → cheap and clean to `destroy`/recreate. 3 resources applied; verified `available`, `postgres 17.9`, publicly_accessible=false, encrypted=true. **Free-tier-eligible** (`db.t4g.micro`/20 GB) → ~$0, plus ~$0.40/mo for the secret, from credits; on-demand. Outputs `db_address`/`db_port`/`db_name`/`db_master_secret_arn` feed the ECS slice. · slice 7 ✅ **VPC & networking**: `infrastructure/terraform/network.tf` creates the network the app will run in — a VPC (`10.0.0.0/16`, DNS on) + internet gateway + **2 public subnets** (ALB/Fargate, auto public IP) + **2 private subnets** (RDS) across 2 AZs (us-east-1a/1b) + a public route table (0.0.0.0/0 → IGW) + a local-only private route table + associations. **Deliberately NO NAT Gateway** (public subnets give Fargate internet egress → ~$32/mo saved; private subnets need none). 13 **free** resources applied (**$0**), `plan` clean, verified via `aws ec2` (VPC `vpc-0f5ae5185f6331cdb`, 2 public + 2 private subnets, zero NAT). Outputs `vpc_id`/`public_subnet_ids`/`private_subnet_ids` feed the next slices. · slice 6 ✅ **Terraform remote-state backend (S3)**: a small local-state `infrastructure/terraform/bootstrap/` config created the state bucket `healthcloud-tfstate-927747714796` (versioned + encrypted + private), and the main config's `backend.tf` now uses that S3 bucket with **S3-native `use_lockfile` locking (no DynamoDB)**; `init -migrate-state` moved state to S3 (confirmed present, 802 B). This is the **first applied AWS resource** — done on the user's real, newly-activated **Free Plan** account (id 927747714796, us-east-1, $100 credits; usage above free tier draws from credits, card untouched; a $5 budget alarm + Cost Anomaly Detection guard it) with a two-gate go-ahead; **cost ~$0** (empty bucket + kilobyte state). The bucket is **kept** (not part of the deploy/destroy cycle). AWS CLI installed via the official pkg (not brew — expat breakage). Next: VPC/networking → RDS → ECR → ECS Fargate → the live app (**NO MSK** — event-driven proven locally). · slice 5 ✅ **Terraform skeleton**: `infrastructure/terraform/` establishes the IaC foundation — version pins (Terraform ≥1.9, AWS provider ~>6.0, locked 6.65.0), the `aws` provider with `default_tags`, region/project/environment variables, `name_prefix` + `common_tags` locals, outputs, and a `backend.tf` documenting the default **local** backend now with the S3 remote backend deferred. It **declares NO resources**, so it needs **no AWS account, no credentials, and costs $0** (`plan` = output values only). The first AWS-account-needed, cost-incurring step is a later `apply` slice, which will be announced. · slice 4 ✅ **publish the frontend image in CI**: a `frontend-image` job in `.github/workflows/ci.yml` (`needs: frontend`) mirrors slice 2 — builds the frontend image on every change and **pushes it to GHCR** (`ghcr.io/nikhil-oggu/healthcloud-frontend`, tags `sha-<short>` + `latest`) **only on push to `main`** (PRs build, don't push). Both image jobs now use distinct GHA cache scopes (`backend`/`frontend`) so they don't evict each other. **Both deployable images now build + publish in CI**; still zero AWS/cost. · slice 3 ✅ **containerize the frontend**: a multi-stage `frontend/Dockerfile` (build the SPA with Node, serve it from a **non-root nginx** that reverse-proxies `/api`+`/actuator` to the backend, same-origin — the production mirror of the Vite dev proxy) + `frontend/default.conf.template` (SPA deep-link fallback + a `${BACKEND_UPSTREAM}`-templated proxy) + `frontend/.dockerignore` + an opt-in `frontend` service in `docker-compose.yml` (`full` profile, host :8081 → :8080). The whole app now runs as containers (frontend → backend → postgres); **local-only, zero AWS/cost**; verified: 77 MB image, SPA served, `/actuator/health` + `/api/v1/me` proxied to the backend, deep-link fallback works, runs non-root. · slice 2 ✅ **build + publish the backend image in CI**: a new `backend-image` job in `.github/workflows/ci.yml` (`needs: backend`, so only a tested image publishes) builds the image via `docker/build-push-action` on every change and **pushes it to GHCR** (`ghcr.io/nikhil-oggu/healthcloud-backend`, tags `sha-<short>` + `latest`) **only on push to `main`** — PRs build but don't push; auth via the automatic `GITHUB_TOKEN` (`packages: write`), no secrets; `linux/amd64` + GHA layer cache; still zero AWS/cost. The versioned, pullable artifact a cloud deploy will later pull. · slice 1 ✅ **containerize the backend**: a multi-stage `backend/Dockerfile` (build the jar on JDK 25, run on a slim JRE as a non-root `spring` user, `/actuator/health` HEALTHCHECK) + `backend/.dockerignore` + an opt-in `backend` service in `docker-compose.yml` behind the `full` profile (everyday `up -d postgres kafka` unchanged), wired to the Postgres container. The artifact AWS ECS Fargate will run later — **local-only, zero AWS/cost**. Verified: `docker build` packages the jar (tests skipped in-image — they need Testcontainers and gate in CI), then the container boots against Postgres with health UP (db connected), runs as non-root, default Spring profile (production shape). Outbox relay disabled for the containerized run (cross-container Kafka is a later slice).
  - **Phase 9 ✅** Search / reporting / accessibility — slice 1 ✅ server-side pagination + filtering (`PageResponse<T>` + `PageRequests` sort-allowlist; claims queue paged in SQL) · slice 2 ✅ paged claims-queue UI (MUI pagination + sortable columns + status filter) · slice 3 ✅ prior-authorizations queue paged · slice 4 ✅ referrals + appeals + claim-reviews queues paged (patient-gated family complete) · slice 5 ✅ reprocessing + audit + dead-letter queues paged — **every work queue is now paginated** (audit also gained a server-side action filter + real paging in place of its 200-row cap) · slice 6 ✅ **free-text search** on the claims queue (reusable `SearchTerms` LIKE-escape helper + a `q` param matching the PHI-free claim number in SQL + a debounced search box) · slice 7 ✅ **free-text search across the five other numbered queues** (prior-auth/referrals/appeals/claim-reviews/reprocessing, each by its own business number) · slice 8 ✅ **free-text search on audit + dead-letters** (by their id fields — **all eight work queues now searchable**; Phase 9 search COMPLETE) · slice 9 ✅ **CSV export with masking** (reusable `common.Csv` formatter — RFC 4180 + formula-injection defusing — and `patients/export.csv` that reuses the field-masked list read so a masked DOB exports blank; Export CSV button on the patients page — the "field-masking meets data export" proof) · slice 10 ✅ **WCAG 2.2 AA-aligned accessibility pass** (axe-core test gate `expectNoAxeViolations`; app-shell skip link + `<nav>` landmark + brand-not-heading; a shared `PageHeading` gives every route one `<h1>`; verified in-browser). **Phase 9 COMPLETE ✅.**
  - **Phase 8 ✅** Event-driven — transactional outbox → relay → Kafka → idempotent consumer → retry/DLT → drain → inspect → replay (+ ops UI).
  - **Phase 7 ✅** Advanced security/governance — audit log, per-org HMAC tamper-evident chain, break-glass emergency access, access review, data retention.
  - **Phase 6 ✅** Advanced claims — prior auth, referrals, appeals, anomaly signals, manual review, reprocessing, provider network (all backend + UIs).
  - **Phase 5 ✅** Basic adjudication engine — deterministic/explainable; deductible/copay/coinsurance/OOP-max + accumulators; versioned re-adjudication.
  - **Phase 4 ✅** Clinical context & claims intake — global medical-code catalog, clinical summaries, claim aggregate, coverage plans + eligibility.
  - **Phase 3 ✅** Consent, authorization, privacy & documents — §21 layered access gate, §22.5 consent+purpose engine, §23 field masking, secure documents.
  - **Phase 2 ✅** Care coordination — patients, service requests + state machine, comments, assignment.
  - **Phase 1 ✅** Foundation & multi-tenant identity — org/facility/user/role, session auth, tenant isolation, React shell.
  - **Phase 0 ✅ · Environment ✅** repo skeleton, CLAUDE.md, PLAN.md, ADRs, local toolchain.
- **Repo:** https://github.com/Nikhil-Oggu/healthcloud (private, branch `main`)
- **Phase 7 COMPLETE ✅ (advanced security/governance):** slice 1 ✅ — **security audit event log** (the
  audit-trail foundation): a tenant-owned, append-only, immutable `audit_event` + `AuditService.record(...)` written
  inside the domain action's own transaction (§31.6), wired into adjudication (`CLAIM_ADJUDICATED`) + consent revoke
  (`CONSENT_REVOKED`); a role-gated read `GET /api/v1/audit-events` (AUDITOR/ORG_ADMIN); the long-unused AUDITOR role
  gets its first job. slice 2 ✅ — **tamper-evident audit chain**: each event is now a link in a **per-org HMAC-SHA256
  hash chain** (`sequence_no` + `prev_hash` + `entry_hash`, appended under a `PESSIMISTIC_WRITE`-locked
  `audit_chain_head`), keyed by a per-org key derived from a master secret held in config (not the DB); a
  `GET /api/v1/audit-events/verify` recomputes the chain and detects any modified/deleted/reordered/inserted/truncated
  row. slice 3 ✅ — **audit-trail UI**: an auditor-facing **Audit** page (`/audit`, nav gated to AUDITOR/ORG_ADMIN)
  — a recent-events table (with a visible truncated fingerprint per row + a client-side action filter) and a
  **Verify integrity** button that surfaces the slice-2 verdict as a green "chain intact" / red "tampering detected
  at sequence X" banner. slice 4 ✅ — **break-glass emergency access** (backend): a PROVIDER self-grants **time-boxed**
  access to a patient they're not assigned to by recording a reason (`POST /api/v1/break-glass`); `PatientAccessGuard`
  honours a live grant so the whole patient record becomes reachable, and a `BREAK_GLASS_INVOKED` event lands on the
  audit trail. Never crosses tenants; expires automatically. slice 5 ✅ — **break-glass UI** (frontend): a PROVIDER who
  hits a patient's secure-404 gets an **emergency-access panel** (break the glass with a reason, id from the URL) in
  place of the error screen — on success the page reloads with access; plus an **Emergency access** page (`/break-glass`)
  listing the provider's live grants. slice 6 ✅ — **access review of break-glass** (backend): an admin/auditor sees
  every live grant in the tenant (`GET /api/v1/break-glass/all`, provider name resolved), and an admin can **revoke**
  a grant early (`POST /api/v1/break-glass/{id}/revoke`) — access ends at once and a `BREAK_GLASS_REVOKED` event is
  audited. slice 7 ✅ — **access-review UI** (frontend): an **Access review** page (`/access-review`, nav gated to
  AUDITOR/ORG_ADMIN) listing every live grant (provider name · patient link · reason · window), with a **Revoke**
  button (inline confirm) shown only to ORG_ADMIN. slice 8 ✅ — **data retention** (backend, the last Phase 7
  area): an ORG_ADMIN purges long-expired break-glass grants for their tenant (`POST /api/v1/retention/break-glass/run`),
  removing the sensitive free-text reason once a grant is past a configurable window
  (`healthcloud.retention.break-glass-days`, default 90) — while the **audit trail is preserved** (the
  `BREAK_GLASS_INVOKED`/`REVOKED` events are permanent — never purged, that would break the hash chain), and the
  purge itself is audited as a `RETENTION_PURGED` event in the same transaction (§31.6). Tenant-scoped; a live or
  recently-expired grant is never touched. Manual trigger (a scheduled purge is Phase 8). **Phase 7 COMPLETE ✅.**
- **Phase 9 IN PROGRESS 🚧 (search / reporting / accessibility):** slice 1 ✅ — **server-side pagination +
  filtering for the claims work queue** (backend). Introduces the **reusable pagination foundation** every work
  queue will adopt: a new `com.healthcloud.common` package with **`PageResponse<T>`** (a stable, framework-agnostic
  page envelope — `content` + `page`/`size`/`totalElements`/`totalPages`/`first`/`last` — that we own, rather than
  Spring Data's unstable `PageImpl` JSON) and **`PageRequests.toPageable(...)`** (a pure helper that **clamps** size
  to 1..100 + page ≥ 0 and **allowlists the sort field** — an unknown field or bad direction is a clean 400, not a
  500, and a caller can't order by an arbitrary column). `GET /api/v1/claims` now takes `page`/`size`/`sort`
  (e.g. `sort=serviceDate,desc`) and returns a `PageResponse<ClaimSummaryDto>`; filtering (status), sorting,
  counting and paging all happen **in the database** now (the old in-memory status `.filter` is gone) via two
  `@Query` finders (`searchAll` for broad roles, `searchForPatients` for the gated/patient-scoped cases). **All the
  existing authorization is unchanged** — a claim is still gated by its patient (§21 layer 6: provider → assigned
  patients; broad roles → the tenant's queue; cross-tenant → secure 404), proven still to hold through the paged
  path. The frontend is untouched behaviourally: a one-line `api.listClaims()` shim requests one large page and
  returns `.content`, so every consumer keeps receiving `ClaimSummary[]` — the real page-control UI is the next
  slice. Backend 443 tests (new `PageRequestsTest` + paged repo/API coverage); frontend 151 tests still green.
  slice 2 ✅ — **the paged claims work-queue UI** (frontend). The browser now uses slice 1's envelope: a new
  `api.listClaimsPage(...)` returns the full `PageResponse`, a `useClaimsPage(params)` hook (query key carries the
  params, so a page/sort/filter change refetches; `keepPreviousData` avoids a loading flash), and the `ClaimsPage`
  gains MUI **`TablePagination`** (page + rows-per-page 10/20/50), **`TableSortLabel`** on the server-sortable
  columns (Claim # / Service date / Total charge / Status — Patient stays unsorted, it's resolved client-side),
  and a **status-filter dropdown**. Default (no active sort) preserves newest-first (`createdAt DESC`). The array
  `api.listClaims()`/`useClaims()` shim is **left untouched** — its 9 consumers (appeals/reviews/reprocessing name
  resolution + selects) still need the whole list. Frontend 154 tests (+3 interaction tests: sort toggles asc/desc,
  the pager requests page 1, the status dropdown filters); typecheck + build green. No backend change.
  slice 3 ✅ — **the prior-authorizations queue, paged (backend + UI)** — the pattern proves it generalizes to a
  second aggregate. Backend: `PriorAuthorizationRepository` gains `searchAll`/`searchForPatients` `@Query` finders
  (`Page<PriorAuthorization>`, optional in-SQL status), `PriorAuthorizationService.list` takes a `Pageable` and
  returns `PageResponse<PriorAuthorizationSummaryDto>` with **identical** authorization (patient gate; empty gated
  set → empty page), and the controller adds `page`/`size`/`sort` (allowlist `{createdAt, authNumber, procedureCode,
  requestedServiceFrom, status}`, default `createdAt DESC`); the three dead unpaged finders were removed and
  `existsApprovedCovering` (the adjudication hook) preserved. Frontend: because **nothing but the queue page**
  consumes this list, `api.listPriorAuthorizations` was converted **directly** to return the envelope (no array
  shim needed, unlike claims); `usePriorAuthorizations(params)` is now paged (`keepPreviousData`), and
  `PriorAuthorizationsPage` gained MUI `TablePagination` + `TableSortLabel` (Auth # / Procedure / Requested from /
  Status; Patient stays client-resolved) + a status-filter dropdown. Backend 449 tests (+6: paged repo + API,
  incl. provider-scoping + cross-tenant secure-404 through the paged path); frontend 157 tests (+3 interaction).
  slice 4 ✅ — **referrals + appeals + claim-reviews queues paged (backend + UI)** — completing the patient-gated
  work-queue family (claims, prior-auth + these three). Same shape as slices 1–3: each repository gains
  `searchAll`/`searchForPatients` `@Query` finders (appeals + claim-reviews also `searchForClaim`, for their
  `?claimId=` filter), each service `list(...)` takes a `Pageable` → `PageResponse<...SummaryDto>` with **identical
  authorization** (patient gate; empty gated set → empty page), and each controller adds `page`/`size`/`sort`
  (allowlists: referral `{createdAt, referralNumber, specialty, reasonCode, status}`, appeal `{createdAt,
  appealNumber, status}`, claim-review `{createdAt, reviewNumber, status}`). The now-dead unpaged finders were
  removed. Frontend: like prior-auth, **nothing but each queue page consumes these lists**, so the three
  `api.list*` were converted **directly** to return envelopes (no shim); each hook is paged (`keepPreviousData`),
  and each page gained MUI `TablePagination` + `TableSortLabel` + a status-filter dropdown (Patient and Claim #
  stay client-resolved). Backend 462 tests (+13: paged repo tests incl. `searchForClaim`, new
  `ClaimReviewRepositoryTest`, + API envelope/paging/sort/status tests; provider-scoping + cross-tenant 404 still
  hold); frontend 166 tests (+9 interaction). Hit the known stray-`target/`-` 2`-class gotcha mid-build — fixed
  with `clean`.
  slice 5 ✅ — **reprocessing + audit + dead-letter queues paged (backend + UI)** — the **non-patient-gated**
  queues (tenant-scoped, role-gated, no patient set), completing the pagination rollout across **every** work
  queue. Each repository gains a paged finder (`searchAll` with an optional in-SQL filter, or a derived
  `findByOrganizationId(pageable)`); each service `list(...)` takes a `Pageable` → `PageResponse<...Dto>`; each
  controller adds `page`/`size`/`sort`. Reprocessing gains a **status** filter; **audit** gains a **server-side
  `action` filter** (replacing the old client-side filter over a 200-row cap — now real pagination +
  `searchRecent`/`searchForResource` `@Query` finders, with the tamper-evidence chain-verify finder
  `findByOrganizationIdOrderBySequenceNoAsc` left untouched), and the stale 2-option action dropdown was widened to
  all 6 `AuditAction` values (type widened too); dead-letters gains pagination + sort, replay untouched. Frontend:
  all three direct-converted (no shim); pages gained MUI `TablePagination` + `TableSortLabel` (+ reprocessing status
  / audit action dropdowns). Backend 468 tests (+6 API: envelope + sort-400 + the new filters; role-gate 403 +
  tenant-scoping still hold through the paged path); frontend 169 tests (+3 interaction).
  slice 6 ✅ — **free-text search on the claims queue (backend + UI)** — the first Phase 9 area beyond pagination.
  A new reusable **`com.healthcloud.common.SearchTerms.likeContains(raw)`** helper (pure, DB-free, like
  `PageRequests`) turns a search box into a safe SQL `LIKE` pattern: blank/whitespace → `null` (no filter), else a
  `%…%` "contains" pattern with the `LIKE` wildcards `\ % _` **escaped** so a literal `%` matches a percent sign,
  not the whole table. `GET /api/v1/claims` gains a `q` param, threaded through `ClaimService.list` into the
  `searchAll`/`searchForPatients` `@Query` finders as one more in-SQL clause
  `(:q is null or lower(c.claimNumber) like lower(cast(:q as string)) escape '\')` — a **case-insensitive contains
  match on the claim number**, a synthetic PHI-free identifier (we deliberately do **not** search patient names —
  rule 5 + no sensitive data in query strings). **All the §21 authorization is unchanged** (patient gate / role
  gate / tenant scope / empty-set short-circuit) — search is just one more optional filter on the same paths. UI:
  a **debounced** search box (300ms) on the claims page beside the status filter; typing resets to page 0, and the
  non-paged `listClaims()` shim is untouched. **A real bug the tests caught:** without `cast(:q as string)`
  Postgres infers the nullable parameter as `bytea` and `lower(bytea)` 500s — the cast pins it to text. Backend
  474 tests (+6: new `SearchTermsTest` (4) + a repo search test + an API search test); frontend 170 tests (+1).
  slice 7 ✅ — **free-text search rolled out to the five other numbered work queues** (prior-auth, referrals,
  appeals, claim-reviews, reprocessing), reusing the slice-6 `SearchTerms` foundation unchanged. Each queue's
  `searchAll`/`searchForPatients`/`searchForClaim` `@Query` gains the same in-SQL clause
  `(:q is null or lower(<number>) like lower(cast(:q as string)) escape '\')` on its own PHI-free business number
  (auth/referral/appeal/review/batch number); each service `list(...)` takes an `Optional<String> q` (normalized
  via `SearchTerms.likeContains`) and each controller a `q` param — authorization paths unchanged. Frontend: each
  queue page gained the same debounced (300ms) search box beside its status filter (resets to page 0), and each
  `api.listXxx`/hook-params gained `q`. **Six of eight work queues are now searchable** — the two left (audit,
  dead-letters) have no business number, so their search targets differ (audit → correlationId/resourceId;
  dead-letters → eventId) and are a later slice. Backend 479 tests (+5: a repo search test on prior-auth/referral/
  appeal/claim-review + an API search test on reprocessing); frontend 175 tests (+5: a search-box test per queue).
  slice 8 ✅ — **free-text search on the last two queues (audit + dead-letters)** — **all eight work queues are now
  searchable**. Neither has a business number, so each searches its **identifier fields**: audit matches
  `correlationId` OR `resourceId` ("all events for this request / about this resource"), dead-letters `eventId` OR
  `messageKey` ("this failed event / this aggregate"). All PHI-free. The new wrinkle vs. slices 6–7: `resourceId`
  and `eventId` are **UUID columns**, so the `@Query` casts them to text before matching —
  `lower(cast(e.resourceId as string)) like lower(cast(:q as string)) escape '\'`. `AuditService.list` /
  `DeadLetterService.listForTenant` take `q` (dead-letters' derived `findByOrganizationId(pageable)` was replaced
  by a `searchAll` `@Query`); both controllers add a `q` param; role gates (AUDITOR/ORG_ADMIN; ORG_ADMIN)
  unchanged. Frontend: a debounced search box on both pages. Backend 481 tests (+2 API: audit search by resourceId,
  dead-letter search by eventId — one needed the `assertFalse` import); frontend 177 tests (+2 search-box tests).
  slice 9 ✅ — **CSV export with masking** (backend + UI): the **reporting** piece of Phase 9, and the place where
  §23 field masking meets data export. A new reusable **`com.healthcloud.common.Csv`** formatter (pure/DB-free, like
  `SearchTerms`): `field(raw)` RFC 4180-quotes a value containing a comma/quote/line-break and **defuses a leading
  formula trigger** (`= + - @` → prefixed `'`) so a downloaded file can't run a spreadsheet CSV-injection; `row(cells)`
  joins fielded cells with a `\r\n` terminator. A new **`GET /api/v1/patients/export.csv`** (`text/csv` attachment)
  **reuses `PatientService.listForCurrentTenant()`** — the *same* read the JSON list uses — so the export inherits
  every layer of it: tenant scope, the relationship gate (a provider exports only their assigned patients), and
  **consent field masking** — a masked `dateOfBirth` is already `null` in the DTO, so it serializes as a **blank
  cell**, never a raw column read (§23.3: the backend is the only trusted masker; no second unmasked read path).
  Columns are PHI-minimal (Patient ID · MRN · Full name · Date of birth · Status) — the same ones the JSON read
  already exposes. Frontend: an **Export CSV** button on the patients page (mirrors the document-download handler —
  fetch the blob, object-URL save as `patients.csv`), shown to any viewer since the backend scopes/masks the file.
  Honest limitation: the whole accessible list is built in one response (no streaming) — fine at synthetic scale;
  the reusable `Csv` helper is ready to roll out to the work queues (reporting) in a later slice. Backend 492 tests
  (+9 `CsvTest` unit + 2 API: export respects masking, a provider's export is relationship-scoped); frontend 178
  tests (+1 export-button test).
  slice 10 ✅ — **WCAG 2.2 AA-aligned accessibility pass** (frontend, the last Phase 9 area): an automated
  **axe-core** gate + app-shell and heading-semantics fixes. New reusable `src/test/axe.ts` →
  **`expectNoAxeViolations(container)`** runs axe over rendered markup with the WCAG 2.0/2.1/2.2 A+AA rule tags
  (color-contrast is disabled — it needs a real rendering engine jsdom lacks, so it's a browser check). App-shell
  fixes in `AppLayout`: a **skip-to-content link** (first focusable, hidden until `:focus`, → `#main`) for WCAG
  2.4.1, a **`<nav aria-label="Primary">`** landmark, a focusable **`<main id="main">`**, and the brand demoted
  from `<h6>` to `component="div"` (not a heading). A shared **`PageHeading`** component (`variant="h5"
  component="h1"`) is now every route's single top-level title (26 page titles converted), so each page has exactly
  one `<h1>` and a correct heading order. **Verified in-browser** (logged in as admin): the skip link reveals on
  focus and targets main, the Primary nav + main landmarks and a single `<h1>` are present, and the default MUI
  theme's contrast reads fine on the core screens. **Honest scope (rules 2–3):** AA-*aligned* — automated axe A/AA
  + keyboard/landmark/heading criteria + in-browser contrast on core screens — **not certified**; a full
  page-by-page audit and a Playwright+axe E2E gate (per the §29 stack) are documented follow-ups. Frontend 181
  tests (+3: a shell a11y test + axe on NotFound/Denied); backend untouched. **Phase 9 COMPLETE ✅.**
- **Phase 8 COMPLETE ✅ (event-driven architecture):** slice 1 ✅ — **transactional outbox foundation**
  (backend, no Kafka yet): the answer to the dual-write problem (a Kafka publish can't join a DB transaction). A
  new `outbox_event` table (V40) + `com.healthcloud.outbox` package — `OutboxService.record(aggregateType,
  aggregateId, eventType, payload)` writes an integration event from **inside the domain action's own transaction**
  (§31.6, exactly like `AuditService`), so the event commits atomically with the domain change or both roll back;
  the payload is Jackson-serialized JSON, **minimum-necessary + PHI-free** (rule 5). Wired into
  `AdjudicationService.adjudicate` as the first exemplar — a `claim.adjudicated` event (`ClaimAdjudicatedEvent`:
  claim id/number, version, outcome, money split — no patient identifiers/clinical data) — so the §31.6 one-tx
  quartet (domain change + status history + audit event + **outbox event**) is now fully real in one place. A row
  is written `published_at IS NULL` (pending); a partial index backs the future relay's poll
  (`findByPublishedAtIsNullOrderByOccurredAtAsc`). slice 2 ✅ — **the outbox relay + Kafka**: a single-node Kafka
  broker (KRaft) added to `docker-compose`, `spring-boot-starter-kafka` wired in, and an **`OutboxRelay`** —
  a `@Scheduled` poller (`OutboxRelayScheduler`, gated by `healthcloud.outbox.relay.enabled`) that reads a bounded
  batch of pending rows oldest-first, publishes each to Kafka (topic = `event_type`, key = `aggregate_id`, payload
  as value, metadata in headers) **after** its domain tx has committed, then stamps `published_at`. Delivery is
  at-least-once (consumers must be idempotent — a later slice); single-instance for now (multi-instance needs
  `SELECT … FOR UPDATE SKIP LOCKED`); retry/backoff + DLQ + replay are later slices. Verified end-to-end against a
  real Testcontainers broker (`OutboxRelayKafkaIntegrationTest`: adjudicate → relay publishes → message lands on
  `claim.adjudicated` → row marked published); the general test suite stays broker-free via a test-only
  `application-local.yml` that disables the scheduler. slice 3 ✅ — **a Kafka consumer + idempotency** (the read
  side): a `@KafkaListener` on `claim.adjudicated` (`com.healthcloud.notification.ClaimAdjudicatedConsumer`) that
  builds a PHI-free **notification** feed (`claim_adjudication_notification`, V41) purely from the event (payload +
  headers), never re-reading the claim. **Idempotent** for the relay's at-least-once delivery: it skips an event it
  has already recorded (`existsByEventId`) and the `UNIQUE(event_id)` constraint is the backstop for a race
  (`DataIntegrityViolationException` caught → treated as processed). Listeners auto-start only when
  `healthcloud.kafka.consumers.enabled` is true (the broker-free suite leaves it off). Verified against a real
  Testcontainers broker (`ClaimAdjudicatedConsumerKafkaIntegrationTest`: an event → one notification; the same
  event twice → still one). slice 4 ✅ — **consumer retry/backoff + dead-letter topic**: a `DefaultErrorHandler`
  (`KafkaConsumerErrorConfig`, auto-applied by Boot) retries a failing record a bounded number of times
  (`healthcloud.kafka.consumers.retry.max-attempts`/`backoff-ms`, default 3 × 500 ms) then a
  `DeadLetterPublishingRecoverer` parks it on `<topic>.DLT` (e.g. `claim.adjudicated.DLT`); structural failures
  (missing/invalid header → `IllegalArgumentException`, malformed payload → `JacksonException`) are **non-retryable**
  and go straight to the DLT. So a poison record never blocks the partition, and the idempotent consumer (slice 3)
  makes the retries safe. Verified against a real Testcontainers broker (`ClaimAdjudicatedDlqKafkaIntegrationTest`:
  a headerless poison message lands on the DLT with no notification; a valid message published after it is still
  consumed). slice 5 ✅ — **dead-letter drain + inspection**: a `DeadLetterDrainer` (`@KafkaListener` on
  `claim.adjudicated.DLT`) drains failed records into a `dead_letter_event` table (V42) — original topic/key/payload,
  the `eventId`/`organizationId` app headers, and Spring's `kafka_dlt-*` failure metadata (exception class +
  message) — turning "what's dead-lettered" into an ordinary tenant/role-gated read. Idempotent via a
  `UNIQUE(dlt_topic, dlt_partition, dlt_offset)`. `GET /api/v1/dead-letter-events` (ORG_ADMIN, tenant-scoped;
  role-gated list → flat 403). Verified against a real Testcontainers broker
  (`DeadLetterApiIntegrationTest`: a malformed message → DLT → drained → the tenant's admin inspects it; a
  coordinator gets 403; another tenant's admin doesn't see it). **Honest limitation:** records with no
  `organizationId` header aren't listable by a tenant admin (a platform-operator view is a later refinement).
  slice 6 ✅ — **dead-letter replay**: an ORG_ADMIN re-drives a stored `dead_letter_event` back onto its source
  topic (`POST /api/v1/dead-letter-events/{id}/replay`) once the underlying cause is fixed; a `DeadLetterReplayService`
  (runs `NOT_SUPPORTED` — no ambient tx around the Kafka send, like the reprocessing orchestrator) publishes the
  original key/payload + the consumer's required `eventId`/`organizationId` headers, then delegates to
  `DeadLetterService.finalizeReplay` (a separate bean's `@Transactional`) to stamp the record replayed
  (`replayed_at`/`replayed_by`, V43) **and** write a `DEAD_LETTER_REPLAYED` audit event atomically. **Publish-first,
  then mark**: a crash after the send just re-publishes on retry, and the consumer's `event_id` dedupe makes the
  redelivery (and a rare concurrent double-click) harmless. A cross-tenant/unknown id is a secure 404; an
  already-replayed record is a 409; the action is ORG_ADMIN-gated (403 otherwise). Verified against a real broker
  (`DeadLetterReplayApiIntegrationTest`: a seeded valid-payload record → replay → the consumer records a
  notification + the record is stamped + a second replay is 409 + a `DEAD_LETTER_REPLAYED` audit event exists; a
  coordinator gets 403; another tenant's admin gets 404). **Honest limitations:** records with a null
  `organizationId` aren't replayable by a tenant admin; `eventType`/`aggregateType`/`correlationId` weren't captured
  at drain time so they aren't restored (not needed by the current consumer).
  slice 7 ✅ — **dead-letter / replay UI** (frontend): a **Dead letters** page (`/dead-letters`, `src/deadletter/`)
  — a table of the tenant's dead-lettered messages (When · Source topic · Event ID · Failure · Payload · Status) via
  `useDeadLetterEvents` → `GET /api/v1/dead-letter-events`, with a **Replay** button (inline Confirm/Cancel, mirroring
  the Access-review Revoke) on an un-replayed record → `useReplayDeadLetter` → `POST .../{id}/replay` (invalidates the
  list so the row flips to a green **Replayed** chip); a replayed record shows the chip, no button. A **Dead letters**
  nav button gated to **ORG_ADMIN** (matching the backend list gate — narrower than the AUDITOR+ORG_ADMIN audit/
  access-review nav, so an auditor never lands on a 403 page). Verified by Vitest (`DeadLetterEventsPage.test.tsx`:
  lists rows + marks the replayed one; Replay→Confirm calls the API with the id) + typecheck + build; no live browser
  demo (no demo seed for poison messages, and the page is admin-gated). **Honest limitations:** ORG_ADMIN-only; no
  pagination/filtering yet (a Phase-9 concern); null-org records never appear (backend limitation carried over).
  **Phase 8 event-driven backend + ops UI COMPLETE ✅** (outbox → relay → Kafka → idempotent consumer → retry/DLT →
  drain → inspect → replay → UI). **Next:** Phase 9 (search/reporting/accessibility).
- **Phase 6 COMPLETE ✅ (advanced claims, slices 1–21):** all seven roadmap areas done — prior auth, referrals,
  appeals, anomaly signals, manual review, reprocessing, provider network. slice 1 ✅ — **prior authorization**: a top-level,
  patient-gated `prior_authorization` aggregate (request a planned procedure be pre-approved under a coverage
  plan) with a pure `PriorAuthTransitions` state machine — REQUESTED → APPROVED/DENIED (reviewer, stamps the
  decision) / CANCELLED (requester), reason to deny/cancel — one-tx status + history, `GET/POST
  /api/v1/prior-authorizations` + `PATCH .../{id}/status` + `.../{id}/history`, backend-only.
  slice 2 ✅ — **prior auth wired into adjudication**: a `plan_prior_auth_requirement` per plan (ORG_ADMIN-managed,
  `.../coverage-plans/{id}/prior-auth-requirements`); the engine marks a covered line **`AUTH_REQUIRED`** (member
  owes the charge, no deductible/OOP consumed) when its procedure requires prior auth and no APPROVED
  authorization covers the service date — approving a covering auth + re-adjudicating (slice 11) flips it to
  COVERED. Backend-only.
  slice 3 ✅ — **prior-auth frontend**: a work queue (`/prior-authorizations`) + detail with a status timeline and
  **Approve/Deny/Cancel** decision buttons (client mirror of `PriorAuthTransitions`, optimistic-locked), a **Prior
  auth** nav button, and the claims adjudication card now renders the **`AUTH_REQUIRED`** line outcome (surfacing
  slice 2). Frontend-only.
  slice 4 ✅ — **prior-auth request form**: a **New request** form on the queue (requester roles) — patient + plan
  + procedure (`MedicalCodePicker`) + service dates, RHF+Zod, navigates to the new auth. Prior auth is now
  complete end-to-end in the browser.
  slice 5 ✅ — **plan-prior-auth-requirement admin card**: a **Prior-auth requirements** card on the coverage-plan
  detail page (`src/coverage/CoveragePlanDetailPage.tsx`) — lists a plan's procedures that require prior auth,
  add via the reusable `MedicalCodePicker` / remove, ORG_ADMIN (a near-twin of the Exclusions card), backed by
  new `useCoverage` hooks + `api` methods against the slice-2 `.../prior-auth-requirements` endpoints. So an admin
  now manages the slice-2 requirement in the browser (closing the last "no admin UI yet" gap). Frontend-only.
  slice 6 ✅ — **referrals (backend)**: a new top-level, patient-gated `referral` aggregate — a care-coordination
  request that a patient be seen by a **specialty**, for a **coded reason** (an ICD-10-CM diagnosis FK to the
  catalog) — with a pure `ReferralTransitions` state machine (REQUESTED → APPROVED/DENIED by **CARE_COORDINATOR**/
  ORG_ADMIN — deliberately a different decision role than prior auth's reviewer — or CANCELLED by the requester;
  reason to deny/cancel), one-tx status + history, `GET/POST /api/v1/referrals` + `.../{id}` + `.../{id}/status` +
  `.../{id}/history`, server-allocated `REF-XXXXXXXX`. A near-mirror of the prior-auth aggregate; not consent
  field-masked (coded data only). Backend-only.
  slice 7 ✅ — **referral UI**: a new `src/referral/` feature folder mirroring `src/priorauth/` — a work queue
  (`/referrals`) + detail with a status timeline and **Approve/Deny/Cancel** decision buttons (client mirror of
  `ReferralTransitions`, optimistic-locked; Approve/Deny gated to CARE_COORDINATOR/ORG_ADMIN, Cancel to the
  requester), a **New request** form (patient + specialty + a **diagnosis** `MedicalCodePicker`), a **Referrals**
  nav button (staff, no reviewer), and `api`/`types` methods. The reusable `MedicalCodePicker` gained an optional
  `category` prop (default Procedure; `Diagnosis` for the referral reason). Referrals are now complete end-to-end
  in the browser. Frontend-only.
  slice 8 ✅ — **appeals (backend)**: a new top-level, patient-gated `appeal` aggregate — a dispute of a
  **claim's** decision (FK-with-org to `claim`, with the claim's `patient_id` denormalized onto the row for the
  gate + list scoping) — with a pure `AppealTransitions` state machine (SUBMITTED → UPHELD/OVERTURNED by
  **CLAIMS_REVIEWER**/ORG_ADMIN or WITHDRAWN by the submitter; **a reason is required on every transition**),
  one-tx status + history, `GET/POST /api/v1/appeals` + `.../{id}` + `.../{id}/status` + `.../{id}/history`
  (+ `?claimId=` filter), server-allocated `APL-XXXXXXXX`. Submit is gated through the parent claim and validates
  that the claim is **appealable** (ADJUDICATED/REJECTED → else 400) with **no existing open appeal** (→ 409). Not
  consent field-masked (claims-domain data). An OVERTURNED appeal records the outcome only — wiring it into
  re-adjudication is a later slice. Backend-only.
  slice 9 ✅ — **appeal UI**: a new `src/appeal/` feature folder mirroring `src/referral/` — a work queue
  (`/appeals`: Appeal # · patient · claim # · status, resolving names via `usePatients`/`useClaims`) + detail with
  a status timeline and **Uphold/Overturn/Withdraw** decision buttons (client mirror of `AppealTransitions`,
  optimistic-locked; Uphold/Overturn gated to CLAIMS_REVIEWER/ORG_ADMIN, Withdraw to the submitter; **every
  transition prompts for a reason**), a link to the disputed claim, a **New appeal** form (a claim picker filtered
  client-side to **appealable** ADJUDICATED/REJECTED claims + a reason), an **Appeals** nav button (adds
  CLAIMS_REVIEWER, unlike referrals), and `api`/`types` methods. Appeals are now complete end-to-end in the
  browser. Frontend-only.
  slice 10 ✅ — **appeal overturn wired into re-adjudication**: overturning an appeal on an **ADJUDICATED** claim
  now re-runs the adjudication engine (`AppealService` → `AdjudicationService.adjudicate`), appending a new
  immutable adjudication version under current coverage/config, in the **same transaction** as the overturn (§31.6)
  — so the appeal outcome actually moves the money. A **REJECTED** claim's overturn still records the outcome only
  (re-opening a terminal-REJECTED claim is a later slice). Backend-only, no new tables; closes the slice-8 deferred
  limitation.
  slice 11 ✅ — **claim anomaly signals**: a new `com.healthcloud.anomaly` package — an advisory fraud/waste/abuse
  detection pass over a claim (`POST /api/v1/claims/{id}/anomaly-scan` + `GET .../anomalies`, nested under the
  claim like adjudication). A reviewer (CLAIMS_REVIEWER/ORG_ADMIN) scans; the pure **`ClaimAnomalyDetector`** (a
  new *detector*-shaped pure-policy class) applies two deterministic heuristics — `DUPLICATE_CLAIM` (same patient,
  same service date, shared procedure) + `HIGH_TOTAL_CHARGE` (total over a configurable threshold). Signals
  (`claim_anomaly_signal`, migration V30) are immutable + patient-gated via the claim; a rescan **replaces** them
  so scanning is idempotent. **Additive** — never touches claim status or the adjudication math. Backend-only.
  slice 12 ✅ — **anomaly UI**: an **Anomalies card** on the claim detail page (`src/claims/ClaimDetailPage.tsx`) —
  lists the claim's current anomaly signals (severity chip + type + PHI-free detail + time) via `useAnomalies`, with
  a **Scan** button for CLAIMS_REVIEWER/ORG_ADMIN (`useScanAnomalies`) that runs the slice-11 detector and refreshes.
  New `api`/`types` methods (`scanClaimAnomalies`/`listClaimAnomalies`, `ClaimAnomalySignal`) + `anomalySeverityColor`.
  Frontend-only; live-verified end-to-end (a duplicate claim scans to a HIGH DUPLICATE_CLAIM signal). Anomaly signals
  are now complete in the browser.
  slice 13 ✅ — **claim manual review (backend)**: a new top-level, patient-gated `claim_review` aggregate (the 7th
  decision aggregate) — a review case a coordinator/reviewer opens on a claim (often prompted by anomaly signals) and
  a reviewer resolves. Pure `ClaimReviewTransitions` state machine — OPEN → RESOLVED (**CLAIMS_REVIEWER**/ORG_ADMIN,
  stamps the resolver + conclusion) / CANCELLED (opener roles), reason required on every transition — one-tx status +
  history, `MRV-XXXXXXXX` number, **at most one OPEN review per claim** (partial unique index → 409). `GET/POST
  /api/v1/claim-reviews` + `.../{id}` + `PATCH .../{id}/status` + `.../{id}/history`. Migration V31. A **tracking**
  record: doesn't hold the claim or change its status. Seeder opens one demo review. Backend-only.
  slice 14 ✅ — **manual-review UI**: a new `src/claimreview/` feature folder mirroring `src/appeal/` — a work queue
  (`/claim-reviews`: Review # · patient · claim # · status) + detail with a status timeline and **Resolve/Cancel**
  decision buttons (client mirror of `ClaimReviewTransitions`, optimistic-locked; Resolve gated to CLAIMS_REVIEWER/
  ORG_ADMIN, Cancel to the opener roles; **every transition prompts for a reason**), a link to the reviewed claim, a
  **New review** form (any claim + an optional reason), a **Reviews** nav button (CARE_COORDINATOR/CLAIMS_REVIEWER/
  ORG_ADMIN), and `api`/`types` methods. Manual review is now complete end-to-end in the browser. Frontend-only.
  slice 15 ✅ — **reprocessing (backend)**: a new `reprocessing` package — batch re-adjudication of a coverage
  plan's claims after a config change. `GET/POST /api/v1/reprocessing-batches` + `.../{id}`; a `reprocessing_batch`
  (scope = a plan, status + counts, `RPB-XXXXXXXX`) owns immutable `reprocessing_item` children (per claim:
  SUCCEEDED + new version / FAILED + PHI-free message). **Orchestrates only** — reuses the slice-11
  `AdjudicationService.adjudicate` path (no math change); selects the tenant's ADJUDICATED claims currently on the
  plan. Gated CLAIMS_REVIEWER/ORG_ADMIN; in-tenant plan required (400 else); another tenant's batch → secure 404.
  **A job record, NOT a state machine** (runs synchronously → COMPLETED / COMPLETED_WITH_ERRORS; no transitions).
  **Deliberate tx-shape departure:** `createAndRun` is `NOT_SUPPORTED`, so each claim's re-adjudication is its own
  transaction — one failure is caught + recorded, never rolling back the batch or the others. Migration V32.
  Backend-only (reprocessing UI is a later slice).
  slice 16 ✅ — **reprocessing UI**: a new `src/reprocessing/` feature folder (a **job**, not a state machine — no
  transition buttons/reason prompts/optimistic locking) — a work queue (`/reprocessing`: Batch # · plan · status ·
  succeeded/failed/total) + detail with a header and a per-claim items table (claim # resolved via `useClaims`,
  outcome chip, new version / PHI-free message), a **Run batch** form (plan `<select>` from `useCoveragePlans`, RHF+
  Zod) gated to CLAIMS_REVIEWER/ORG_ADMIN, a **Reprocessing** nav button (same roles), `statusColor.ts`,
  `useReprocessing.ts`, and `api`/`types` methods. Reprocessing is now complete end-to-end. Frontend-only.
  slice 17 ✅ — **provider network config (backend)**: a `plan_network_provider` table — which PROVIDERs are in a
  coverage plan's network. `GET/POST /api/v1/coverage-plans/{planId}/network-providers`, `GET .../candidates`,
  `DELETE .../{id}`; reads same-tenant, add/remove/candidates ORG_ADMIN, dup 409, cross-tenant plan → secure 404.
  The participant is a **provider (`app_user`)**, validated as an active same-tenant PROVIDER (else 400) via the
  identity repos (like `ProviderPatientAssignmentService`); candidate picker reuses `AssignmentCandidateDto`.
  Migration V33. **Inert config** — the claim gains a rendering provider (slice 18) and the engine marks
  out-of-network lines (slice 19) next. Backend-only.
  slice 18 ✅ — **rendering provider on the claim (backend)**: an optional header-level `renderingProviderId` on a
  claim (migration V34, nullable FK → app_user). `CreateClaimRequest` accepts it; `ClaimService.create` validates
  it is an active same-tenant PROVIDER (else 400) and stamps it; `ClaimDto`/`ClaimSummaryDto` expose it as a raw
  id. Backward-compatible (null when omitted). Backend-only.
  slice 19 ✅ — **provider network wired into adjudication (backend)**: a new `LineOutcome.OUT_OF_NETWORK` (migration
  V35) — when the covering plan defines a network (`plan_network_provider`) and the claim's rendering provider is
  present but not in it, every non-excluded line is OUT_OF_NETWORK (allowed 0, plan pays 0, member owes charge, no
  deductible/OOP), a claim-level determination. Precedence exclusion > out-of-network > auth > covered; a null
  rendering provider or a plan with no network imposes no penalty (opt-in, backward-compatible). Seeder adds a 2nd
  provider per org (`provider2@`/Morgan) + the PPO network = {Dana}. Backend-only.
  slice 20 ✅ — **provider-network UI (part 1)**: the **`OUT_OF_NETWORK` line chip** (`LineOutcome` type +
  `lineOutcomeColor` → error) so the slice-19 engine rule is visible on the adjudication card, and a **Network
  providers card** on the coverage-plan detail page (list in-network PROVIDERs by name + add via a provider select
  from the candidates endpoint / remove, ORG_ADMIN) — the slice-17 config is now browser-manageable. Frontend-only.
  slice 21 ✅ — **rendering-provider picker on claim create (UI part 2)**: a new read `GET /api/v1/providers`
  (`ProviderController`/`ProviderDirectoryService`, gated to the claim-create roles) lists the tenant's active
  PROVIDERs; the **New claim** form gained an optional "Rendering provider" select, and the claim detail header now
  shows "rendered by <name>". Also caught up the frontend `Claim`/`ClaimSummary`/`CreateClaimRequest` types to carry
  `renderingProviderId` (backend has since slice 18). **This completes Phase 6's advanced-claims areas** — provider
  network is now fully usable through the UI (admin sets the network → user picks the rendering provider → engine
  marks OON → UI shows the chip). No migration, no engine change.
- **Tooling:** HealthCloud-specific **`code-reviewer`** + **`security-reviewer`** subagents now live in
  `.claude/agents/` (read-only; project-aware checklists — tenant isolation, `PatientAccessGuard`, consent/masking,
  one-tx history, financial accumulators). Invoke by name in a fresh session (agent files load at startup).
- **Phase 5 COMPLETE ✅ (adjudication engine, completes the MVP — slices 1–12):** slice 1 ✅ — the core deterministic
  engine: `POST /api/v1/claims/{id}/adjudicate` reads an ACCEPTED claim →
  `PatientEligibilityRepository.findCovering(serviceDate)` → the coverage plan → the pure `AdjudicationCalculator`
  (allowed → copay → deductible → coinsurance) → an **immutable** `adjudication` (header + per-line breakdown)
  and moves the claim to `ADJUDICATED` in one transaction (a no-coverage claim is `DENIED_NO_ELIGIBILITY`); the
  §60 proof surface (which plan applied + how every amount was computed) reads back at `GET .../adjudication`.
  slice 2 ✅ — the **benefit accumulator**: a `benefit_accumulator` per `(patient, plan, benefit_year)` tracks
  `deductible_met`/`out_of_pocket_met`, read-and-updated inside the adjudication tx under a `PESSIMISTIC_WRITE`
  lock (§31), so the **annual deductible carries across claims** (a later claim sees less deductible remaining →
  the plan pays more) and concurrent adjudications can't lose an update.
  slice 3 ✅ — **out-of-pocket-max enforcement**: the calculator caps the member's cost-sharing so the year's
  cumulative out-of-pocket can't exceed the plan's `outOfPocketMax` (the excess shifts to the plan, recorded per
  line as `oopMaxAppliedAmount`); OOP-remaining carries across claims via the accumulator's `out_of_pocket_met`,
  so once the max is met the plan pays 100%. **The core adjudication math is now complete** (eligibility,
  deductible carry-over, copay, coinsurance, OOP max) — a natural MVP milestone.
  slice 4 ✅ — **plan exclusions**: a `plan_exclusion` per plan lists procedure codes the plan won't cover
  (`GET/POST/DELETE /api/v1/coverage-plans/{id}/exclusions`, ORG_ADMIN writes); the engine marks a matching claim
  line `NOT_COVERED` (member owes the charge, plan 0) without touching the deductible/OOP, and the claim is still
  `ADJUDICATED` with a mix of COVERED/NOT_COVERED lines.
  slice 5 ✅ — the **claims/adjudication frontend**: a claims work queue (`/claims`) + claim detail (`/claims/:id`)
  with the lines table, status timeline, lifecycle action buttons (submit/accept/reject/cancel), an **Adjudicate**
  button on an ACCEPTED claim, and the **adjudication breakdown** card — the whole money engine is now visible and
  drivable in the browser.
  slice 6 ✅ — a **claim-creation form** + reusable **medical-code picker**: create a claim in the browser
  (patient, service date, and a dynamic list of lines each with a catalog-searching procedure picker + units +
  charge) → the claims UI is now self-sufficient.
  slice 7 ✅ — the **coverage admin UI**: a coverage-plans list + New-plan form (ORG_ADMIN) and a plan detail with
  an **exclusions** card (add via the code picker / remove) → benefit config is now manageable in the browser.
  slice 8 ✅ — the **patient eligibility enrollment UI**: a **Coverage eligibility** card on the patient detail
  page listing a patient's enrollments (plan · member ID · effective period, "Open-ended" for no end) with an
  **Enroll in a plan** form (CARE_COORDINATOR/ORG_ADMIN) — plan select + member ID + effective dates → the last
  browser gap in the coverage/eligibility story is closed; the adjudication engine's `findCovering` input is now
  set up from the browser.
  slice 9 ✅ — **fee-schedule allowed amounts**: a `plan_fee_schedule` per plan prices procedure codes
  (`GET/POST/DELETE /api/v1/coverage-plans/{id}/fee-schedule`, ORG_ADMIN writes); the engine now sets
  `allowed = min(charge, fee-schedule amount)` for a priced covered line (the difference is a provider write-off
  no one pays) and falls back to `allowed = charge` for an unpriced line — replacing the old "allowed = charge"
  everywhere. All cost-sharing already keys off allowed, so the whole split becomes realistic. **Backend-only.**
  slice 10 ✅ — the **fee-schedule admin UI**: a **Fee schedule** card on the coverage-plan detail page (add via
  the reusable `MedicalCodePicker` + an allowed-amount field / remove, ORG_ADMIN; reads open to same-tenant),
  mirroring the exclusions card → the slice-9 fee schedule is now manageable in the browser. **Frontend-only.**
  slice 11 ✅ — **re-adjudication versioning**: an already-ADJUDICATED claim can be re-adjudicated (the same
  `POST /api/v1/claims/{id}/adjudicate`), writing a **new immutable version** (v2, v3…) while every prior version
  is retained and the claim stays ADJUDICATED; the engine backs out the prior version's benefit-accumulator
  contribution first so the deductible/OOP isn't double-counted, and a denied claim can flip to covered after a
  retroactive enrollment. `GET .../adjudication` returns the latest; `GET .../adjudication/versions` lists all,
  newest first. **Backend-only** — this completes the core Phase-5 adjudication engine. **The MVP (Phase 0–5)
  engine is now feature-complete.**
  slice 12 ✅ — **adjudication version history + re-adjudicate in the claims UI**: the claim detail page now shows a
  **Re-adjudicate** button on an ADJUDICATED claim (CLAIMS_REVIEWER/ORG_ADMIN), labels the breakdown with its
  **current version**, and shows a **Version history** card (all versions, newest first) once there's more than one
  → the slice-11 re-adjudication/versions endpoints are now visible and drivable in the browser. **Frontend-only.
  The full MVP (Phase 0–5) — engine AND UI — is now feature-complete.**
  **Next Phase-5 slices:** none required for the MVP. Also still deferred: a **frontend** for
  clinical summaries + claims + coverage (incl. a medical-code picker); consent masking of claim fields
  (`CLAIMS_BENEFITS`) and the fuller CLAIMS_REVIEWER business-need scoping; a close/edit endpoint for an
  eligibility period. **Phase 4 proof (§60):** a claims reviewer sees
  claim-relevant data *without* unrestricted medical context — so the CLAIMS_REVIEWER business-need scoping
  deferred through Phase 3 gets designed here. A **medical-codes UI** (a code picker) arrives when a slice first
  consumes codes. Plan each slice before building. (Older deferred items still open — Phase 3 was
  COMPLETE; run `/security-review` on the authorization stack at a good breakpoint. Deferred:
  provider/coordinator assignment PENDING→ACTIVE/→EXPIRED time sweeps — scheduler,
  Phase 8; **asynchronous document scanning** — the scan is synchronous at upload now (deterministic); the
  event-driven worker that writes PENDING then flips to CLEAN/QUARANTINED is Phase 8; **admin/break-glass
  download of a quarantined document** — Phase 7; batch consent + care-team lookups for list reads — perf follow-up; PROVIDER-scoped consent to an
  *un*assigned provider — the picker/candidates list assigned/eligible only; a patient-facing consent picker for
  PROVIDER scope currently lists their assigned providers. Phase-2 niceties: SLA/due-dates, request edit/priority
  UI.)
- **Run the frontend:** with Postgres + backend up, `cd frontend && npm run dev` → open
  http://localhost:5173 → sign in as a seeded demo user.
- **Run the demo:** `docker compose up -d postgres` then
  `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
  Log in: `curl -c j.txt -X POST localhost:8080/api/v1/dev-login --data email=admin@greenvalley.example.org`
  then `curl -b j.txt localhost:8080/api/v1/me`. Reset DB with `./scripts/db-reset.sh`.

## Log (newest first)

### 2026-09-22 — Front page → technical-dark bento + two-org credentials popup ✅ (frontend)
- **Why:** iterate the landing page (below) toward the user's preferred "technical dark" look and a stronger,
  self-serve demo entry point for recruiters. Commit `e2d86eb` (`frontend/src/auth/LoginPage.tsx` only).
- **Six roles:** added **Provider** to the header nav → Patient · Provider · Care Coordinator · Reviewer · Admin ·
  Auditor (natural-width items + uniform gap = compact, even spacing; equal-width was tried and rejected as too
  sparse). Inline on desktop, wrapped centered row on tablet/phone.
- **Fixed technical-dark theme:** the front page is now a bespoke **always-dark** surface (mode-independent, like the
  old Constellation hero) via a local `DK` token set — not the app's theme-aware light/dark. Background is an
  **aurora glow** (layered radial teal/indigo gradients); the earlier engineering-grid was removed on request.
  Gotcha fixed: `background-attachment: fixed` caused a white-band scroll composite artifact → removed.
- **Bento feature grid** ("What HealthCloud brings together"): 8 color-tinted tiles (Coordination hero + Adjudication
  span-2; Consent/Isolation/Security/Traceability; Resilience/Observability span-2) — 4 cols desktop / 2 tablet / 1
  phone. Each is a real backend-enforced capability (rule 2).
- **Credentials popup — deliberately LIGHT** (contrast on the dark page = "this is the differentiator"): a single
  **"Explore HealthCloud in action │ Credentials"** button opens it. Two org columns (**NorthCare** left · **Green
  Valley** right); each of the 6 roles is **color-matched across both orgs** (provider2 shares the Provider color);
  **bold emails**; one-click **copy buttons** on every email + password (✓ feedback); switch-roles (Incognito) tip.
- **Demo passwords are now published** (the user's explicit, informed decision — synthetic, tenant-isolated accounts;
  supersedes the old `DEMO_PASSWORD` placeholder). All 14 accounts (7 per org) with individual passwords are shown so
  a reviewer can self-serve. ⚠️ Anyone with the link can sign into the deployed demo as any role (intended).
- **Verified:** in-browser (light popup on dark page, both org columns, copy buttons); `typecheck` + **all 187 tests**
  green. Login flow itself still needs the backend on the `cognito` profile (deferred — frontend design first).

### 2026-09-22 — Front page rebuilt as a landing page (header nav + hero) + logo asset FIXED ✅ (frontend)
- **Why:** the user designed a specific landing-page layout (a reference image) and wanted the front page to match it
  **exactly**, then rendered in a "technical dark" style. Supersedes the earlier "Console" two-column login below.
- **Landing page** (`frontend/src/auth/LoginPage.tsx`, commit `c764e11`): a proper landing header —
  brand + logo glyph · **five clickable role personas** with icons (Patient, Care Coordinator, Reviewer, Admin,
  Auditor) · a rounded **"Sign in"** pill — over a hairline divider; then the **hero**: the horizontal-flowing
  headline *"Care coordinated. Consent enforced. Decisions explained."* (verbs teal-accented) + the one-line sub.
- **Theme-aware** (the user chose light+dark): **light = the reference image** (soft mint→white wash); **dark = a
  technical deep-navy field** with a teal top-glow + a faint 44px engineering grid. All via theme tokens +
  `theme.applyStyles('dark', …)`, so it follows the visitor's light/dark preference. Verified in both schemes.
- **Interactive roles** (the user's question — "if roles are clickable, what's the Sign in button for?"):
  clicking a **role** = "explore as this persona" → smooth-scrolls to the demo-credentials card and **highlights that
  role's account**. (The Sign in pill is the actual sign-in — see the follow-up below.)
- **Dev login removed from the page** (per the user): Cognito is the sign-in path. ⚠️ Consequence: local login now
  needs the backend on the `cognito` profile (no more one-click dev sign-in in the UI).
- **Follow-ups later the same session** (commits `d79bab0`, `38d94b7`):
  - **Responsive role nav** (`d79bab0`): the role personas were `display:{xs:'none', md:'flex'}`, so on **tablet and
    phone** they vanished entirely. Fixed — a `rolePersonas()` renderer used in two placements: inline on desktop
    (md+), and a **centered, wrapped second row** under the brand + Sign in below md. All five show at every width.
  - **Header "Sign in" is now the sole sign-in action** (`38d94b7`): the top-right pill is a full-page link to the
    Cognito BFF (`/oauth2/authorization/cognito`), disabled only when Cognito isn't configured. The **standalone
    "Sign in with Cognito" card below the hero was removed** (redundant); the hero now flows straight into the
    demo-credentials card, whose copy points at the top-right "Sign in". (The user has "another idea" for a richer
    sign-in area — still TBD.)
- **Logo asset FIXED** (`frontend/public/logo.png`): the previously-committed logo (from `5d19f4c`) was **100%
  transparent** — the earlier browser-canvas background-removal had flood-filled the *entire* image away (0 opaque
  pixels), so the glyph had been rendering **invisibly** all along. Redone **in pure Python** (zlib un-filter → border
  flood-fill of near-white → crop, keeping the enclosed white cross): now a real 79×79 RGBA. **Lesson:** don't ship a
  12KB base64 blob through a copy-paste boundary (the first attempt corrupted the IDAT — `file` still read the intact
  header as "79×79 RGBA", but zlib failed the data check); process the bytes end-to-end in one tool (Python `zlib`).
- **Verified:** `npm run typecheck` + **all 187 frontend tests** (incl. the axe accessibility check) green; in-browser
  in light and dark. No backend/API changes.

### 2026-09-22 — Login page redesign (theme-aware "Console") + demo-credentials card + real logo asset ✅ (frontend)
- **Why:** prepping the LinkedIn/recruiter demo (Phase 12). The login page is the first thing a stranger sees, so it
  needed a stronger, more distinctive first impression — and a way for a reviewer to actually get in and explore.
- **Demo-credentials card** (`frontend/src/auth/LoginPage.tsx`, commit `c7c03e6`): shown whenever Cognito login is
  available (deployed app, or a local `local,cognito` run). Lists key demo accounts (role · email · what-to-try hint) +
  a shared `DEMO_PASSWORD`, a "Synthetic data only" chip, and tips for seeing multi-tenant isolation (swap
  `northcare` ↔ `greenvalley`) and switching roles (open a fresh Incognito window — Cognito keeps its own SSO cookie).
  ⚠️ `DEMO_PASSWORD` is a **placeholder** to fill with the real shared demo password before deploy — no password is
  committed.
- **Login redesign** — explored 8 directions as throwaway HTML artifacts, the user chose the **"Console"
  engineering-credibility** look, then it was rebuilt **theme-aware**: brand + `CARE COORDINATION & CLAIMS` eyebrow, a
  centered headline *"Care coordinated. Consent enforced. Decisions explained."* (verbs teal-accented), a one-line hook
  sub, then balanced **Security posture** (monospace rows of honest **backend-enforced capabilities** — tenant
  isolation / consent engine / audit hash-chain / field masking — described as guarantees, NOT fake live telemetry,
  rule 2) ↔ **Sign in** columns, with the demo card below. Every color comes from the app **theme tokens**
  (`primary`/`success`/`text`/`background` + `theme.applyStyles('dark', …)`), so the login **follows the visitor's
  light/dark preference**. Verified live in both schemes. Commits: Console `3d948c8`, sharper headline `7a2ecfb`,
  theme-aware `5ba73e2`.
- **Real logo asset** (`frontend/src/components/Brand.tsx` + `frontend/public/logo.png`, commits `f79fd3e`…`5d19f4c`):
  replaced the CSS-recreated glyph with the **actual provided logo** — a transparent PNG produced by flood-filling the
  white background off the user's screenshot **in the browser canvas** (kept the enclosed white cross), cropped to
  79×80. `Brand` gained a `size="lg"` option (used on the login header). **Lesson:** to match a *designed* logo, use
  the real asset — a CSS gradient recreation never matches exactly (we burned several tries color-guessing before
  switching to the file).
- **Verified throughout:** `npm run typecheck` + the LoginPage tests (incl. the axe accessibility check) green;
  in-browser in light and dark. No backend/API changes this session.

### 2026-09-21 — Cognito enabled for ALL 14 seeded users (every role × both orgs), verified live ✅ (AWS, $0)
- **Why:** planning the LinkedIn/recruiter demo — a stranger on the deployed app can only log in via **Cognito**
  (the `demo,cognito` profile removes the local dev-login bypass), and only 6 accounts had Cognito passwords. To let
  a stranger log in as **any** role and walk the full cross-role workflow (e.g. provider files a claim → reviewer
  adjudicates → auditor sees the audit trail; and switch tenants to see isolation), we enabled the remaining 8.
- **What:** created the 8 missing pool users one by one via `aws cognito-idp admin-create-user … --message-action
  SUPPRESS` (assistant, per-action go-ahead, $0 free-tier) — `patient@`/`coordinator@`/`reviewer@`/`auditor@` for
  **both** `northcare.example.org` and `greenvalley.example.org`. The **user set every permanent password themselves**
  in a real macOS Terminal (`admin-set-user-password --permanent`); the assistant never types/handles passwords.
  All 14 now show `UserStatus: CONFIRMED`. Each was verified with a live browser login ("Welcome back <name> — <org>").
- The user also **rotated several existing passwords** during the session (providers + both admins + an auditor) to
  clean values they can retype. **No password values are stored** anywhere — only that they were set/changed.
- **Login-path reminder proven in practice:** dev-login (one-click dropdown, no password) is **local-only** and gone
  on the deploy; Cognito (email + password) is the only deployed path — hence all 14 needed Cognito provisioning.
- **Gotcha (recurred):** handing the user an inline `--password 'PLACEHOLDER'` command failed repeatedly (they ran it
  verbatim; the all-caps placeholder breaks the lowercase policy). The reliable path is the `read -s "PW?…"` prompt
  form in the **real Terminal** (not the app's inline runner, which can't feed stdin → the prompt hangs).
- **Drift note:** these 14 users are Cognito-only, not in Terraform (same intentional drift as the hand-made local
  app client). A future `terraform apply` won't know about them. Docs updated: CLAUDE.md + memory `local-cognito-dev-client`.

### 2026-09-21 — Cognito enabled for all 4 providers + both admins, verified live in-browser ✅ (AWS, $0)
- The user wanted more of the seeded demo users usable via real "Sign in with Cognito" (previously only
  `provider@northcare` fully worked; `admin@northcare` existed in the pool but had no permanent password). Enabled
  **6 accounts** on pool `us-east-1_YA95ksq5k`: the 4 provider-role accounts (`provider@`/`provider2@` × NorthCare +
  Green Valley) and both org admins (`admin@northcare`, `admin@greenvalley`).
- **Split of work (respecting the password boundary):** the assistant ran `aws cognito-idp admin-create-user
  --message-action SUPPRESS` for the 3 missing providers + `admin@greenvalley` (each per-action user-approved, $0),
  then the **user** set every permanent password themselves via `admin-set-user-password --permanent`. The assistant
  never saw or typed a password.
- **Verified all 6 live in the browser** (local `local,cognito` backend + Vite :5173): logged out → cleared the
  Cognito SSO session (hit the hosted-UI `/logout`) → clicked "Sign in with Cognito" → the **branded hosted UI**
  (dark navy + teal + HealthCloud logo) → email + password → back into the app. `/api/v1/me` after each confirmed:
  `admin@northcare`→Alex Admin/NorthCare/ORG_ADMIN, `admin@greenvalley`→Alex Admin/Green Valley/ORG_ADMIN,
  `provider@northcare`→Dana/NorthCare/PROVIDER, `provider2@northcare`→Morgan/NorthCare/PROVIDER,
  `provider@greenvalley`→Dana/Green Valley/PROVIDER, `provider2@greenvalley`→Morgan/Green Valley/PROVIDER. Same-named
  users are distinct records (different `userId`/`organizationId`) — **proves identity vs. authorization (rule 4)**
  (Cognito supplies only the email; role + tenant come from the DB) **and multi-tenant isolation** (Green Valley
  logins land in Green Valley, NorthCare in NorthCare).
- **Gotchas (documented in CLAUDE.md + memory):** the permanent-password `read -s` step needs a **real TTY** (macOS
  Terminal) — the app's inline command runner can't feed stdin, so the prompt just hangs and an empty password is
  rejected (`^[\S]+.*[\S]+$`); the shell is **zsh** (`read -s "PW?prompt"`, not bash's `read -p`); pool policy is
  min 8 + upper/lower/number, no symbol. The assistant only ever filled the **email** field in the browser.
- The user **reset several passwords** during testing (the shared-loop password for the 3 providers wasn't
  remembered) to fresh values (e.g. `GreenValley123`). **No password values are stored** anywhere — only which
  accounts are enabled + that they changed. Remaining 8 seeded users (patients/coordinators/reviewers/auditors ×
  both orgs) are still **dev-login only**. AWS drift, not in Terraform. No repo code changed — CLAUDE.md updated
  (commit `01a194f`).

### 2026-09-20 — Local Cognito login wired + hosted-UI branded + self-signup removed ✅ (AWS, $0)
- The user wanted real "Sign in with Cognito" working in **local dev**. The Cognito pool (`us-east-1_YA95ksq5k`)
  still exists, but the deploy had been torn down so the **Terraform-managed app client was gone** — and recreating
  it via `terraform apply -target` would drag in CloudFront + the ALB (cost) because the client's callback URLs
  reference the CloudFront domain. So (user-approved, after showing the plan) we created a **throwaway app client by
  hand** (`aws cognito-idp create-user-pool-client`, localhost callbacks only, `$0`, not in TF state):
  `healthcloud-local-dev`, id `2apbhhj0d4pn3pvc004nmkki6l`.
- Restarted the backend with `SPRING_PROFILES_ACTIVE=local,cognito` + `COGNITO_CLIENT_ID`/`COGNITO_CLIENT_SECRET`/
  `COGNITO_ISSUER_URI`. Verified: `/api/v1/auth/config` → `cognitoEnabled:true`, the login button activates, and
  `/oauth2/authorization/cognito` → 302 to the Cognito hosted UI (PKCE, no 500). The user set a permanent password
  (`admin-set-user-password`, they ran it themselves — the assistant never types a credential to authenticate) and
  **logged in as Dana Provider through genuine OIDC** — proven by `/me` (PROVIDER) + **zero dev-login calls** in the
  backend log since restart.
- **Branded the hosted UI** (`set-ui-customization`, per client): dark navy card, teal `#0d9488` button, light
  on-dark labels, and a **HealthCloud logo** (teal glyph + wordmark). The logo was built locally with no PIL/
  ImageMagick — an HTML lockup → `qlmanage` (WebKit renders the font) → a **pure-Python PNG auto-cropper** (stdlib
  zlib, bounding-box to content) in the scratchpad. Classic hosted UI can't style the outer grey page margin.
- **Removed self-signup** pool-wide (`update-user-pool` → `AdminCreateUserConfig.AllowAdminCreateUserOnly=true`,
  preserving MFA OPTIONAL + password policy + recovery): the "Sign up" link is gone. Appropriate — users are
  admin-provisioned and `CognitoOidcUserService` rejects any login with no ACTIVE AppUser, so self-signup was a
  dead-end anyway.
- **All $0**, all verified in-browser. Documented as AWS drift in CLAUDE.md + a session memory note: a future
  `terraform apply` reverts both (recreates its own client sans branding; re-allows signup) unless `cognito.tf` adds
  `admin_create_user_config { allow_admin_create_user_only = true }` and the branding is re-applied. Throwaway client
  kept for now (delete with `aws cognito-idp delete-user-pool-client …` when done). No repo code changed this entry.

### 2026-09-20 — Live role→screen verification + DB reseed + dev-login dropdown ✅
- **Verified the role-based UI live** by logging in as all six roles (via the CSRF-exempt dev-login) and screenshotting
  each sidebar: PATIENT (Dashboard+Requests), PROVIDER (+Patients/Referrals/Emergency access + Claims/Coverage/Prior
  auth/Appeals), CARE_COORDINATOR (+Reviews), CLAIMS_REVIEWER (Claims-side + Reprocessing), AUDITOR (Audit+Access
  review), ORG_ADMIN (everything incl. Dead letters). **All matched** the nav gating in `AppLayout.tsx` — the role UI
  is correct (backend still the real boundary).
- **Found + fixed a stale local DB:** it was missing the AUDITOR user (seeded in Phase 7; the seeder is skip-if-exists,
  so an older DB never picked it up) and carried leftover test data (Trace/Grafana patients, stray adjudications). Ran
  `./scripts/db-reset.sh` → fresh canonical seed (7 users/org incl. auditor, 6 patients, 4 claims, 17 codes, ~138 rows).
- **`LoginPage.tsx`**: the dev-login dropdown listed only 5 of 7 seeded roles per org — added **provider2 + auditor**
  (both orgs, role order). Typecheck + LoginPage tests green; verified in-browser. Committed `3bd64b0`.

### 2026-09-20 — UI polish round 2 ✅ (full pixel pass in light + dark → styled upload + un-cramped fields)
- Did a **full pixel pass of every screen in both light and dark mode** (Dashboard, Patients list + detail, Requests,
  Claims list + detail, Coverage list + detail, Prior auth, Referrals, Appeals, Reviews, Reprocessing, Audit, Access
  review, Dead letters). Verdict: the design system holds up well in both themes — no console errors anywhere; the
  cards / tinted chips / adjudication + audit tables / dashboard all read cleanly. Two things genuinely looked like
  defects, fixed this slice:
  1. **Document upload was a bare native `<input type="file">`** — default browser chrome, tolerable in light but
     clearly wrong in dark (light-grey "Choose File" button on the dark card). Replaced with a styled
     `<Button variant="outlined" component="label">Choose file</Button>` wrapping a **hidden** input (keeps the
     `aria-label="Choose a document"` on the hidden input, so the existing upload test needed no change) + a themed
     filename / "No file chosen" line. `DocumentsCard` in `frontend/src/patients/PatientDetailPage.tsx`.
  2. **Native date / number fields collapsed in row forms**, clipping `mm/dd/yyyy` → "mm/dd/" and truncating long
     labels ("Coinsurance (0–1)" → "Coinsuranc…", "Coverage start" → "Cove…"). Added `sx={{ minWidth: … }}`: enroll
     Coverage start/end (190), care-team From/To (160) + add-patient DOB (175) in `PatientDetailPage.tsx` /
     `PatientsPage.tsx`, and the four coverage-plan money fields (150) in `coverage/CoveragePlansPage.tsx`.
- **Verified:** `npm run typecheck` clean; the three affected suites green (26 tests — the upload test unchanged); and
  re-shot the Documents card + all four form rows **in light and dark** — file control now themed, all date/number
  fields show their full value + label. Backend untouched (no `mvnw` run needed).
- **Deferred (documented in CLAUDE.md):** loading skeletons for the name-resolving queues / patient-detail cards
  (brief empty/"—" flash before a secondary query resolves); make every empty table use the centered `EmptyState`;
  give the Phase-2 **Requests** queue the same search + pagination the eight newer queues have.

### 2026-09-20 — Phase 11 review fixes ✅ (code-reviewer + security-reviewer follow-ups)
- Ran the `code-reviewer` and `security-reviewer` subagents in parallel over the Phase 11 diff (`a8febde..HEAD`).
  **Security: clean** (no exploitable findings). **Code review:** no correctness bugs; one Major hardening item +
  a few minors. Fixed the ones worth fixing now:
- **[Major] Public health endpoint leaked component detail.** `show-details: always` + the public
  `/actuator/health[/**]` (reachable via nginx→CloudFront on deploy) meant an anonymous caller saw the `db`
  component (incl. a connection exception message if Postgres was down). Fixed: `show-details: when-authorized`
  in `application.yml`, with a `local`-profile document overriding it back to `always` (local isn't public).
  Added a regression test — `DeployProfileNoDevLoginTest.health_endpoint_hides_component_details_from_anonymous_off_local`
  (under `demo`: anonymous `/actuator/health` = status only, no `components`/`db`).
- **[Minor] Restore drill could false-fail on a busy DB.** It compared live-source vs restored counts, so a write
  between backup and verify (e.g. a `spring_session` row) printed "❌ do NOT trust this backup" on a good backup.
  Fixed `scripts/db-restore-drill.sh`: exclude the volatile `spring_session`/`spring_session_attributes` tables,
  reframe a mismatch as "possible concurrent writes — re-run against a quiescent DB," and document the quiescence
  assumption (script header + `docs/runbooks/backup-and-restore.md`).
- **[Minor] Test isolation.** `OutboxHealthIndicatorIntegrationTest` used `deleteAll()` on the shared
  `outbox_event` table; reworked to be baseline-relative and to delete only the rows it inserts (matches the
  suite's scope-to-your-own-rows convention).
- **[Nit] Grafana creds** — added a comment in `docker-compose.yml` that the `admin`/anonymous access is local-only
  and must never be reused for a real deployment.
- **Documented follow-up (not fixed):** role-gate `/actuator/prometheus`+`/actuator/metrics` to ORG_ADMIN
  (today authenticated but any role; PHI-free, so consistency not a hole) — noted in CLAUDE.md.
- Verified: `./mvnw clean verify` green; restore drill re-run PASSED.

### 2026-09-20 — Phase 11, slice 7 ✅ (runbooks) — Phase 11 COMPLETE ✅
- **What:** operational playbooks that tie together everything Phase 11 built (metrics, dashboards, tracing,
  health probes, alerts, backup/restore) into "when this happens, diagnose and recover like so." Docs + one
  config annotation; no backend code, no AWS, **$0**.
- **`docs/runbooks/README.md`** (new) — the entry point: the observability stack at a glance (Prometheus :9090,
  Grafana :3000, Jaeger :16686, `/actuator/*`), the three-signals model (metrics/traces/logs and how they join
  by `correlationId`↔`traceId`), the general triage workflow, and an alert→runbook index table.
- **`docs/runbooks/alert-response.md`** (new) — one section per slice-5 alert (`BackendTargetDown`,
  `OutboxBacklogHigh`, `HighHttp5xxRate`, `HighRequestLatencyP95`, `JvmHeapHigh`): what it means, how to confirm
  (real Prometheus queries / actuator endpoints), likely causes, and recovery steps — grounded in tooling that
  actually exists (rule 2), with production (AWS) equivalents noted honestly.
- **`infrastructure/observability/alert-rules.yml`** — each rule gains a `runbook` annotation pointing to its
  `alert-response.md` section (the standard "the alert tells you where the playbook is" pattern), closing the
  slice-5 ↔ slice-7 loop.
- **Verified:** `promtool check rules` → 5 rules still valid; all 5 `runbook` anchors exactly match the `##`
  headings in `alert-response.md`; cross-referenced assets confirmed real (`adjudicate-claim` `@Observed` span,
  the `/dead-letters` route, `JAVA_OPTS` in the Dockerfile, the sibling runbooks).
- **Honest scope:** runbooks target the local observability stack (AWS Prometheus/Jaeger wiring is a documented
  follow-up); the `/actuator/*` endpoints they use exist on the deployed app too.
- **Phase 11 COMPLETE ✅** — observability & recovery done end to end. Next: Phase 12 (final validation & portfolio).

### 2026-09-20 — Phase 11, slice 6 ✅ (backup & restore drill — prove we can recover)
- **What:** an automated backup + **restore drill** — because "a backup you have never restored is not a backup."
  Not just a `pg_dump`; a rehearsal that restores the dump and verifies fidelity. Local-first, **$0**, no AWS.
- **`scripts/db-backup.sh`** (new) — dumps the `healthcloud` DB to `var/backups/healthcloud-<ts>.dump`
  (custom-format `pg_dump -Fc`, git-ignored) via `docker compose exec` (pg tools run inside the postgres
  container — no host psql needed). Prints the dump path.
- **`scripts/db-restore-drill.sh`** (new) — non-destructive to the live DB: backup → create a scratch DB
  `healthcloud_restore_drill` → `pg_restore` into it → **verify `count(*)` of every public table matches source
  vs restored** → drop scratch → PASS/FAIL (non-zero exit on mismatch). The dump includes
  `flyway_schema_history`, so a restored DB passes `ddl-auto: validate`.
- **`docs/runbooks/backup-and-restore.md`** (new) — how backups work, running the drill, interpreting PASS/FAIL,
  restoring for real (deliberate/destructive — documented steps, no foot-gun script), and the production RDS DR
  equivalent (automated backups + PITR + snapshot restore) as the on-demand, approval-gated follow-up (RDS
  `backup_retention_period` is 0 for cheap teardown).
- **Bug found & fixed during verification:** the verify loop only checked **1 table** — a classic bash gotcha where
  `docker compose exec` inside a `while read` loop swallows the loop's stdin (the table list). Fixed by detaching
  stdin (`</dev/null`) on the in-container psql helpers; documented in the script + runbook + CLAUDE.md.
- **Verified:** ran `./scripts/db-restore-drill.sh` → **RESTORE DRILL PASSED ✅ — 51 tables, 283 rows restored
  identically** (all counts matched, scratch DB cleaned up). Dumps confirmed git-ignored under `var/backups/`.
- No backend code change, no AWS, $0. Remaining Phase 11: runbooks (slice 7).

### 2026-09-20 — Phase 11, slice 5 ✅ (alert rules — Prometheus alerting + an alertable outbox gauge)
- **What:** meaningful, measurable **Prometheus alert rules** over the metrics we already export, plus a small
  backend change to make the HealthCloud-specific outbox backlog alertable.
- **`infrastructure/observability/alert-rules.yml`** (new) — a `healthcloud-alerts` group of 5 rules, each with a
  `severity` label + PHI-free `summary`/`description`: `BackendTargetDown` (`up{job="healthcloud-backend"}==0`,
  1m, **critical** — the keystone), `OutboxBacklogHigh` (`healthcloud_outbox_pending > 100`, 5m), `HighHttp5xxRate`
  (5xx÷total > 5%, 10m), `HighRequestLatencyP95` (`histogram_quantile(0.95, …)` > 1s, 10m — uses the slice-2
  histogram), `JvmHeapHigh` (heap used÷max > 90%, 10m). Thresholds are demo **targets**, not measured SLOs (rule 2).
- **Wired in:** `prometheus.yml` gains `rule_files: [/etc/prometheus/alert-rules.yml]`; `docker-compose.yml` mounts
  the rules file into the `prometheus` service (read-only).
- **`OutboxMetrics`** (new, outbox package) — a Micrometer **gauge** `healthcloud.outbox.pending` backed by
  `countByPublishedAtIsNull()`, so slice 4's health signal is now a first-class **metric** on `/actuator/prometheus`
  and therefore alertable. A gauge (up/down), PHI-free (a count), polled cheaply at scrape time.
- **No Alertmanager locally** — Prometheus evaluates the rules and exposes their state without it; Alertmanager is
  only the routing/notification layer (needs external services + secrets), documented as a follow-up (fits the
  runbooks slice).
- **Verified:** `./mvnw clean verify` → **507 tests green** (+1 — `OutboxMetricsIntegrationTest` proves the gauge
  is registered and tracks the repo count, real repo/no mocks). `promtool check rules` + `check config` (run via
  the `prom/prometheus` image) → 5 rules, valid config, `rule_files` resolves. Live end-to-end: recreated
  prometheus → `/api/v1/rules` shows all 5 loaded; with the backend down `BackendTargetDown` went **pending →
  firing** (`/api/v1/alerts`); started the backend → the `healthcloud_outbox_pending` gauge (value **7**, a real
  backlog) was scraped and `BackendTargetDown` resolved to **inactive** (`up=1`).
- **Zero AWS, $0.** Remaining Phase 11: backup/restore drill, runbooks.

### 2026-09-20 — Phase 11, slice 4 ✅ (health & readiness probes + a custom outbox health indicator)
- **What:** made the actuator health story coherent — a liveness/readiness/root split, plus the first custom
  domain `HealthIndicator`. The design principle: **liveness ≠ readiness ≠ overall health.**
- **Readiness now reflects the DB** (`application.yml`): `management.endpoint.health.group.readiness.include:
  readinessState,db` → `/actuator/health/readiness` is DOWN when Postgres is unreachable (orchestrator stops
  routing traffic). **Liveness stays default** (`livenessState` only) — an external-dependency blip must never
  restart the process. (`probes.enabled: true` was already set, so the two probe endpoints already existed;
  `SecurityConfig` already permits `/actuator/health/**`, so no security change.)
- **Custom `OutboxHealthIndicator`** (`backend/.../outbox/OutboxHealthIndicator.java`) — surfaced as the `outbox`
  component on root `/actuator/health`. Reports the transactional-outbox pipeline: `pending` backlog count +
  `oldestPendingAgeSeconds` (PHI-free — counts/ages only). Status: relay disabled → UP `relay: disabled` (nothing
  publishes, don't alarm); pending ≤ `healthcloud.outbox.health.max-pending` (default 500) → UP; over →
  `OUT_OF_SERVICE` (degraded, not DOWN). **Deliberately NOT in the readiness group** — a relay backlog doesn't
  stop serving user requests, so it must never pull the pod from the load balancer; it's an operator/alert signal
  on root health only. Added `countByPublishedAtIsNull()` + `findFirstByPublishedAtIsNullOrderByOccurredAtAsc()`
  to `OutboxEventRepository` (cheap, partial-index-backed).
- **Container HEALTHCHECK → liveness** (`backend/Dockerfile`): now `curl /actuator/health/liveness` instead of the
  full `/actuator/health` aggregate, so a DB outage or a relay backlog can't get the container killed — only a
  genuine process failure triggers a restart. (This also makes the outbox `OUT_OF_SERVICE`-at-root safe.)
- **Verified:** `./mvnw clean verify` → **506 tests green** (0 failures/errors; +5 from two new test classes —
  `OutboxHealthIndicatorIntegrationTest` proves the status logic against a real repo, no mocks; `HealthProbes
  IntegrationTest` proves the three endpoints over HTTP). Live: liveness `{"status":"UP"}`; readiness UP with
  `db`+`readinessState`; root health UP exposing the `outbox` component showing a **real** backlog
  (`pending: 7, oldestPendingAgeSeconds: 47875` from prior local runs where Kafka was down) — a measured value,
  not fabricated (rule 2).
- **Deliberately out of scope (documented follow-ups):** pointing the ECS/ALB health check at
  `/actuator/health/readiness` (a Terraform `apply`, AWS boundary); tightening `show-details: always` → `when-
  authorized` on deploy; no Kafka-broker health indicator (Kafka is off on deploy — the outbox backlog is the
  meaningful domain signal). Zero AWS, $0 this slice.

### 2026-09-19/20 — Phase 11, slices 1–3 ✅ (observability foundation — backfilled log; full detail in CLAUDE.md)
> These were built, committed, and pushed (tip `53d4ae5`, all four CI jobs green) in the prior session but not
> logged here at the time — recording them now. The durable conventions live in CLAUDE.md → "Observability conventions".
- **slice 1 — metrics foundation:** `spring-boot-starter-actuator` + a `micrometer-registry-prometheus` runtime dep
  expose `/actuator/prometheus`; `application.yml` adds `prometheus,metrics` to the exposure + a common
  `management.metrics.tags.application: healthcloud`. `/actuator/prometheus` is unauthenticated **only under
  `local`** (SecurityConfig, like dev-login); the deployed `demo,cognito` app keeps it authenticated
  (`DeployProfileNoDevLoginTest` asserts this). First domain counter `healthcloud.adjudications`
  (→ `healthcloud_adjudications_total`, tagged outcome+type) incremented on the adjudication tx's **afterCommit**
  (`TransactionSynchronizationManager`) so a rollback is never counted. Tests need `@AutoConfigureMetrics` (Boot
  disables metrics exporters in `@SpringBootTest` by default).
- **slice 2 — local Prometheus + Grafana dashboards:** an `observability` docker-compose profile runs Prometheus
  (`:9090`) + Grafana (`:3000`, anonymous Viewer, auto-provisioned "HealthCloud Overview" dashboard) + Jaeger;
  config under `infrastructure/observability/`. Added `percentiles-histogram.http.server.requests: true` so p95
  latency is computable. Opt-in — everyday `up -d postgres kafka` is unchanged.
- **slice 3 — distributed tracing:** Micrometer Tracing + OpenTelemetry → local **Jaeger** over OTLP (sampling 1.0
  locally), an `@Observed` `adjudicate-claim` span nested under the HTTP span (needs the `ObservedAspect` bean in
  `observability/ObservabilityConfig`), traceId/spanId in the log pattern, Kafka observation for cross-boundary
  propagation. **Two Boot-4 gotchas** (both now in CLAUDE.md): tracing is opt-in via
  `spring-boot-micrometer-tracing-opentelemetry` **plus an explicit `micrometer-tracing-bridge-otel`** (it's only a
  runtime transitive otherwise, so `@ConditionalOnClass(OtelTracer)` misses it); and Boot 4.1 **renamed** the OTLP
  tracing endpoint property to `management.opentelemetry.tracing.export.otlp.endpoint` (the old name silently
  creates spans but never exports). Tests set `management.tracing.enabled=false` (no OTLP noise in CI).

### 2026-09-19 — UI/Design track, slice 9 ✅ (Light/Dark/System theme mode — dark scheme echoes the hero)
- **Why:** user feedback #3 — offer the viewer Light/Dark/System, defaulting to their device's setting (a recruiter whose laptop is dark should land in dark), with a toggle to override. The last of the three UI-feedback items.
- **Done (frontend):**
  - **`src/theme/index.ts` restructured to MUI CSS variables + `colorSchemes` (`{ light, dark }`)** with `cssVariables: { colorSchemeSelector: 'class' }`. The **dark scheme echoes the Constellation hero** (deep navy `#070b18` surfaces, brighter teal `#2dd4bf` / indigo `#818cf8`) so the whole app feels like the login/dashboard hero extended across every screen. Scheme-varying component overrides (AppBar, Card, TableCell, the canvas wash, the zebra, the soft status chips) now resolve per scheme via **theme vars** + **`theme.applyStyles('dark', …)`**; the chip tint uses the CSS-var `--mui-palette-<color>-mainChannel` so it follows the active scheme.
  - **`src/components/ThemeToggle.tsx`** — a Light/Dark/System `ToggleButtonGroup` in the sidebar footer, backed by `useColorScheme` (instant switch, persists in localStorage, System follows `prefers-color-scheme`). Renders nothing until mounted / without a CSS-vars provider, so unit tests (which don't wrap in `ThemeProvider`) are unaffected — `useColorScheme` returns `undefined` mode there.
  - **`main.tsx`** sets `defaultMode="system"`; **`index.html`** gains a tiny pre-hydration script that sets the `<html>` color-scheme class from stored mode / device, so a dark viewer sees **no light flash** on load.
  - The always-dark bespoke hero surfaces (login, dashboard hero band) use the fixed `constellation` tokens directly, so they're **unchanged and mode-independent** by design.
- **Verified:** typecheck + **187 tests** + build green; in the browser proved **system-default follows the device both ways** (emulated light → app light, dark → app dark), the toggle switches Light/Dark/System **instantly**, the choice **persists across reload with no flash**, no MUI/console warnings, and dark mode is AA-legible across the dashboard, a work queue and a detail page (chips, tables, mono money all adapt).
- **UI-feedback track COMPLETE ✅** (slices 7–9): dropdown label fix → depth/color pass → theme mode. Documented follow-ups (optional): a `NativeSelectField` wrapper so a native select can't forget the shrink flag; a full page-by-page dark-mode sweep of less-trafficked screens.

### 2026-09-19 — UI/Design track, slice 8 ✅ (depth & color pass — canvas wash, richer cards, live dashboard)
- **Why:** user feedback #2 — the app below the hero read as white-on-near-white with a nearly-flat shadow ("too plain / no effort"). Add tasteful depth + brand color without going loud or hurting readability.
- **Done (frontend), mostly theme-level so the whole app lifts at once:**
  - **`src/theme/index.ts`:** a subtle fixed radial **canvas wash** (faint teal top-right + indigo bottom-left, `MuiCssBaseline` body); a soft layered **card shadow** replacing the near-invisible 1px one; a barely-there **zebra** tint on even table rows (head row stays untinted; hover wins).
  - **`src/pages/HomePage.tsx`:** stat cards get a brand-gradient top accent, teal icon + teal number, and a hover lift; quick-access tiles get the same hover lift + a teal border on hover.
  - Also widened the claim form's **"Service date"** field so its label stops truncating to "Serv…".
- **Verified:** typecheck + **187 tests** + build green; in the browser the dashboard reads far more crafted, the queue tables have gentle structure, text legibility intact (all API calls 200; the console 401/500s were stale pre-login / mid-QA-restart noise, not from this change).
- **Next slice:** 9 — Light/Dark/System theme mode.

### 2026-09-19 — UI/Design track, slice 7 ✅ (pin native-select labels to shrink — the garbled dropdown fix)
- **Why:** user feedback #1 — the "Select a patient/plan/claim" **native `<select>`** dropdowns showed the floating `InputLabel` overlapping the option text (garbled letters). A native select always shows text in the box, but MUI left the label sitting mid-field.
- **Done (frontend):** added **`inputLabel: { shrink: true }`** to every `slotProps={{ select: { native: true } }}` (the MUI-recommended pattern for native selects) — **16 occurrences across 10 files** (5 selects added in later slices already had the flag; left untouched). No logic changes.
- **Verified:** typecheck + **187 tests** + build green; in the browser the Patient/Type/Priority labels on the claim, referral and request forms now sit cleanly above the box with no overlap.
- **Next slice:** 8 — depth/color pass.

### 2026-09-19 — UI/Design track, slice 6 ✅ (detail-page finish: unified BackLink) — UI/Design track COMPLETE
- **Why:** the finishing consistency pass. After slices 1–5 the app is already cohesive (theme, sidebar, dark hero, dashboard, soft chips + clean tables), so the remaining clear inconsistency was the detail pages' ad-hoc back links (`← Back to X`, styled slightly differently per page).
- **Done (frontend):**
  - New **`src/components/BackLink.tsx`** — a consistent back affordance (an `ArrowBack` icon + label, muted → teal on hover).
  - Rolled it across **all 9 detail pages** (claims, prior-auth, referrals, appeals, claim-reviews, reprocessing, requests, coverage, patients), replacing the hand-rolled `<Link>… ← Back to X` blocks. Removed the now-unused `Link`/`RouterLink` imports from the 6 pages where they were only used there.
- **Scoping decision (honest):** I deliberately **did not** do the deeper `DetailHeader`/`FormCard` restructuring across ~17 detail pages + forms that the plan floated — the app is already cohesive and that rewrite is high-regression-risk for marginal gain. The detail-page title/status blocks and the create forms already inherit the theme (cards, buttons, inputs, soft chips) and read consistently. That deeper restructuring is available as an optional future slice if desired.
- **Verified:** typecheck + build clean; **187 tests pass** (no test relied on the old `←` text); confirmed **live** — the claim detail page shows the new icon back link, the soft ADJUDICATED chip, and the clean card.
- **UI/Design track COMPLETE ✅** (slices 1–6): design-system foundation → grouped sidebar shell → dark animated Constellation login hero → role-aware dashboard → work-queue/table polish → detail-page finish. The app now presents a distinctive, cohesive "Care Constellation" identity from the login screen through every working page. Follow-ups (optional): dark-mode toggle; deeper `DetailHeader`/`FormCard` unification; a separate public marketing landing page.

### 2026-09-19 — UI/Design track, slice 5 ✅ (work-queue & table polish across all 8 queues)
- **Why:** the 8 work queues all share one structure (heading → filters → table → status chips → pagination). Polish them consistently — mostly at the **theme level** so all improve at once with low risk (no rewrite of the pagination/sort/search/filter logic).
- **Done (frontend):**
  - **Soft status chips (global, theme):** `MuiChip` override — filled colored chips now render **soft tinted** (light colored bg + strong colored text) instead of solid fills. Restyles every status chip across all 8 queues **and** every detail page at once. Outlined chips (nav/identity) keep their border. AA-contrast.
  - **Refined tables (global, theme):** `MuiTableCell` divider-colored borders + `MuiTableRow` subtle teal hover tint, keeping the tinted uppercase headers from slice 1. Applies to every table app-wide.
  - **Shared `EmptyState` component** (`src/components/EmptyState.tsx` — centered inbox icon + message) rolled across **all 8 queues** (claims, prior-auth, referrals, appeals, claim-reviews, reprocessing, audit, dead-letters), replacing the plain "No X yet." cell text while **keeping each queue's exact message** (so existing tests pass). Removed the now-unused `Typography` import from the 3 pages where it was only used for that text.
- **Verified:** typecheck + build clean; **187 tests pass** (all queue + axe tests — behavior unchanged); confirmed **live** — the claims queue shows the softer tinted chips + cleaner table, and the dead-letters queue shows the new centered empty state.
- **Next slice:** 6 — forms/detail-page polish (the last consistency pass), then the app is cohesively designed end to end.

### 2026-09-19 — UI/Design track, slice 4 ✅ (role-aware dashboard: constellation hero band + real counts + launchpad)
- **Why:** the post-login home (`HomePage.tsx`) was a bare identity card. Make it a real landing that carries the Constellation look into the app and gives each role a launchpad.
- **Done (frontend):**
  - Rewrote **`HomePage.tsx`**: (1) a **dark constellation hero band** reusing `ConstellationBackground` — *"Welcome back, {name}"* + org + role chips; (2) an **"At a glance" stat row** of **real, role-gated counts** and (3) a **role-aware quick-access launchpad** of navigation tiles (icon + title + one-line description), each a `CardActionArea` link.
  - **Honest counts (rule 2):** the stats come from the existing paged endpoints' `totalElements` (a cheap `size=1` query) via a `useQueries` fan-out gated per role (Patients, Claims, Prior auth, Referrals, Appeals, Reviews) — real tenant-scoped/relationship-gated reads, never fabricated; a card shows a dash while loading or on error.
  - New **`src/pages/HomePage.test.tsx`** (greeting h1 + role-aware tiles + a resolved real count + axe). Updated `shell.test.tsx`'s name assertion to a substring (the dashboard now greets "Welcome back, {name}").
- **Verified:** typecheck + build clean; **187 tests pass** (184 + 3 new; incl. axe); confirmed **live** logged in as ORG_ADMIN — the hero band animates, real seeded counts render (Patients 3, Claims 3, Prior auth/Referrals/Appeals/Reviews 1), and the launchpad shows all accessible areas.
- **Next slices:** 5 — work-queue/table polish (all 8 queues, one pattern); 6 — forms/detail polish.

### 2026-09-19 — UI/Design track, slice 3 ✅ (signature dark Constellation hero on the login page)
- **Why:** an unauthenticated visitor (a recruiter opening the deployed URL) is redirected `/` → `/login`, so **the login page is the first impression**. This slice makes it the bespoke "recruiter stop" surface — the dark, animated Care Constellation hero.
- **Done (frontend):**
  - New **`src/components/ConstellationBackground.tsx`** — the signature motif as a `<canvas>` (glowing nodes + connecting lines, teal→indigo) that fills its positioned parent; `aria-hidden`, resize-aware, **static when `prefers-reduced-motion`**, and it **bails cleanly in jsdom** (no 2d context) so tests are unaffected. Reusable — the Slice 4 dashboard hero will use it too.
  - Rebuilt **`LoginPage.tsx`** as a full-height dark split-hero: left = brand + a big Space-Grotesk headline (*"Care that stays **connected** — and consent that stays in control."*, with a gradient "connected"), a lede, and three trust chips (Tenant-isolated · Consent-aware · Tamper-evident audit); right = a clean **white sign-in card** (readable light components on the dark bg) with the unchanged **"Sign in with Cognito"** button + the DEV-gated developer sign-in.
  - Uses a CSS-grid two-column layout (`1fr` on mobile) — no MUI `Grid` API dependency.
- **Preserved (tests enforce):** Cognito link `href="/oauth2/authorization/cognito"`; the "Demo user" select + "Developer sign-in" button (DEV only); exactly one `<h1>` (the headline); brand + canvas non-heading/`aria-hidden`; axe clean.
- **Verified:** `npm run typecheck` + `npm run build` clean; **184 tests pass** (incl. LoginPage + axe); confirmed **live in the browser** — the dark hero renders with the animated network, gradient headline, trust chips, and the white sign-in card (desktop two-column; stacks on mobile).
- **Next slices:** 4 — role-aware dashboard (reuse the constellation hero band + stat cards); 5 — work-queue/table polish; 6 — forms/detail polish.

### 2026-09-19 — UI/Design track, slice 2 ✅ (app shell + navigation: grouped sidebar)
- **Why:** the shell crammed ~16 role-gated items into one horizontal top bar (cluttered, wrapped, "regular"). A grouped **sidebar** is the biggest single "real product" upgrade and touches one shared file (`layout/AppLayout.tsx`).
- **Done (frontend):**
  - Rebuilt `AppLayout.tsx` as a **permanent left sidebar on desktop / temporary drawer + hamburger top bar on mobile** (`useMediaQuery(up('md'), { defaultMatches: true })` — `defaultMatches:true` so the permanent sidebar renders in jsdom tests, keeping the single `Primary` nav landmark the a11y test asserts).
  - **Grouped, role-gated nav with icons + active-route highlight** (teal tint + `primary.dark`): **Care** (Dashboard, Patients, Requests, Referrals, Emergency access) · **Claims & coverage** (Claims, Coverage, Prior auth, Appeals, Reviews, Reprocessing) · **Governance** (Audit, Access review, Dead letters). Brand mark at top; user identity (name · org · role chips) + Log out in the footer.
  - **Removed the two dead disabled placeholders** ("Coordination", "Administration").
  - Added dependency **`@mui/icons-material`** (`^9.4.0`) for the nav icons.
  - Accessibility preserved (the tests enforce it): skip link first (`href="#main"`), sidebar is the `<nav aria-label="Primary">`, `<main id="main" tabIndex={-1}>`, brand non-heading, axe clean. **Fix hit en route:** MUI `ListItemButton` renders a `<div>`, so putting it directly under `<ul>` broke list semantics (axe "list" violation) — wrapped each in `<ListItem disablePadding>`.
- **Verified:** `npm run typecheck` + `npm run build` clean; **184 tests pass** (incl. the axe + shell tests); confirmed live in the browser logged in as ORG_ADMIN — all **13 grouped nav items + Log out** render with the active highlight (desktop), and the sidebar collapses to a hamburger top bar (mobile).
- **Next slices:** 3 — the signature dark **animated Constellation hero** on landing + login; 4 — role-aware dashboard; 5 — work-queue/table polish; 6 — forms/detail polish.

### 2026-09-19 — UI/Design track, slice 1 ✅ (design-system foundation: "Care Constellation" theme, fonts & brand)
- **Why:** the UI was plain default MUI (`src/main.tsx` called `createTheme()` with zero customization). We chose an original design direction — **Care Constellation** (dark, network-motif identity on the entry surfaces; light + readable in the deep app) after comparing three live concept mockups. This slice lays the foundation that restyles every page at once.
- **Design spec first:** `docs/design/design-system.md` — the durable "design CLAUDE.md" (identity, the hybrid dark-hero/light-app rule, all color tokens for both variants, typography, shape/elevation/spacing, component conventions, motion/accessibility, build order).
- **Done (frontend, no backend changes):**
  - `frontend/src/theme/index.ts` — the real MUI theme (light-app variant): teal primary `#0d9488` + indigo accent `#4f46e5`, soft `#f6f8fb` background, AA status colors; **Space Grotesk** headings + **Inter** body + **IBM Plex Mono** (exported `MONO`) for codes/IDs; radius 12; soft elevation; component defaults (light `AppBar` instead of the default blue, bordered cards, no-uppercase buttons, tinted table heads, pill chips). Also exports `constellation` dark-hero tokens for the bespoke landing/login/dashboard surfaces in later slices.
  - `frontend/src/components/Brand.tsx` — the gradient rounded-square logo + Space Grotesk wordmark (`compact` + `onDark` variants).
  - `frontend/index.html` — Google Fonts `<link>` (Inter / Space Grotesk / IBM Plex Mono) + an inline-SVG gradient favicon.
  - `frontend/src/main.tsx` — uses the new theme.
- **Verified:** `npm run typecheck` + `npm run build` clean; **184 frontend tests pass** (incl. the axe accessibility gate — styling didn't break structure); confirmed live in the browser (login page: Space Grotesk brand, teal rounded CTA, soft off-white surfaces).
- **Next slices:** 2 — app shell + navigation (sidebar + refined top bar + page-header pattern); 3 — the signature dark **animated Constellation hero** on landing + login; 4 — a role-aware dashboard; 5 — work-queue/table polish; 6 — forms/detail polish.

### 2026-09-19 — Phase 10, auth-hardening slice ✅ (close the dev-login bypass on the deploy; lock the ALB to CloudFront; trim Cognito auth flows)
- **Why:** the code-reviewer + security-reviewer subagents (run in parallel over the Phase 10 diff) both flagged, high-confidence, that the deployed app ran `SPRING_PROFILES_ACTIVE=local,cognito` — and the `local` profile keeps `/api/v1/dev-login` (a permitAll, CSRF-exempt, unauthenticated email→session endpoint) live. Anyone could `POST /api/v1/dev-login?email=admin@northcare.example.org` and get an ORG_ADMIN session, **bypassing Cognito entirely**. (The app was torn down, so nothing was exposed — but it was a real hole on every `apply`.) Two lesser items came with it: the ALB was reachable directly over plain HTTP (bypassing CloudFront's HTTPS), and the Cognito client enabled `ALLOW_USER_PASSWORD_AUTH`/`ALLOW_USER_SRP_AUTH` the BFF never uses.
- **The core fix — separate "seed" from "dev-login":** the deploy needs the synthetic seed (so a Cognito login maps to a real `AppUser`) but must NOT expose dev-login.
  - `DevDataSeeder`: `@Profile("local")` → **`@Profile({"local", "demo"})`** (seeds under either).
  - `DevLoginController`: **unchanged** (`@Profile("local")`) — so it's absent under `demo`.
  - `SecurityConfig`: the `/api/v1/dev-login` `permitAll()` + CSRF `ignoringRequestMatchers` are now applied **only when the `local` profile is active** (`Environment.acceptsProfiles(Profiles.of("local"))`), keeping the security exemption in lockstep with the controller's own profile.
  - `ecs.tf`: deploy profile flipped **`local,cognito` → `demo,cognito`**. The deployed app is now **Cognito-only**; `/api/v1/dev-login` is hard-denied (403 CSRF / 401 — never a 200 session) and the controller isn't even wired.
  - **Local dev, all CLAUDE.md commands, and every existing test still use `local` unchanged** (zero breakage) — only the *deployed* profile changed.
- **ALB → CloudFront lock (`alb.tf`):** the ALB SG's `:80` ingress changed from `cidr_blocks = ["0.0.0.0/0"]` to the AWS-managed **`com.amazonaws.global.cloudfront.origin-facing`** prefix list (new `data "aws_ec2_managed_prefix_list"`). Direct plaintext HTTP from the internet to the ALB is dropped; only CloudFront (which forces HTTPS) reaches it.
- **Cognito auth flows trimmed (`cognito.tf`):** `explicit_auth_flows` reduced to just **`ALLOW_REFRESH_TOKEN_AUTH`** (dropped `ALLOW_USER_PASSWORD_AUTH` + `ALLOW_USER_SRP_AUTH`). The BFF uses only the hosted-UI authorization-code flow + refresh; `admin-set-user-password` (how the synthetic passwords are set) is unaffected.
- **Verified ($0, no AWS touched):** new **`DeployProfileNoDevLoginTest`** boots under the `demo` deploy profile and proves (a) `DevDataSeeder` is present (seed still runs), (b) `DevLoginController` is absent, (c) `POST /api/v1/dev-login` is denied (403/401, never 200, no `SESSION` cookie). Full backend `./mvnw verify` green; `terraform fmt -check` + `validate` clean. The Terraform changes land on the next `apply` (the app is torn down).
- **Honest limitations / follow-ups (documented, not built):** the ALB prefix-list still admits *any* account's CloudFront distro — a per-distribution secret origin-verify header would fully close it; RP-initiated Cognito logout; a custom domain; enforcing MFA.

### 2026-09-19 — Phase 10, slice 15 ✅ (CloudFront HTTPS — the live Cognito login completes over HTTPS)
- **Why:** Cognito rejects non-HTTPS callbacks and ACM can't cert the ALB's `*.elb.amazonaws.com` name, so put
  CloudFront (free trusted `*.cloudfront.net` cert, no domain) in front of the ALB. This is the finish line of the
  Cognito arc.
- **Done (Terraform only — NO image rebuild):**
  - `cloudfront.tf` (new) — a distribution over the ALB origin (`http-only`, port 80), `viewer_protocol_policy =
    redirect-to-https`, all HTTP methods, managed **`CachingDisabled`** + **`AllViewer`** policies (forward every
    header/cookie/query string — needed for the session + CSRF cookies and the OAuth `code`/`state`), the default
    CloudFront cert, `PriceClass_100`.
  - `cognito.tf` — added the CloudFront HTTPS callback + logout URLs to the app client.
  - `ecs.tf` — pinned the OIDC callback to the CloudFront HTTPS URL via the Spring relaxed-binding env var
    `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_COGNITO_REDIRECTURI` (overrides application-cognito.yml's `{baseUrl}`
    default with **no rebuild**). Needed because behind CloudFront→ALB the ALB hop is HTTP, so Spring would otherwise
    compute an `http://` redirect_uri that Cognito rejects.
  - `outputs.tf` — `cloudfront_url`.
- **Applied:** `terraform apply` = **2 added, 2 changed, 1 destroyed** — CloudFront created (~3 min), Cognito client
  updated, ECS rolled to task-def rev 3 (the pinned env). ALB/ECS were NOT recreated (incremental). App URL:
  **`https://d3rj9qdohmthpy.cloudfront.net`**.
- **Verified over HTTPS:** `GET /actuator/health` UP w/ db UP (CloudFront→ALB→nginx→backend→RDS, all HTTPS); once the
  new task finished rolling out, `GET /oauth2/authorization/cognito` → 302 to Cognito with
  `redirect_uri=https://d3rj9qdohmthpy.cloudfront.net/login/oauth2/code/cognito` (the pinned callback, registered in
  Cognito). In the browser: the app loads over HTTPS and **clicking "Sign in with Cognito" reaches the Cognito hosted
  login page**. The final step (typing the password → authenticated landing) is the user's — the assistant doesn't
  authenticate. **Gotcha noted:** during the rolling deploy the old task (rev 2, no pinned env) briefly served an
  `http://` redirect_uri; the https value appears once the rollout COMPLETES.
- **Cost:** CloudFront ~$0 (free tier); the app's ~$0.08–0.10/hr continues. On-demand: `terraform destroy` → ~$0.
- **Honest limitations:** logout is local-only (full Cognito RP-initiated logout is a follow-up); the ALB is still
  directly reachable on HTTP (locking it to CloudFront origins is a hardening follow-up); the deploy runs the
  `local,cognito` profile so seeded synthetic users back the Cognito logins (flipping fully to the default profile +
  real user provisioning is a later concern).
- **Cognito arc COMPLETE (slices 11–15):** pool → backend BFF → frontend button → deployed on HTTP → **live over
  HTTPS**. Real OIDC login works on the cloud URL. NO MSK.

### 2026-09-19 — Phase 10, slice 14 ✅ (redeploy the Cognito-capable app on the ALB (HTTP) + OAuth proxy, verified)
- **Why:** the deployed images were still slice-9 (pre-Cognito). To deploy real auth, rebuild+re-push both images
  and wire the backend's Cognito config + nginx's OAuth proxy — proven on HTTP first; CloudFront/HTTPS is slice 15.
- **Done:**
  - **Rebuilt + re-pushed both images** (arm64, via crane) so ECR holds the current app: backend now has the slice-12
    OAuth2 code + `application-cognito.yml` (verified in the jar); frontend has the slice-13 button + nginx proxy.
    Tags `latest` + `sha-d3b6ac9`.
  - `frontend/default.conf.template` — nginx now proxies **`/oauth2/` + `/login/oauth2/`** to the backend (was only
    `/api`+`/actuator`) — the production mirror of the Vite dev proxy.
  - `cognito.tf` — the Cognito **client secret → Secrets Manager** (`aws_secretsmanager_secret[_version].cognito_client`,
    `recovery_window_in_days=0` for clean teardown), injected into the task like the DB password (never plaintext).
  - `ecs.tf` — task env `SPRING_PROFILES_ACTIVE=local,cognito` (seeded users so a Cognito login maps + real OIDC),
    `COGNITO_CLIENT_ID`/`COGNITO_ISSUER_URI`, `SERVER_FORWARD_HEADERS_STRATEGY=framework`; injected
    `COGNITO_CLIENT_SECRET`; execution role now reads both the RDS + Cognito secrets.
- **Applied:** `terraform apply` → **7 added, 0 changed, 2 destroyed** (recreated ALB/ECS pulling the new images,
  new task-def + IAM-policy revisions, + the Cognito secret). New ALB DNS
  `http://healthcloud-dev-alb-1688045131.us-east-1.elb.amazonaws.com` (changes each apply). Task healthy ~70 s.
- **Verified (HTTP):** health UP; **`GET /oauth2/authorization/cognito` through the deployed nginx → 302 to Cognito**
  (PKCE S256) — the OAuth proxy + backend Cognito profile work in the cloud; the redirect_uri is the external ALB
  host (forwarded-headers working). dev-login + `/me` still return Dana Provider. The deployed **login page shows only
  "Sign in with Cognito"** (prod build hides dev-login). Login can't *complete* yet — the callback is HTTP and
  unregistered — that's slice 15.
- **Cost:** restarted the ~$0.08–0.10/hr meter (ALB+Fargate+RDS), from credits. On-demand: `terraform destroy` (or
  the targeted app-only destroy) returns to ~$0; Cognito + state bucket stay.
- **Next:** slice 15 — **CloudFront HTTPS** over the ALB (free trusted cert, no domain) + add the CloudFront callback
  to the Cognito client + `COGNITO_REDIRECT_URI=https://<cloudfront>/login/oauth2/code/cognito`, so the live Cognito
  login completes over HTTPS. NO MSK.

### 2026-09-19 — Phase 10, slice 13 ✅ (frontend "Sign in with Cognito" — the SPA login button, proven locally E2E)
- **Why:** give the SPA a real Cognito sign-in (the browser entry to the slice-12 BFF flow), keeping the dev-login
  for offline work. Frontend + a $0 Cognito client tweak; backend-only deploy is slice 14.
- **Done:**
  - `frontend/src/auth/LoginPage.tsx` — a primary **"Sign in with Cognito"** button rendered as a full-page link
    (`<Button component="a" href="/oauth2/authorization/cognito">`; a redirect, NOT a fetch — the response is a 302 to
    Cognito). The dev-login dropdown moved under a **"Developer sign-in (local only)"** section gated by
    `import.meta.env.DEV`, so the **production bundle shows only the Cognito button**.
  - `frontend/vite.config.ts` — proxy `/oauth2` + `/login/oauth2` → :8080 (scoped, NOT all of `/login`, which is the
    SPA's own route); `changeOrigin:false` keeps cookies first-party on `localhost`.
  - `infrastructure/terraform/cognito.tf` — added the Vite dev-server URLs to the app client
    (`http://localhost:5173/login/oauth2/code/cognito` + `http://localhost:5173/`), because through the SPA proxy the
    backend computes the callback with the :5173 host. Applied **via `-target` on the client** (in-place, **$0**;
    ALB/ECS left torn down).
  - `frontend/src/auth/LoginPage.test.tsx` (new) — Cognito link href, dev section present, axe clean.
- **Verified:** `npm run typecheck` clean · **184 tests pass** (39 files, incl. the new one) · `npm run build` OK.
  End-to-end in the browser (backend `local,cognito` + `npm run dev`): the login page shows the Cognito button + dev
  section; **clicking "Sign in with Cognito" redirected SPA → vite proxy → backend → the real Cognito hosted login
  page**, with `redirect_uri=http://localhost:5173/login/oauth2/code/cognito` (the dev callback we just registered).
  The interactive password step (→ back into the SPA authenticated) is the user's.
- **Cost $0.** No app redeploy; ALB/ECS still torn down; Cognito unchanged except the client's callback list.
- **Not in this slice:** the deployed nginx must also proxy `/oauth2` + `/login/oauth2`, and the prod HTTPS callback
  must be registered — **slice 14** (deploy with HTTPS). Full Cognito RP-initiated logout also deferred to 14.
- **Next:** slice 14 — deploy the Cognito-backed app with **HTTPS** (ACM cert + ALB HTTPS listener / CloudFront),
  add the prod callback URL, proxy `/oauth2`+`/login/oauth2` in nginx, and flip the backend off the `local` profile.
  NO MSK.

### 2026-09-19 — Phase 10, slice 12 ✅ (backend Spring Security OAuth2 BFF — real Cognito login, proven locally)
- **Why:** wire the backend to authenticate via the slice-11 Cognito pool (ADR-004 BFF), replacing the dev-login
  stand-in as the real login path. Backend only + local-first this slice; frontend (13) and HTTPS deploy (14) follow.
- **The clean bit:** `UserContextFilter` already resolves the caller by **email** (`resolveByEmail(auth.getName())`).
  Setting Cognito's `user-name-attribute: email` makes the OIDC principal name = email, so **the entire app —
  roles, tenant, the §21 gate — keeps working unchanged**; Cognito only establishes the email-keyed session, roles
  still come from the DB (rule 4). `AppUser.cognitoSub` already exists (linking it is a later refinement).
- **Done:**
  - `pom.xml` — added `spring-boot-starter-oauth2-client` (inert unless a ClientRegistration is configured, so
    builds/tests/CI without Cognito are unaffected).
  - `application-cognito.yml` (new, profile `cognito`) — the Cognito client registration: issuer-uri, client-id,
    scopes `openid email profile`, `redirect-uri={baseUrl}/login/oauth2/code/cognito`, `user-name-attribute: email`.
    **Client secret is `${COGNITO_CLIENT_SECRET}` from env (never committed)**; client-id/issuer are env-overridable
    (`COGNITO_CLIENT_ID`/`COGNITO_ISSUER_URI`) with the dev pool's values as defaults.
  - `SecurityConfig` — conditionally adds `.oauth2Login(...)` **only when a `ClientRegistrationRepository` bean
    exists** (injected via `ObjectProvider`), so offline/local/CI runs boot on dev-login alone. Permits
    `/oauth2/**` + `/login/oauth2/**`; success → `/`; wires the custom OIDC user service.
  - `CognitoOidcUserService` (new) — after Cognito authenticates, enforces an **ACTIVE `AppUser` exists for the
    email**, else rejects the login (`OAuth2AuthenticationException`) — no orphan session; generic message (no email echo).
  - dev-login stays under the `local` profile, untouched.
- **Verified:** `./mvnw -Dtest=CognitoOidcUserServiceTest test` → **3/3 green** (accepts ACTIVE user; rejects unknown
  email; rejects non-ACTIVE). Ran locally with `SPRING_PROFILES_ACTIVE=local,cognito` + `COGNITO_CLIENT_SECRET`
  against the real pool: app **boots cleanly** (OIDC discovery loaded, filter chain built); `GET
  /oauth2/authorization/cognito` → **302 to the Cognito authorize endpoint** with `response_type=code`, correct
  scopes + `redirect_uri`, and **PKCE** (`code_challenge` S256); **dev-login regression intact** (`/me` still returns
  Dana Provider / PROVIDER). The interactive login round-trip (typing the Cognito password) is a manual step —
  needs the slice-11 `admin-set-user-password` run first; the assistant doesn't authenticate with passwords.
- **Cost $0** — app code proven against the already-running free pool; no AWS changes, no deploy. Cognito + ALB/ECS
  state unchanged (app compute still torn down).
- **How to run locally with Cognito:**
  `export COGNITO_CLIENT_SECRET=$(cd infrastructure/terraform && terraform output -raw cognito_client_secret)`,
  `docker compose up -d postgres`, then `cd backend && SPRING_PROFILES_ACTIVE=local,cognito ./mvnw spring-boot:run`;
  open `http://localhost:8080/oauth2/authorization/cognito`.
- **Next:** slice 13 — frontend "Sign in with Cognito" (redirect to `/oauth2/authorization/cognito`); slice 14 —
  deploy the Cognito-backed app with **HTTPS** (ACM + ALB HTTPS listener / CloudFront) + add the prod callback URL +
  flip off the `local` profile. NO MSK.

### 2026-09-19 — Phase 10, slice 11 ✅ (Amazon Cognito user pool — real-auth infrastructure; INFRA ONLY, $0)
- **Why:** replace the local dev-login stand-in with real OIDC auth (ADR-004: Cognito + a Spring Boot BFF). This is
  a big change (infra + backend + frontend), so it's sliced: **slice 11 = the Cognito infrastructure only** (stand it
  up + prove it), with the app wiring in the following slices.
- **Done (`cognito.tf`, applied — $0):**
  - `aws_cognito_user_pool` — email as sign-in (`username_attributes=["email"]`, auto-verified), a standard password
    policy, **MFA OPTIONAL** (software-token/TOTP available, not enforced — enforcing it is a hardening follow-up),
    email account recovery, `deletion_protection=INACTIVE` (clean teardown).
  - `aws_cognito_user_pool_client` — a **confidential client** (`generate_secret=true`) for the BFF; OIDC
    **authorization-code** flow, scopes `openid`/`email`/`profile`; **local-dev** callback/logout URLs
    (`http://localhost:8080/login/oauth2/code/cognito`) — the deployed HTTPS URLs get added in the deploy-with-HTTPS
    slice (Cognito requires HTTPS for non-localhost). `prevent_user_existence_errors=ENABLED`.
  - `aws_cognito_user_pool_domain` — the free hosted login page
    `https://healthcloud-dev-927747714796.auth.us-east-1.amazoncognito.com` (account-id suffix → globally unique).
  - **2 synthetic users** matching seeded app emails — `provider@northcare.example.org` (PROVIDER),
    `admin@northcare.example.org` (ORG_ADMIN); invites suppressed; **no password in Terraform** (would land in state).
  - `outputs.tf` — `cognito_user_pool_id`, `cognito_client_id`, `cognito_client_secret` (sensitive), `cognito_issuer_url`,
    `cognito_hosted_ui_domain` (feed the backend slice).
- **Applied via `-target`** (Cognito resources only) so the torn-down ALB/ECS did **NOT** get recreated (they're still
  in config → a bare apply would want them back + restart the hourly meter). 5 Cognito resources added.
- **Verified:** pool `us-east-1_YA95ksq5k` (MFA OPTIONAL); client `3b6dmtfvsbhl2j5qofgfh0vcsv` (secret present, len 51);
  both users present (`FORCE_CHANGE_PASSWORD`); OIDC discovery reachable (authorize + token endpoints resolve); the
  **hosted login page renders over HTTPS** in the browser ("Sign in with your email and password").
- **Post-apply manual step (keeps passwords out of state):** set a permanent synthetic password per user via
  `aws cognito-idp admin-set-user-password --user-pool-id us-east-1_YA95ksq5k --username <email> --password 'Demo-Passw0rd!' --permanent`.
- **Cost $0** (Cognito free tier ≤ 50k MAU) — **safe to leave up** between slices (no hourly meter, like VPC/RDS/ECR).
  App compute (ALB/Fargate) remains torn down.
- **Next:** slice 12 — backend **Spring Security OAuth2 BFF** wiring (map a Cognito login → app user/org/roles),
  **proven locally** against this pool; keep the dev-login for pure-offline dev. Then frontend (slice 13), then deploy
  with HTTPS (slice 14). NO MSK.

### 2026-09-19 — Phase 10, slice 10 ✅ (ECS Fargate + ALB — the app is LIVE on AWS)
- **Why:** turn the pushed images into a running, reachable app. First slice with an ongoing hourly charge, so
  on-demand: apply → capture evidence → `destroy`.
- **Shape (chosen for cost + image reuse):** ONE Fargate task holds BOTH containers (backend + frontend nginx),
  talking over `localhost` — one task = one compute charge, and the images run unchanged (only env vars). Because
  awsvpc containers share a network namespace and nginx is hard-wired to 8080, the **backend runs on 8081**
  (`SERVER_PORT`) and nginx proxies `/api`+`/actuator` to `http://localhost:8081` (`BACKEND_UPSTREAM`). ALB → frontend :8080.
- **Done:**
  - `alb.tf` — ALB SG (:80 from internet), **app SG** (:8080 from the ALB SG only), the ALB (public subnets), a
    target group (:8080, health check `/`, target_type `ip`), and the HTTP :80 listener.
  - `ecs.tf` — ECS cluster; a CloudWatch log group (`/ecs/healthcloud-dev`, 7-day retention); an **execution role**
    (managed `AmazonECSTaskExecutionRolePolicy` for ECR pull + logs, + an inline `secretsmanager:GetSecretValue` on
    the RDS secret) and an empty **task role**; the **ARM64** task definition (1 vCPU / 2 GB, the two containers) —
    the DB password is **injected from Secrets Manager** by the ECS agent (`secrets` → `HEALTHCLOUD_DB_PASSWORD`/
    `_USER` from the managed secret's JSON keys, never in state); and the **service** (desired 1, 180 s health grace).
  - Backend env: `SPRING_PROFILES_ACTIVE=local` (dev-login + synthetic seed so the URL is demoable before Cognito),
    `SPRING_DATASOURCE_URL` from the RDS endpoint, Kafka relay + consumers **off** (no MSK on AWS).
  - `rds.tf` — **tightened**: Postgres 5432 now reachable **only from the app SG** (was VPC-wide) — an in-place SG
    update (kept the SG-level `description` byte-identical so it doesn't force a replacement of a live SG).
  - `outputs.tf` — `app_url` (the ALB DNS name).
- **Applied (Gate 2, go-ahead given):** `terraform apply` → **13 added, 1 changed (RDS SG in-place), 0 destroyed**.
  Task healthy in ~60 s (image pull + Flyway migrate + seed against fresh RDS).
- **Verified through the live ALB URL** (`http://healthcloud-dev-alb-604958717.us-east-1.elb.amazonaws.com`):
  `/actuator/health` = **UP** with `db` UP (PostgreSQL = RDS — proves ALB → nginx → backend → RDS); the SPA is served
  (`<title>HealthCloud</title>`); `POST /api/v1/dev-login` → 200 and `/me` returns *Dana Provider / NorthCare / PROVIDER*
  (session cookie flows over plain HTTP — no `Secure`-flag problem); `GET /api/v1/patients` returns seeded synthetic
  data, **relationship-gated** (Dana sees only her 2 assigned patients) and **consent-masked** (`dateOfBirth: null`,
  `maskedFields:["dateOfBirth"]`). Browser: the login page and the authenticated **Patients** page (DOB shows
  "Restricted") both render at the ALB URL.
- **Cost:** ~$0.08–0.10/hr all-in (ALB + Fargate ARM 1 vCPU/2 GB + a few public IPv4s + the running RDS), from
  credits — card untouched. **Left running so the user can capture evidence; must `terraform destroy` to return to ~$0**
  (the state bucket is kept). `destroy` needs an explicit go-ahead per the AWS boundary rule.
- **HTTP only this slice** — HTTPS (ACM cert + a domain) belongs with the CloudFront/custom-domain/Cognito slices.
- **Next:** capture evidence → **`terraform destroy`** (on go-ahead). Then Cognito (real auth, flip off the `local`
  profile) → CloudFront/HTTPS. **NO MSK.**

### 2026-09-19 — Phase 10, slice 9 ✅ COMPLETE (ECR registries + both images pushed via crane; Docker fixed)
- **Why:** ECS Fargate pulls from ECR natively via IAM (simpler than GHCR creds), so the app images need an AWS
  registry copy before the ECS slice.
- **Done (`infrastructure/terraform/ecr.tf`, applied):** 2 ECR repositories — `healthcloud-dev-backend`,
  `healthcloud-dev-frontend` — with `scan_on_push`, a lifecycle policy keeping only the last 5 images, and
  `force_delete = true` (clean teardown). Output `ecr_repository_urls`
  (`927747714796.dkr.ecr.us-east-1.amazonaws.com/healthcloud-dev-{backend,frontend}`). 4 resources applied, **$0**.
- **NOT done — the image push:** the plan was to build locally (arm64) and `docker push` to ECR. The backend
  image is **823 MB** and `docker push` **kept timing out** (`net/http: timeout awaiting response headers`) — Docker
  splits upload bandwidth across parallel layers, so the biggest layer never completed within the HTTP timeout on a
  home uplink. Attempted fix: `max-concurrent-uploads: 1` in `~/.docker/daemon.json` + Docker Desktop restart — but
  **Docker Desktop stripped the setting** on restart, and worse, the quick quit+reopen caused a **`Docker.raw is held
  by another process / disk image in use`** VM lock that Docker Desktop got wedged on (VM won't start; kill-and-reopen
  cycles didn't clear it). Terminal cleanup confirmed no process holds `Docker.raw`, but the app stays wedged →
  a **Mac restart** is the reliable fix.
- **Decision pending (two paths, discussed with the user):**
  1. **Fix local Docker** (Mac restart, or Docker Desktop → Troubleshoot → Clean/Purge data) then push with **`crane`**
     (go-containerregistry — retries/streams rather than timing out, robust to slow uplinks). Keeps the fast local loop.
  2. **Pivot to CI → ECR via GitHub OIDC** (recommended): add an OIDC provider + a repo-scoped IAM role (ECR-push only)
     in Terraform, and extend the CI image jobs to push to ECR. No local Docker, fast runner network, professional
     pattern; images become `linux/amd64` → the ECS slice would run **x86_64** (not the arm64 originally planned).
     Trade-off: slower iterate→redeploy loop (commit→push→CI) and OIDC trust must be scoped tightly.
- **Committing this partial slice** so nothing is lost before the Mac restart: `ecr.tf` + `outputs.tf` (the repos are
  real in AWS), with docs marking slice 9 as in-progress. **Local Docker is currently broken** (pending Mac restart);
  it is **not needed** for the CI path.
- **Everything through slice 8 is committed + pushed + CI-green.** Terraform state is safe in S3; the ECR repos exist.
- **RESOLUTION (same day):** the user restarted the Mac, which cleared the stale `Docker.raw` VM lock — Docker
  came back healthy (verified: `docker version` server 29.7.2, `docker run hello-world` OK). Then we pushed with
  **`crane`** instead of `docker push`: `brew install crane`, `aws ecr get-login-password | crane auth login AWS`,
  `docker save <img> -o img.tar`, `crane push img.tar <ecr-ref>`, `crane tag … sha-c1e42ab`. The backend tarball is
  **257 MB compressed** (vs 823 MB uncompressed) and pushed in **~2 min**; the frontend (21 MB) in seconds. **Both
  repos now hold `sha-c1e42ab` + `latest`.** Verified `crane config` → both images are **`linux/arm64`** (built
  natively on Apple Silicon) → **the ECS Fargate slice must set `cpu_architecture = "ARM64"`**. **Slice 9 COMPLETE.**
- **Takeaway:** on a slow home uplink, push large images with **`crane`** (or from CI), never `docker push` —
  crane retries/streams and doesn't hit the per-layer HTTP timeout that `docker push`'s parallel uploads do.
- **Next:** slice 10 — **ECS Fargate + ALB** (run the containers behind a load balancer → a live URL), task role
  reads the RDS secret from Secrets Manager, ARM64 platform. Then Cognito → CloudFront. **NO MSK.**

### 2026-09-18 — Phase 10, slice 8 ✅ (RDS PostgreSQL — the managed database; private, encrypted, Secrets Manager)
- **Why:** the app needs a database. First real "server" resource — managed Postgres in the private subnets built
  in slice 7.
- **What shipped (`infrastructure/terraform/rds.tf`):** a **DB subnet group** across the 2 private subnets; a
  **security group** allowing Postgres **5432 from the VPC CIDR only** (tighten to the Fargate app SG in the ECS
  slice); and an **`aws_db_instance`** — **Postgres 17** (`engine_version = "17"` prefix → 17.9), **`db.t4g.micro`**,
  **20 GB gp3**, **`storage_encrypted`**, **`publicly_accessible = false`**, single-AZ. **Master password managed in
  Secrets Manager** (`manage_master_user_password = true`) — never in code or state; the app will read it via IAM.
  Demo/teardown settings: `backup_retention_period = 0`, `skip_final_snapshot = true`, `deletion_protection = false`.
- **Two-gate boundary honoured:** Gate 1 = `fmt`/`validate`/`plan` ($0, nothing created), shown; Gate 2 = explicit
  "go ahead and apply it" before `terraform apply` (creating RDS took a few minutes).
- **Verified:** `apply` created 3 resources; `terraform plan` → "No changes"; `aws rds describe-db-instances` →
  `available`, `postgres 17.9`, `db.t4g.micro`, **publicly_accessible = false**, **encrypted = true**, multi_az = false.
  Endpoint `healthcloud-dev-postgres.cyvye82887pw.us-east-1.rds.amazonaws.com:5432`, db `healthcloud`.
- **Cost:** `db.t4g.micro` + 20 GB is **free-tier eligible** (~$0), plus ~**$0.40/mo** for the Secrets Manager
  secret — from the $100 credits. On-demand: `destroy` when not demoing.
- **Outputs added:** `db_address`, `db_port`, `db_name`, `db_master_secret_arn` (the ECS Fargate slice consumes these).
- **Next:** ECR (push the backend/frontend images to AWS) → ECS Fargate + ALB (run the app, live URL) → Cognito →
  CloudFront. **NO MSK.**

### 2026-09-18 — Phase 10, slice 7 ✅ (VPC & networking — the network the app runs in; 13 free resources, $0)
- **Why:** with state safely in S3, start the real infrastructure. The VPC is the foundation every later
  resource (RDS, ECS Fargate, ALB) sits in.
- **Cost-conscious topology (the key design):** **public subnets** host the ALB + Fargate (tasks get a public
  IP and reach the internet directly), **private subnets** host RDS (no outbound internet needed). This lets us
  **omit the NAT Gateway** — the single biggest saving (~$32/mo) vs a textbook VPC.
- **What shipped (`infrastructure/terraform/network.tf`):** a VPC (`10.0.0.0/16`, `enable_dns_hostnames` for
  RDS/ECR DNS) + an internet gateway + **2 public subnets** (`10.0.0.0/24`, `10.0.1.0/24`,
  `map_public_ip_on_launch`) + **2 private subnets** (`10.0.10.0/24`, `10.0.11.0/24`) across 2 AZs
  (us-east-1a/1b — RDS requires 2) + a public route table (`0.0.0.0/0` → IGW) + a **local-only** private route
  table (no internet route → confirms no NAT) + associations. Written as **raw resources** (not a VPC module)
  so the pieces are visible. Outputs: `vpc_id`, `public_subnet_ids`, `private_subnet_ids` (consumed by later slices).
- **Two-gate boundary honoured:** Gate 1 = `fmt`/`validate`/`plan` ($0, nothing created), shown to the user;
  Gate 2 = explicit "go ahead and apply it" before `terraform apply`.
- **Verified:** `apply` created **13 resources**; `terraform plan` → "No changes"; `aws ec2 describe-vpcs`/
  `describe-subnets` confirm the VPC `vpc-0f5ae5185f6331cdb` + 2 public (MapPublicIp=true) + 2 private subnets;
  `describe-nat-gateways` → **none** (no NAT charges). `fmt -check` OK. **Cost ~$0** (VPC/subnets/IGW/route
  tables are all free; no public IPs allocated yet).
- **Next:** RDS (managed Postgres in the private subnets) → ECR (push the images) → ECS Fargate + ALB (the live
  app) → Cognito → CloudFront. Billable pieces start at the ALB/Fargate slices (drawn from credits, removed by
  `destroy`). **NO MSK.**

### 2026-09-18 — Phase 10, slice 6 ✅ (Terraform remote-state backend — S3; the FIRST applied AWS resource)
- **Why:** before defining any real infrastructure, give Terraform a durable, safe home for its state (instead of
  a loose file on the laptop). Standard professional practice; do it once, now, while there's nothing to lose.
- **AWS onboarding (done this session, all by the user):** created AWS account `927747714796` (`Nikhil.O`), region
  **us-east-1**, on the new **Free Plan** (**$100 credits**, +$100 earnable over 6 mo; expires 18 Mar 2027).
  Key nuance: *usage above the free tier draws from the credit balance* and the card is **not** charged while
  credits cover it — so we likely **won't need the paid plan** for this project. Guardrails set: a **$5 budget
  alarm** (`HealthCloudProject-monthly`) + AWS auto-enabled **Cost Anomaly Detection**. Created a dedicated IAM
  user **`healthcloud-terraform`** (AdministratorAccess) with access keys; installed **AWS CLI v2 via the official
  pkg** (NOT Homebrew — the brew awscli had a pyexpat/expat symbol breakage) and `aws configure`d it (verified via
  `aws sts get-caller-identity`).
- **What shipped:** a small **`infrastructure/terraform/bootstrap/`** config (its own **local** state — it must
  exist before the remote backend it creates, the chicken-and-egg) that creates **one S3 bucket**
  `healthcloud-tfstate-927747714796` with **versioning + AES256 encryption + all public access blocked**. Then the
  main config's **`backend.tf`** was switched to a real `backend "s3"` block (key `healthcloud/dev/terraform.tfstate`,
  `encrypt = true`, **`use_lockfile = true` → S3-native locking, NO DynamoDB** on TF ≥1.10 — one fewer resource,
  cheaper than the old plan), and `terraform init -migrate-state` moved state to S3.
- **Two-gate boundary honoured:** Gate 1 = wrote files + `terraform plan` ($0, nothing created), shown to the user;
  Gate 2 = explicit "go ahead and apply it" before `terraform apply` created the bucket. New-account activation
  delay hit first (`NotSignedUp: not signed up for the S3 service`) — waited for AWS to email "account is now
  active", then the apply succeeded.
- **Verified:** `terraform apply` created 4 resources; state file confirmed in S3 (`aws s3 ls` →
  `healthcloud/dev/terraform.tfstate`, 802 B); `terraform plan` clean (0 real resources — main config still declares
  none, only outputs); `terraform fmt -check -recursive` OK. **Cost ~$0** (empty bucket + kilobyte state).
- **The state bucket is KEPT** — it is not part of the deploy → `destroy` cycle (it must outlive the resources
  whose state it stores). Everything else stays synthetic.
- **Next:** real infra slices — VPC/networking → RDS → ECR → ECS Fargate → the live app. **NO MSK** (event-driven is
  proven locally; managed Kafka is too costly). On-demand: `apply` → capture evidence → `terraform destroy`.

### 2026-09-17 — Phase 10, slice 5 ✅ (Terraform skeleton — IaC foundation; no resources, no AWS account, $0)
- **Why:** both deployable images now publish to GHCR. Before defining any real AWS infrastructure, lay the
  Terraform foundation — version pins, provider, and naming/tagging conventions every later resource hangs off —
  **without declaring any resources**, so there is nothing to `apply`, no AWS account needed, and no cost.
- **What (all in `infrastructure/terraform/`):** `versions.tf` (Terraform ≥1.9 + AWS provider `~> 6.0`),
  `providers.tf` (the `aws` provider with **`default_tags`** = Project/Environment/ManagedBy, so tagging is a
  convention not a chore), `variables.tf` (`aws_region`/`project`/`environment`, defaulted + validated),
  `locals.tf` (`name_prefix` + `common_tags`), `outputs.tf` (echo the conventions), `backend.tf` (documents that
  we use the **default local backend** now — state on disk, git-ignored — and switch to an **S3 + DynamoDB**
  remote backend, bootstrapped, in the deploy slice), `terraform.tfvars.example`, and a `README.md` with an
  explicit cost/account note. Removed the placeholder `.gitkeep`.
- **Cost/AWS posture (per the request to flag any AWS entry):** this slice contacted **no AWS** — no credentials
  are configured on the machine, and the config declares **zero resources**. `terraform init` downloads the
  provider from the **Terraform registry** (HashiCorp), not AWS. The **first** step that needs an AWS account and
  incurs cost is a *future* `apply` slice; it will be announced and gated, never a side effect here.
- **Verified locally (installed Terraform 1.16.3 via Homebrew — a free local tool, no password, not AWS):**
  `terraform fmt -check -recursive` clean; `terraform init -backend=false` ok (wrote `.terraform.lock.hcl`, which
  is **committed** — it pins the provider to the resolved **6.65.0**); `terraform validate` → "Success! The
  configuration is valid."; `terraform plan` (no credentials) → only "Changes to Outputs", **no resources** — proof
  it provisions nothing and needs no account. The `.terraform/` dir + any `*.tfstate` are git-ignored (already in
  the root `.gitignore`); the lock file is not ignored and is committed.
- **Honest scope:** no real resources (VPC/ECS/RDS/S3/CloudFront/Cognito/MSK), no live remote state yet (local
  backend now), no CI Terraform step yet (a `fmt`/`validate` check is a candidate later slice). All future data
  stays synthetic.
- **Files:** `infrastructure/terraform/{versions,providers,variables,locals,outputs,backend}.tf`,
  `terraform.tfvars.example`, `README.md`, `.terraform.lock.hcl` (all new); removed `.gitkeep`; `CLAUDE.md`,
  `docs/PROGRESS.md`.
- **⚠️ Next step involves AWS:** the natural slice 6 is the **remote-state bootstrap** (create the S3 state bucket +
  DynamoDB lock table) — the **first slice that requires your AWS account and incurs (small) cost**. I will lay it
  out and get your explicit go-ahead before anything is applied. A zero-cost alternative first: add a
  `terraform fmt`/`validate` check to CI.

### 2026-09-17 — Phase 10, slice 4 ✅ (build + publish the frontend image in CI → GHCR)
- **Why:** slice 2 wired the backend image into CI; slice 3 gave the frontend a container image but only built it
  on demand locally. This does for the frontend what slice 2 did for the backend, so **both** deployable artifacts
  are built, tested, and published automatically — a cloud deploy can pull a matched pair (backend + frontend at
  the same `sha-…`). Still free (GHCR + `GITHUB_TOKEN`), no AWS.
- **New `frontend-image` job in `.github/workflows/ci.yml`** (mirrors `backend-image`; the other three jobs
  unchanged): `needs: frontend` (only a tested image publishes); builds on every change; on a PR builds but does
  **not** push; on push to `main` builds **and** pushes to **GHCR** (`ghcr.io/nikhil-oggu/healthcloud-frontend`),
  tags `sha-<short>` + `latest` via `docker/metadata-action`; auth via the automatic `GITHUB_TOKEN`
  (`packages: write`); `linux/amd64`, context `./frontend`.
- **Also:** gave each image build a **distinct GHA cache scope** (`scope=backend` / `scope=frontend`) so the two
  builds don't evict each other's layers (they'd otherwise share one scope and thrash).
- **Kept two explicit image jobs** rather than a build matrix — clearer, and each stays gated on exactly its own
  test job. A matrix is a possible future DRY refactor (noted, not done).
- **Verified:** the workflow YAML parses and the wiring is right (jobs: backend, frontend, backend-image,
  frontend-image; `frontend-image` `needs: frontend`, `packages: write`); `docker build ./frontend` is already
  green from slice 3. **Full verification is the Actions run** — after the push, watch it and confirm the frontend
  image published + the `sha-…`/`latest` tags appear in GHCR.
- **Honest scope:** no AWS, no deploy, no cost — just building + storing the image in GitHub's free registry. The
  new GHCR package may start **private** (make it public once in the repo's Packages settings, optional).
- **Files:** `.github/workflows/ci.yml`, `CLAUDE.md`, `docs/PROGRESS.md`.
- **Next candidates:** the **Terraform skeleton** (provider + remote state config, no resources applied — still
  zero-cost until we deliberately `apply`); then, when you're ready to spend, the first real AWS resources.

### 2026-09-17 — Phase 10, slice 3 ✅ (containerize the frontend — a static nginx image, verified locally)
- **Why:** slices 1–2 gave the backend a container image + CI publish. The frontend is the other half of the
  deployable app and today only runs via `npm run dev`. For any cloud host it must be built to static files and
  served — so, mirroring slice 1, we make it a container image (local, free) that ECS Fargate can run.
- **The design point — how the SPA reaches the API in production:** in dev, Vite proxies `/api`+`/actuator` to
  :8080 **same-origin** (keeps the SESSION + XSRF-TOKEN cookies first-party → no CORS, no token in JS). The image
  preserves that exactly: nginx serves the SPA **and reverse-proxies `/api`+`/actuator` to the backend**.
- **`frontend/Dockerfile` (multi-stage):** stage 1 = `node:24-alpine` → `npm ci` (lockfile) → `npm run build`
  (`tsc --noEmit && vite build` → `dist/`). Stage 2 = **`nginxinc/nginx-unprivileged:1.27-alpine`** (non-root,
  uid 101, listens on 8080 — matching the backend image's non-root posture): copies `dist/` + the config
  template; `HEALTHCHECK` via busybox `wget` on `/` (independent of the backend).
- **`frontend/default.conf.template`:** `listen 8080`; `location /api/` + `/actuator/` `proxy_pass
  ${BACKEND_UPSTREAM}` (filled in at container start by the base image's envsubst — so the same image points at
  `http://backend:8080` in compose and the backend's DNS in ECS, no rebuild); `location /` `try_files $uri
  $uri/ /index.html` (SPA deep-link fallback for React Router).
- **`frontend/.dockerignore`:** excludes `node_modules`, `dist`, `coverage`, etc. — small build context.
- **`docker-compose.yml`:** an opt-in **`frontend`** service behind the **`full` profile**, `build: ./frontend`,
  `depends_on: backend`, `BACKEND_UPSTREAM=http://backend:8080`, host **:8081** → container :8080. The whole app
  now runs as containers: **frontend → backend → postgres**.
- **Verified locally:** `docker build ./frontend` succeeds; **image size 77.2 MB** (measured). With the full stack
  up: `curl :8081/` returns the SPA (`<title>HealthCloud</title>`, `<div id="root">`); `curl :8081/actuator/health`
  → `status: UP` (db UP) — proves the reverse proxy; `curl :8081/api/v1/me` → 401 `UNAUTHENTICATED` with a
  correlationId — proves the `/api` proxy reaches the backend; `curl :8081/claims/abc` → HTTP 200 + `index.html`
  — proves the SPA deep-link fallback; `docker exec … whoami` → `nginx` (non-root). Torn down after.
- **Honest scope:** local-only — **no AWS, no cost**; **no CI publish of the frontend image yet** (that's the
  natural slice 4, mirroring backend slice 2); **no new unit tests** (infra — the Vitest suite already runs in CI;
  proof is the build + curl checks). Alternative not taken yet: hosting the static build on **S3/CloudFront**
  instead of an nginx container — a valid production path for the AWS phase; the nginx image is the
  container-consistent, locally-verifiable choice now and keeps the same-origin API proxy trivially. Known
  follow-up: the bundle is a single ~940 kB (270 kB gzipped) chunk — Vite suggests code-splitting.
- **Files:** `frontend/Dockerfile` (new), `frontend/default.conf.template` (new), `frontend/.dockerignore` (new),
  `frontend/README.md` (new), `docker-compose.yml`, `CLAUDE.md`, `docs/PROGRESS.md`.
- **Next candidates:** publish the frontend image in CI to GHCR (slice 4, mirrors backend slice 2); then the
  Terraform skeleton (provider + remote state, nothing applied) — still zero-cost until we deliberately `apply`.

### 2026-09-17 — Fix: flaky adjudication tests that were failing CI (test-only, unblocks the image publish)
- **Found while verifying slice 2:** the `backend` CI job had been red since before Phase 10 (the docs-only push
  `567c4dc`, no code change, failed identically) — so `backend-image` (which `needs: backend`) never published.
  Two tests in `AdjudicationAccumulatorApiIntegrationTest` failed **in CI but passed locally**.
- **Root cause (test pollution + non-deterministic ordering, not a product bug):** these tests assert exact dollar
  amounts using the **seeded "Standard PPO"** but located it by regex as *"the first plan whose type is PPO"* from
  the tenant's plan list. The list is ordered by **plan code ascending**, and other adjudication tests create their
  own PPO-type plans (e.g. `AdjudicationFeeScheduleApiIntegrationTest` → "Adjudication Fee Schedule Plan", code
  `AFS-…`, which prices 99213 to $120). `AFS-…` sorts before the seeded `…-PPO-STD`, so once it exists the
  accumulator test grabbed the wrong plan and its deductible math broke. Local runs order the test classes
  differently, hiding it.
- **Fix (test-only):** select the seeded plan by its **unique plan code (`…-PPO-STD`)** instead of "first PPO" —
  order-independent, the way `CoveragePlanApiIntegrationTest` already does it. Applied to
  `AdjudicationAccumulatorApiIntegrationTest`; also hardened `AdjudicationApiIntegrationTest` (its `firstPlanId`
  helper grabbed the first plan of any type → renamed `seededPpoPlanId`, same code match) since it relied on the
  same luck. The other adjudication tests create their own uniquely-coded plans, so they're already isolated. No
  production code changed.
- **Verified:** full backend `./mvnw -B clean verify` green locally — **492 tests, 0 failures** (the suite runs all
  classes against one shared DB, so it reproduces the CI conditions; the fix holds regardless of order).
- **Files:** `backend/src/test/java/com/healthcloud/adjudication/AdjudicationAccumulatorApiIntegrationTest.java`,
  `AdjudicationApiIntegrationTest.java`.

### 2026-09-17 — Phase 10, slice 2 ✅ (build + publish the backend image in CI → GHCR)
- **Why:** slice 1 proved the image builds on my Mac. A CI/CD pipeline needs it to build on a clean machine on
  every change and to produce a **versioned, pullable artifact** a deploy can grab — exactly what AWS ECS Fargate
  will pull. The phase is "cloud deployment & **CI/CD**," so this is the natural next step, and it stays free.
- **New `backend-image` job in `.github/workflows/ci.yml`** (`backend` and `frontend` jobs unchanged):
  - **`needs: backend`** — never publishes an image whose tests didn't pass.
  - **On a PR:** builds the image (proves the Dockerfile still builds), does **not** push.
  - **On push to `main`:** builds **and** pushes to **GHCR** (`ghcr.io/nikhil-oggu/healthcloud-backend`).
  - **Tags** via `docker/metadata-action`: `sha-<short>` (immutable — what a deploy pins to) + `latest`
    (`enable={{is_default_branch}}`). metadata-action lowercases the image name (GHCR requires it).
  - **Auth:** the automatic `GITHUB_TOKEN` with job-scoped `packages: write` — **no secrets to configure**.
  - **`linux/amd64`** (ECS Fargate default; arm64/multi-arch is a documented follow-up). Buildx **GHA layer cache**
    (`cache-from/to: type=gha`) reuses the slow Maven-dependency layer across runs.
- **Honest tradeoff:** the code compiles twice per main run — once in `backend` (tests) and once in the image build.
  That's the price of keeping the `Dockerfile` self-contained (so `docker build ./backend` still works locally);
  the layer cache softens it. Feeding a pre-built jar into a single-stage Dockerfile would couple the image to CI
  and lose the local-build property — not worth it.
- **Verified:** the workflow YAML parses and the job wiring is correct (jobs: backend, frontend, backend-image;
  `needs: backend`; `packages: write`); `docker build ./backend` remains green from slice 1 (Dockerfile unchanged).
  **Full verification is the Actions run itself** — a CI slice can only be fully proven once it runs on GitHub, so
  after the push I watch the run and confirm the image published + the `sha-…`/`latest` tags appear in GHCR.
- **Honest scope:** no AWS, no deploy, no cost — just building + storing the image in GitHub's free registry. The
  GHCR package may start **private** (make it public once in the repo's Packages settings, optional). Not the
  frontend image, not Terraform, not an actual deployment.
- **Files:** `.github/workflows/ci.yml`, `CLAUDE.md`, `backend/README.md`, `docs/PROGRESS.md`.
- **Next candidates:** a frontend static image (nginx) or an S3/CloudFront plan; then the Terraform skeleton
  (provider + remote state, nothing applied) — still zero-cost until we deliberately `apply`.

### 2026-09-17 — Phase 10, slice 1 ✅ (containerize the backend — a production container image, verified locally)
- **Why:** Phase 10 is cloud deployment (Terraform, ECS Fargate, RDS, S3/CloudFront, Cognito, MSK). Two constraints
  shape the first slice: (1) no AWS account yet + the phase is deliberately cost-aware/on-demand, and (2) ECS Fargate
  runs **container images**, not JARs — and the repo had **no Dockerfile anywhere** (the app only ran via `mvnw`).
  So the true first dependency is a production-grade image of the backend: 100% local, 100% free, fully verifiable
  with Docker. Everything AWS later just *runs this image*.
- **`backend/Dockerfile` (multi-stage):** stage 1 = full JDK 25 (Temurin), copies `mvnw`/`.mvn`/`pom.xml` first and
  runs `dependency:go-offline` (so deps cache in a layer and source-only changes rebuild fast), then packages the jar
  with **`-DskipTests`** — the suite uses Testcontainers (a real Postgres via Docker), which doesn't belong in an
  image build, and tests already gate in CI. Stage 2 = slim **JRE** (smaller + smaller attack surface): adds `curl`
  for the healthcheck, creates a **non-root `spring` user**, copies just the jar, `EXPOSE 8080`, a
  `HEALTHCHECK` on `/actuator/health` (the same signal AWS's load balancer will use), and a `JAVA_OPTS` env hook.
  `exec java … -jar` so the JVM is PID 1 and receives stop signals.
- **`backend/.dockerignore`:** excludes `target/`, `var/` (local document bytes — never ship local data), `.git`,
  IDE files — keeps the build context small and safe.
- **`docker-compose.yml`:** an opt-in **`backend`** service behind the **`full` profile** (so everyday
  `docker compose up -d postgres kafka` is completely unchanged), `build: ./backend`, `depends_on` postgres
  **healthy**, talking to the `postgres` container over the compose network
  (`jdbc:postgresql://postgres:5432/healthcloud`). Runs with the **default** Spring profile (production shape —
  Flyway migrates, but **no demo seed / dev-login**; add `SPRING_PROFILES_ACTIVE: local` to seed). Outbox relay
  **disabled** here (`HEALTHCLOUD_OUTBOX_RELAY_ENABLED=false`) — cross-container Kafka networking is a later slice,
  and the app makes no broker connection at startup anyway.
- **Verified locally:** `docker build -t healthcloud-backend:local ./backend` succeeds (packages
  `healthcloud-0.0.1-SNAPSHOT.jar`); **image size 823 MB** (measured — a layered-jar / smaller-base optimization is a
  documented follow-up). `docker compose --profile full up -d postgres backend` → `curl localhost:8080/actuator/health`
  returns `status: UP` with `db: UP` (connected to the Postgres container); logs show `Tomcat started on port 8080`
  and `Started HealthcloudApplication` as PID 1; `docker exec … whoami` → `spring` (non-root). Torn down after.
- **Honest scope:** local-only — **no AWS, no Terraform, no cost** this slice; **no new unit tests** (infra — its
  proof is the reproducible build + the live health probe). Not the frontend image, not CI image-publish to a
  registry (a natural slice 2), not Kafka-in-a-container.
- **Files:** `backend/Dockerfile` (new), `backend/.dockerignore` (new), `docker-compose.yml`, `backend/README.md`,
  `CLAUDE.md`, `docs/PROGRESS.md`.
- **Next candidates:** build + publish the image in CI to a registry (GHCR); a frontend static image (nginx) or
  S3/CloudFront plan; then the Terraform skeleton (provider + remote state, no resources applied) — still zero-cost
  until we deliberately `apply`.

### 2026-09-17 — Phase 9, slice 10 ✅ (WCAG 2.2 AA-aligned accessibility pass — axe gate + shell + headings, frontend)
- **Why:** the last Phase 9 area — accessibility. Rather than a one-off cleanup, this establishes an **automated
  gate** the suite keeps enforcing, then fixes the cross-cutting shell + heading issues that every page inherits.
- **Honest framing (rules 2–3):** I don't claim "WCAG 2.2 AA certified". This is **AA-aligned**: automated axe
  A/AA checks + the keyboard/landmark/heading criteria + an in-browser contrast check on the core screens. A tool
  can't certify; a full page-by-page audit + a real-browser Playwright+axe gate are documented follow-ups.
- **Foundation:** added `axe-core` (devDep) + `src/test/axe.ts` → **`expectNoAxeViolations(container)`**, which runs
  axe with the `wcag2a/2aa/21a/21aa/22aa` tags and fails with a readable violation list. **color-contrast is
  disabled** in the helper — axe reads computed pixels via canvas, which jsdom doesn't implement, so it can't run
  under jsdom (it throws canvas errors); contrast is verified in the browser instead.
- **App shell (`AppLayout`, one file, every page benefits):** a **Skip to main content** link (the first focusable
  element, visually hidden until `:focus`, `href="#main"`) — WCAG 2.4.1 Bypass Blocks; the nav wrapped in
  **`<nav aria-label="Primary">`**; the `<main>` given `id="main" tabIndex={-1}` as the skip target; and the brand
  "HealthCloud" changed from `variant="h6"` (an `<h6>`) to `component="div"` so it no longer competes as a heading.
- **Headings:** new shared **`components/PageHeading.tsx`** (`variant="h5" component="h1"`, keeps the visual size)
  adopted as the single top-level title on **all 26 pages** (a Node codemod for the 20 simple inline titles + the
  import; 6 special cases — `sx`/`gutterBottom`/a nested span — done by hand). Now every route has exactly one
  `<h1>` and a correct heading order; card/section subheadings keep their lower levels. (`NotFoundPage` lost its
  last `Typography` use → dropped the unused import.)
- **Tests:** `src/test/accessibility.test.tsx` — a shell test asserting the skip link (`href="#main"`), the named
  Primary nav landmark, and exactly one `<h1>` (+ axe), and axe on `NotFoundPage`/`DeniedPage`. +3 tests.
- **In-browser verification** (backend up, logged in as `admin@northcare.example.org`): the accessibility tree
  shows `link "Skip to main content" → #main`, `navigation "Primary"`, `main`, brand as plain text (not a heading),
  and a single `heading level 1`; pressing Tab reveals the skip link top-left; the default MUI theme's contrast
  (white nav on primary-blue, dark `<h1>` on white) reads fine on the core screens.
- **Verified:** frontend `typecheck` + **181 tests** (was 178, +3) + `build` all green. Backend untouched.
- **WCAG 2.2 note:** 2.4.1 addressed (skip link); 2.4.11 Focus Not Obscured is fine (the AppBar is
  `position="static"`, so it never covers focused content); target-size (2.5.8) + a full contrast/focus sweep across
  all pages are part of the documented follow-up.
- **Files:** `frontend/`: `test/axe.ts` (new), `test/accessibility.test.tsx` (new), `components/PageHeading.tsx`
  (new), `layout/AppLayout.tsx`, + 26 page components (title → `PageHeading`), `package.json`/lock (axe-core);
  CLAUDE.md.
- **Phase 9 COMPLETE ✅** (pagination + free-text search + CSV export + accessibility). Next phase: 10 (AWS deploy
  & CI/CD) — a big, on-demand phase; or a Playwright+axe E2E accessibility gate as an optional slice first.

### 2026-09-17 — Phase 9, slice 9 ✅ (CSV export with masking — the reporting piece, backend + UI)
- **Why:** Phase 9 is "search / **reporting** / accessibility." This adds the reporting piece — download a list as
  CSV — and it's the interesting one because **field masking (§23) has to hold on the export path too**, not just
  the JSON reads. If a caller sees a patient's DOB as "Restricted" on screen, the CSV they download must have a
  **blank** DOB cell — never the real value.
- **The key idea:** the export **calls the same service read the JSON API uses** (`PatientService.list...`), not a
  second finder that reads raw columns. So it inherits *everything* — tenant scope, the relationship gate (a
  provider exports only their assigned patients), and consent masking. A masked field is already `null` in the DTO
  the read returns, so it serializes as an empty cell. §23.3 in one sentence: the backend is the only trusted
  masker, so don't build an unmasked back-door read for export.
- **Target = the patient list**, because the patient read is the *one* place §23 masking actually bites
  (`dateOfBirth`). The eight work queues are PHI-free by design (business numbers, no masked fields), so exporting
  them is pure formatting — the reusable helper is ready for that in a later slice, but the masking-critical target
  goes first.
- **New reusable foundation — `com.healthcloud.common.Csv`** (pure/DB-free, like `SearchTerms`/`PageRequests`):
  `field(raw)` RFC 4180-quotes a value with a comma/quote/line-break (doubling embedded quotes) and **defuses a
  leading formula trigger** (`= + - @ \t \r` → prefixed with `'`) so opening the file in Excel/Sheets can't execute
  a **CSV-injection** formula (the one free-text column is a patient name); `row(cells)` joins fielded cells with
  commas + a `\r\n` terminator. 9 unit tests.
- **Backend:** `PatientService.exportCsvForCurrentTenant()` builds the CSV from the already-masked DTO list;
  `GET /api/v1/patients/export.csv` (`produces="text/csv"`, `Content-Disposition: attachment; filename=patients.csv`)
  streams it. Columns are PHI-minimal (Patient ID · MRN · Full name · Date of birth · Status). The literal
  `export.csv` path is matched ahead of `/{id}` by Spring.
- **Frontend:** an **Export CSV** button on the patients page — reuses the `downloadBlob` client helper + the
  object-URL save pattern from document download (saves `patients.csv`); an `exporting` state + an error alert.
  Shown to any viewer (the backend scopes + masks the file per caller).
- **Proof (tests):** `CsvTest` (9) covers quoting/escaping/formula-defusing/`\r\n`. Two API tests: (1) the export
  respects masking — a patient with a consent GRANT exports a real DOB while a no-consent patient's DOB is a blank
  cell (CSV mirrors JSON exactly); (2) a **provider's export excludes an unassigned patient's MRN** (the
  relationship gate carries to export). Plus a frontend test that the button triggers `api.exportPatientsCsv`.
- **Verified:** backend `./mvnw -B clean verify` → **492 tests, BUILD SUCCESS** (was 481, +11). Frontend
  `typecheck` + **178 tests** (was 177, +1) + `build` all green.
- **Honest limitation:** the whole accessible list is built in one response (no streaming/pagination) — fine at
  synthetic scale; streaming is a later refinement.
- **Remaining Phase 9 area:** WCAG 2.2 AA accessibility.
- **Files:** `backend/.../common/Csv.java` (new) + `CsvTest.java` (new); `patient/PatientService.java`,
  `patient/PatientController.java`; `patient/PatientFieldMaskingApiIntegrationTest.java` (+2 tests);
  `frontend/src/api/client.ts`, `frontend/src/patients/PatientsPage.tsx` + `PatientsPage.test.tsx`; CLAUDE.md.

### 2026-09-17 — Phase 9, slice 8 ✅ (free-text search on audit + dead-letters — search now covers all eight queues)
- **Why:** slices 6–7 gave the six *numbered* queues a search box. This finishes search by covering the two that
  have no business number — the audit trail and the dead-letter queue — so **every work queue is now searchable**.
- **What each searches (all PHI-free identifiers):** audit matches **`correlationId` or `resourceId`** — trace a
  request across events, or find every event about one resource; dead-letters match **`eventId` or `messageKey`**
  — find a specific failed event, or all dead letters for one aggregate (e.g. a claim id).
- **The new wrinkle — UUID columns.** `resourceId` and `eventId` are `UUID`, not `String`, so the `@Query` casts
  them to text before the `like`: `lower(cast(e.resourceId as string)) like lower(cast(:q as string)) escape '\'`.
  (The param `cast(:q as string)` is the same `bytea`-avoidance from slice 6.) So a partial-id paste matches.
- **Backend:** audit's `searchRecent` gains the OR clause (the exact `?resourceType=&resourceId=` history path is
  unchanged — `q` applies only to the recent list); dead-letters' derived `findByOrganizationId(org, pageable)` was
  replaced by a `searchAll(org, q, pageable)` `@Query` (keeping `findByIdAndOrganizationId` for replay + the
  drainer's idempotency finder). Both services take `q` (via `SearchTerms.likeContains`), both controllers add a
  `q` param; the role gates (AUDITOR/ORG_ADMIN; ORG_ADMIN) are untouched.
- **Frontend:** a debounced (300ms) search box on `AuditEventsPage` (beside the action filter) and
  `DeadLetterEventsPage`; `api.listAuditEvents`/`listDeadLetterEvents` + their hooks gained `q`.
- **Verification:** backend **481** tests green (`clean verify`; +2 API — audit search by resourceId, dead-letter
  search by eventId; the first build failed on a missing `assertFalse` import in the dead-letter test — fixed).
  Frontend **177** tests + typecheck + build green (+2 search-box tests). Note: `PatientDetailPage.test.tsx` (a
  heavy, untouched test) intermittently times out under full-suite parallel load but passes 18/18 in isolation —
  pre-existing flakiness, unrelated to this slice.
- **Next:** Phase 9 is down to its last two areas — **CSV export with masking**, then **WCAG 2.2 AA**.

### 2026-09-17 — Phase 9, slice 7 ✅ (free-text search rolled out to the five other numbered queues — backend + UI)
- **Why:** slice 6 gave the claims queue a search box and built the reusable `SearchTerms` foundation. This rolls
  that same search out to the other five queues that have a synthetic, PHI-free business number, so **six of the
  eight work queues** now have a search box.
- **Queues + what each searches:** prior-auth (`authNumber`), referrals (`referralNumber`), appeals
  (`appealNumber`), claim-reviews (`reviewNumber`), reprocessing (`batchNumber`). Each is a coded identifier, not
  PHI — we never search patient names.
- **Backend (per queue, purely additive):** each `@Query` finder (`searchAll`/`searchForPatients`, plus
  `searchForClaim` for appeals + claim-reviews) gains the same clause as claims —
  `(:q is null or lower(<number>) like lower(cast(:q as string)) escape '\')`, keeping the `cast(:q as string)`
  that avoids the Postgres `bytea` inference bug. Each service `list(...)` takes an `Optional<String> q`
  (normalized with `SearchTerms.likeContains`) and each controller a `@RequestParam q`. **No new migrations, no
  DTO changes, no `SearchTerms` change** — it reuses slice 6 as-is. Authorization (patient gate / role gate /
  tenant scope / empty-set short-circuit) is untouched.
- **Frontend (per queue):** the same debounced (300ms) search box beside the status filter, resetting to page 0;
  `api.listXxx` params + the page hook's params gained `q`.
- **Verification:** backend **479** tests green (`clean verify`; +5 — a repo search test on prior-auth/referral/
  appeal/claim-review + an end-to-end `?q=` API test on reprocessing); frontend **175** tests + typecheck + build
  green (+5 — a debounced-search interaction test per queue). One frontend test needed its per-test mocks set
  (the reprocessing page test has no shared default mock) — fixed.
- **Next:** Phase 9 slice 8 — search for the two non-numbered queues (audit by correlationId/resourceId,
  dead-letters by eventId), then CSV export with masking, then WCAG 2.2 AA.

### 2026-09-17 — Phase 9, slice 6 ✅ (free-text search on the claims queue — backend + UI)
- **Why:** every work queue is paginated now (slices 1–5). The first Phase 9 area beyond pagination is **search** —
  a real queue needs a "type part of a number and jump to it" box. This adds it to the claims queue and lays the
  reusable foundation the other queues will adopt, exactly as slice 1 did with pagination.
- **What we search, and why it's PHI-safe:** a case-insensitive **"contains" match on the claim number** (`CLM-…`,
  a synthetic, coded identifier). We deliberately do **not** search patient names — that would drag PHI into the
  query string (rule 5 + the privacy rule against sensitive data in URLs) and require cross-table joins.
- **New reusable helper — `com.healthcloud.common.SearchTerms`** (pure, DB-free, like `PageRequests`):
  `likeContains(raw)` trims; blank/whitespace → `null` ("no filter", so an empty box lists everything); else a
  `%…%` pattern with the SQL `LIKE` wildcards `\ % _` **escaped** (a user typing `50%` searches for a percent
  sign, not half the table). Unit-tested in `SearchTermsTest` (4 tests).
- **Backend claims:** `GET /api/v1/claims` gains an optional `q` param → `ClaimService.list(..., Optional<String>
  q, ...)` (normalized via `SearchTerms`) → the `searchAll`/`searchForPatients` `@Query` finders gain one more
  in-SQL clause: `(:q is null or lower(c.claimNumber) like lower(cast(:q as string)) escape '\')`. Filtering runs
  in the database, not in memory. **All the §21 authorization is unchanged** — a claim is still gated by its
  patient (provider → assigned; broad roles → the tenant's queue; cross-tenant → secure 404; empty accessible set
  short-circuits to an empty page) — search is just one more optional filter on the same paths.
- **The bug the tests caught (worth remembering):** the first build **failed** — `function lower(bytea) does not
  exist`. When a nullable `String` parameter is used in a JPQL query, Postgres/Hibernate infers its type as
  `bytea`, so `lower(:q)` blows up at runtime with a 500. The fix is an explicit **`cast(:q as string)`**, which
  pins the parameter to text. This is why we run the real DB via Testcontainers — a mock would have passed. All
  eight failures traced to this one cause; the cast fixed every one.
- **Frontend claims:** a **debounced** search box (300ms — a `setTimeout` in a `useEffect` on the raw value, so we
  query once typing settles, not per keystroke) on `ClaimsPage`, beside the status filter; typing resets to page 0.
  `ClaimsPageParams` + `api.listClaimsPage` gained `q`; the non-paged `listClaims()` shim is untouched.
- **Verification:** backend **474** tests green (`clean verify`; +6 — `SearchTermsTest` 4, a repo search test, an
  API search test); frontend **170** tests + typecheck + build green (+1 interaction test).
- **Next:** Phase 9 slice 7 — roll free-text search out to the other queues (prior-auth/referrals/appeals/reviews/
  reprocessing/audit/dead-letters, each by its own business number, reusing `SearchTerms`), then CSV export with
  masking, then WCAG 2.2 AA.

### 2026-09-17 — Phase 9, slice 5 ✅ (paged reprocessing + audit + dead-letter queues — backend + UI)
- **Why:** slices 1–4 paginated the five patient-gated queues. This does the three **non-patient-gated** queues —
  tenant-scoped, role-gated, no patient set — completing the pagination rollout across **every** work queue.
- **Backend (per queue):** a paged finder — `searchAll(org, <filter>, pageable)` `@Query` for reprocessing (status)
  and audit (action), a paged `searchForResource` for audit's `?resourceType=&resourceId=` history, and a derived
  `findByOrganizationId(pageable)` for dead-letters. Each service `list(...)` takes a `Pageable` and returns
  `PageResponse<...Dto>`; each controller adds `page`/`size`/`sort` (allowlists: reprocessing `{createdAt,
  batchNumber, status}`, audit `{occurredAt, sequenceNo}`, dead-letter `{createdAt, sourceTopic}`).
  - **Audit** got the biggest win: the old view was **capped at 200 rows** with a **client-side** action filter over
    just those. Now it's real pagination + a **server-side `action` filter**; the tamper-evidence chain-verify
    finder (`findByOrganizationIdOrderBySequenceNoAsc`) is deliberately left untouched.
  - **Reprocessing** got a status filter (RUNNING/COMPLETED/COMPLETED_WITH_ERRORS); the `planName` lookup now runs
    only for the page's rows.
- **Frontend (direct conversion, no shim — each list is consumed only by its page):** `api.listReprocessingBatches`
  / `listAuditEvents` / `listDeadLetterEvents` return the `PageResponse` envelope; hooks paged with
  `keepPreviousData`; pages gained MUI `TablePagination` + `TableSortLabel` (+ reprocessing status dropdown, + audit
  action dropdown). The audit action dropdown, previously stale (2 of 6 actions), now lists all six, and the
  `AuditAction` TS type was widened to match. The dead-letter Replay flow and the audit Verify button are unchanged.
- **Verify:** backend `./mvnw -B clean verify` green — **468 tests** (was 462): +2 each to the reprocessing, audit
  and dead-letter API tests (page envelope + counts, 400 on unknown sort, the new status/action filter running in
  SQL). The existing **role-gate 403** (a reviewer/provider/patient can't read audit; a non-admin can't read dead
  letters) and **tenant-scoping** tests still pass through the paged path. Frontend `typecheck` + `npm test` **169**
  green (was 166; +3 interaction — a combined sort/page/filter test per queue) + `build` clean.
- **Next:** with every queue paginated, the remaining Phase 9 areas are **free-text search**, **CSV export with
  masking**, and the **WCAG 2.2 AA** accessibility pass.

### 2026-09-17 — Phase 9, slice 4 ✅ (paged referrals + appeals + claim-reviews queues — backend + UI)
- **Why:** slices 1–3 built and proved the pagination pattern on claims + prior-auth. This rolls it out to the last
  three **patient-gated** work queues in one slice (mechanical, each independently tested), completing that family.
- **Backend (per queue):** paged `@Query` finders returning `Page<T>` — `searchAll` + `searchForPatients` for all
  three, plus **`searchForClaim`** for appeals + claim-reviews (their list filters by `?claimId=`, not patientId).
  Each service `list(...)` takes a `Pageable` and returns `PageResponse<...SummaryDto>` with **identical
  authorization** (patient gate §21 layer 6; the `claimId` branch loads-and-gates the claim by its patient; empty
  gated set → `PageResponse.empty`; the in-memory status `.filter` is gone). Each controller adds `page`/`size`/
  `sort` with an allowlist (referral `{createdAt, referralNumber, specialty, reasonCode, status}`, appeal
  `{createdAt, appealNumber, status}`, claim-review `{createdAt, reviewNumber, status}`), default `createdAt DESC`.
  Removed the now-dead unpaged finders (grep-checked; the anomaly detector's claim finder was the only cross-package
  user of that name family, in the claim package — untouched here).
- **Frontend (direct conversion, no shim):** each list hook is consumed only by its own queue page, so
  `api.listReferrals` / `listAppeals` / `listClaimReviews` were changed directly to return the `PageResponse`
  envelope; `useReferrals`/`useAppeals`/`useClaimReviews(params)` are paged with `keepPreviousData`; each page gained
  MUI `TablePagination` + `TableSortLabel` (on the server-sortable columns) + a status-filter dropdown. Patient and
  Claim # remain client-resolved (not sortable). Kept create forms, name resolution, empty states.
- **Verify:** backend `./mvnw -B clean verify` green — **462 tests** (was 449): extended `ReferralRepositoryTest`
  (+2) and `AppealRepositoryTest` (+2, incl. `searchForClaim`), new `ClaimReviewRepositoryTest` (+2), + API tests
  (referral +4 envelope/paging/sort/status via `?patientId=`; appeal +2 and claim-review +2 envelope + bad-sort —
  their `?claimId=`/broad counts are polluted by the seeder and by the one-open-per-claim guard, so deterministic
  paging is asserted at the repo level). The existing provider-scoping + cross-tenant secure-404 tests still pass
  through the paged path. Frontend `typecheck` + `npm test` **166** green (was 157; +9 interaction tests, 3 per
  queue) + `build` clean.
- **Gotcha:** mid-build hit the documented stray-`target/`-`" 2"`-class failure (a duplicated
  `TestcontainersConfiguration 2.class` crashed the surefire fork) — fixed with `clean`, per the version-control note.
- **Next:** Phase 9 slice 5 — the **non-patient-gated** queues (reprocessing, audit, dead-letters — tenant-scoped,
  role-gated, simpler list shape), then free-text search / CSV export with masking / WCAG 2.2 AA.

### 2026-09-17 — Phase 9, slice 3 ✅ (paged prior-authorizations queue — pagination + sort + status filter, backend + UI)
- **Why:** slice 1+2 built and proved the pattern on claims. This rolls it out to the prior-authorizations queue —
  the closest structural twin — proving the reusable `common` foundation generalizes to a second aggregate. One
  full-stack slice (backend + UI) because the work is now mechanical.
- **Backend:** `PriorAuthorizationRepository` gains two `@Query` finders returning `Page<PriorAuthorization>` —
  `searchAll` (broad role) and `searchForPatients` (gated/single-patient), each with an optional in-SQL status
  filter (mirrors the claims finders). `PriorAuthorizationService.list(...)` takes a `Pageable` and returns
  `PageResponse<PriorAuthorizationSummaryDto>` with **identical authorization** (patient gate §21 layer 6; empty
  gated set → `PageResponse.empty`; the in-memory status `.filter` is gone). `PriorAuthorizationController.list`
  adds `page`/`size`/`sort` (allowlist `{createdAt, authNumber, procedureCode, requestedServiceFrom, status}`,
  default `createdAt DESC`). Removed the three now-dead unpaged finders; **kept `existsApprovedCovering`** (the
  adjudication hook) untouched.
- **Frontend (direct conversion, no shim):** unlike claims, **nothing but the queue page** consumes this list, so
  `api.listPriorAuthorizations(params)` was changed directly to return the `PageResponse` envelope (no array shim
  needed). `usePriorAuthorizations(params)` is now paged with `keepPreviousData` (query key carries the params;
  still prefixed `['prior-authorizations']` so a create/decision invalidates it). `PriorAuthorizationsPage` gained
  MUI `TablePagination` + `TableSortLabel` on the server-sortable columns (Auth # / Procedure / Requested from /
  Status — Patient stays client-resolved) + a status-filter dropdown. Kept the create form, patient-name
  resolution, and empty state.
- **Verify:** backend `./mvnw -B verify` green — **449 tests** (was 443): extended `PriorAuthorizationRepositoryTest`
  (+2 paged `searchAll`/`searchForPatients` — page/count/sort/status + tenant scoping) and
  `PriorAuthorizationApiIntegrationTest` (+4 — page envelope + counts, size/page navigation, 400 on unknown sort,
  status filter + service-from sort order; the existing provider-scoping + cross-tenant secure-404 tests still pass
  through the paged path). Frontend `typecheck` + `npm test` **157** green (was 154 — the 3 kept page intents now
  assert the default page/size/sort call, + 3 new interaction tests) + `build` clean. Live browser check skipped
  (no backend running; RTL drives the components against the mocked API).
- **Next:** Phase 9 slice 4 — continue the rollout to the remaining queues (referrals, appeals, reviews,
  reprocessing, audit, dead-letters), then free-text search / CSV export with masking / WCAG 2.2 AA.

### 2026-09-17 — Phase 9, slice 2 ✅ (paged claims work-queue UI — pagination + sortable columns + status filter, frontend)
- **Why:** slice 1 made the endpoint server-paginated and returned a `PageResponse` envelope, but the browser still
  used the transitional shim that swallowed it. This puts real page controls, sortable columns, and a status filter
  on the claims queue. Frontend-only — the backend already supports every param.
- **New paged path (kept the array shim):** `api.listClaimsPage({patientId,status,page,size,sort})` returns the full
  `PageResponse<ClaimSummary>`; `useClaimsPage(params)` in `useClaims.ts` (query key `['claims','page',params]` so a
  page/sort/filter change refetches, and still prefixed `['claims']` so a create invalidates it; `keepPreviousData`
  keeps rows on screen while the next page loads). **`api.listClaims()` / `useClaims()` (array) were left untouched**
  — their 9 consumers (appeals/reviews/reprocessing name resolution + `<select>`s) need the whole list, not a page.
- **`ClaimsPage.tsx` rewrite:** MUI `TablePagination` (`component="div"`, rows-per-page 10/20/50, `count` from
  `totalElements`), `TableSortLabel` on the four server-sortable columns (Claim # `claimNumber`, Service date
  `serviceDate`, Total charge `totalChargeAmount`, Status `status`) toggling asc/desc — Patient is not sortable
  (resolved client-side, not in the backend allowlist), and a **status** `TextField select` (All + the 6 statuses).
  Any sort/size/filter change resets to page 0; no active sort by default → the backend's `createdAt DESC` newest-first
  is preserved. Kept the role-gated create form, patient-name resolution, empty state, and the exported `money()`
  (still imported by 3 other pages).
- **Verify:** `npm run typecheck` clean, `npm test` **154** green (was 151 — the 3 kept ClaimsPage intents now assert
  the default page/size/sort call, plus 3 new interaction tests: a header click sorts `serviceDate,asc` then toggles
  `,desc`; the pager's next button requests `page:1`; the status dropdown filters `status:'SUBMITTED'`), `npm run build`
  succeeds. Live browser check skipped (no backend running; RTL drives the components against the mocked API, as with
  prior frontend slices).
- **Next:** Phase 9 slice 3 — roll the pagination/sort/filter pattern out to the other work queues (prior-auth,
  referrals, appeals, reviews, reprocessing, audit, dead-letters), then free-text search / CSV export / WCAG 2.2 AA.

### 2026-09-17 — Phase 9, slice 1 ✅ (server-side pagination + filtering for the claims work queue, backend)
- **Why:** every work queue currently returns its whole list in one unbounded response, and the claims list even
  filters status **in memory**. Phase 9 is search/reporting/accessibility — so this slice builds the **reusable
  pagination foundation** on the busiest queue (claims), which the other queues will adopt mechanically later.
- **New `com.healthcloud.common` package (the foundation):**
  - **`PageResponse<T>`** — a stable page envelope (`content`, `page`, `size`, `totalElements`, `totalPages`,
    `first`, `last`) + `of(Page<E>, mapper)` and `empty(pageable)`. We return this, not Spring Data's `PageImpl`,
    because Spring's serialized page JSON is explicitly unstable across versions — the API shape is ours to own.
  - **`PageRequests.toPageable(page, size, sort, allowedSortFields, defaultSort)`** — a pure, DB-free helper that
    **clamps** `size` to `[1,100]` and `page` to `≥0`, parses `"field,dir"` (dir defaults to asc, case-insensitive),
    and **allowlists** the sort field: an unknown field or a bad direction is a clean `400 VALIDATION_FAILED` (not a
    `PropertyReferenceException` 500), which also blocks ordering by — and probing — an arbitrary column.
- **Claims queue, now DB-paged:** `ClaimRepository` gains two `@Query` finders returning `Page<Claim>` — `searchAll`
  (broad-role, optional status) and `searchForPatients` (the gated/single-patient cases, optional status). Status
  now filters in SQL; the in-memory `.filter` is deleted. `ClaimService.list(...)` takes a `Pageable` and returns
  `PageResponse<ClaimSummaryDto>` with **identical authorization** (patient gate §21 layer 6; an empty gated set
  short-circuits to an empty page). `ClaimController.list(...)` adds `page`/`size`/`sort` (allowlist
  `{createdAt, serviceDate, totalChargeAmount, status, claimNumber}`, default `createdAt DESC`). Removed the two
  now-dead unpaged finders (kept the one the anomaly detector still uses).
- **Frontend compat (zero consumer churn):** `api.listClaims()` requests one large page (`size=200`) and returns
  `.content`, so `useClaims()` and every consumer keep receiving `ClaimSummary[]` unchanged. Added a `PageResponse<T>`
  type. The real page-control UI (page buttons, sort headers) is the next slice — noted as an honest transitional shim.
- **Verify:** backend `./mvnw -B clean verify` green — **443 tests** (was 430): new `PageRequestsTest` (7 cases —
  clamping, default sort, `field,dir` parse, 400 on unknown field/bad direction), extended `ClaimRepositoryTest`
  (+2 — `searchAll`/`searchForPatients` page/count/sort/status + tenant scoping), extended `ClaimApiIntegrationTest`
  (+4 — page envelope + counts, size/page navigation, 400 on unknown sort, status filter + service-date sort order;
  the existing provider-scoping + cross-tenant secure-404 tests still pass through the paged path). Frontend
  `typecheck` + `npm test` (**151** green) + `build` all clean — proving the shim kept the UI intact. Live browser
  check skipped (no backend running; standing up the full Docker stack would only re-confirm what the real-server
  integration tests already prove end-to-end).
- **Next:** Phase 9 slice 2 — the paged **UI** (page controls + sortable columns) on the claims queue, then roll the
  `common` foundation out to the other work queues.

### 2026-09-16 — Phase 7, slice 7 ✅ (access-review UI — live grants + Revoke, frontend)
- **Why:** slice 6 built the break-glass oversight backend. This puts it in front of an admin/auditor. Frontend-only.
- **New `AccessReviewPage.tsx`** (route `/access-review`) in `src/breakglass/`: a table of every live grant in the
  tenant (`useAllBreakGlassGrants` → `GET /api/v1/break-glass/all`) — Provider (resolved name, id in a tooltip) ·
  Patient (id → link to `/patients/:id`) · Reason · Granted · Expires · Action. A **Revoke** button (with an inline
  **Confirm/Cancel**, matching the prior-auth inline-pending idiom — the codebase uses no `window.confirm`) is shown
  **only to ORG_ADMIN** (`useRevokeBreakGlass` → `POST /api/v1/break-glass/{id}/revoke`, invalidates the list so the
  grant drops out); an auditor sees the list read-only.
- **Wiring:** `api.listAllBreakGlass`/`api.revokeBreakGlass` + `BreakGlassGrantAdmin` type; hooks
  `useAllBreakGlassGrants`/`useRevokeBreakGlass`; the `/access-review` route; an **Access review** nav button gated to
  AUDITOR/ORG_ADMIN.
- **Verify:** `npm run typecheck` clean (hit + fixed the documented MUI 9 `Stack` `justifyContent`-in-`sx` gotcha),
  `npm test` green (145 → **149**; new `AccessReviewPage.test.tsx`, 4 cases — lists grants with provider name + patient
  link; an admin revokes via the inline confirm (calls `revokeBreakGlass('g1')`); a read-only auditor sees no Revoke
  button; empty state), `npm run build` succeeds. Live browser check skipped (in-app browser can't reach localhost);
  RTL exercises the components against the mocked API.
- **Next:** Phase 7 — **retention** (data lifecycle / purge policy), the final Phase 7 area.

### 2026-09-16 — Phase 7, slice 6 ✅ (access review of break-glass — oversight + early revocation, backend)
- **Why:** slice 4 left two gaps (flagged then): admins had no visibility of who holds emergency access, and a grant
  could only expire, never be ended early. This slice closes both — the first "access review" capability, on the most
  sensitive access. Backend-only.
- **Migration `V39__break_glass_revocation.sql`:** add `revoked_at` + `revoked_by` to `break_glass_grant`. A grant is
  now **live** only when `expires_at > now AND revoked_at IS NULL`.
- **Entity/repo:** `BreakGlassGrant` gains `revoke(by)` + `isLive()` (revocation is the one allowed mutation). The
  guard's finders gain `…AndRevokedAtIsNull` (so a revoked grant grants nothing immediately); new org-wide live-grants
  finder + a tenant-scoped `findByIdAndOrganizationId`.
- **Guard (`PatientAccessGuard`):** its two break-glass calls now use the revoked-excluding variants — an admin's
  revocation cuts off access at once.
- **`BreakGlassService`:** `listActiveForTenant()` (**AUDITOR/ORG_ADMIN**) returns every live grant in the tenant as a
  `BreakGlassGrantAdminDto` with the **provider name resolved** (`AppUserRepository`); `revoke(grantId)`
  (**ORG_ADMIN** only — an auditor is read-only) loads by `(id, org)` (cross-tenant → secure 404), 409s if the grant
  isn't live, else stamps `revoked_at`/`revoked_by` + writes a `BREAK_GLASS_REVOKED` audit event in **one tx**.
  `listMine()` also switched to the live-only finder.
- **Audit:** new `AuditAction.BREAK_GLASS_REVOKED` (PHI-free detail: the grant id).
- **Endpoints:** `GET /api/v1/break-glass/all` (oversight) + `POST /api/v1/break-glass/{id}/revoke` (revoke).
- **Verify:** backend `./mvnw -B clean verify` green (411 → **416** tests; new `BreakGlassReviewApiIntegrationTest`, 5
  cases — admin sees + revokes (provider loses access immediately, grant drops off the list, `BREAK_GLASS_REVOKED`
  audited); list gated to auditor/admin (provider/coordinator 403); auditor cannot revoke (403); re-revoke 409;
  cross-tenant revoke 404).
- **Scope note:** the access-review capability is scoped to **break-glass** here (the sensitive, gap-closing part). It
  will grow to cover standing provider/coordinator assignments and role memberships in later slices.
- **Next:** Phase 7 — the access-review UI (an admin/auditor page of all live grants + a Revoke button), then retention.

### 2026-09-16 — Phase 7, slice 5 ✅ (break-glass UI — emergency-access panel + my-grants page, frontend)
- **Why:** slice 4 built the break-glass backend. This puts it in front of the provider. Frontend-only.
- **The design subtlety:** a provider breaks glass to reach a patient they *can't see* — so there's no patient page
  to click on. The entry point is the **denied state itself**: navigating to `/patients/:id` for an unassigned
  patient returns a secure 404, and right there a PROVIDER is offered break-glass (the id is already in the URL).
- **New `src/breakglass/`:** `useBreakGlass.ts` (`useMyBreakGlassGrants` query + `useBreakGlass(patientId)` mutation
  that on success invalidates `patientKey(id)` + the patients list + the grants list, so the denied page reloads with
  access); **`BreakGlassPanel.tsx`** (a reason form + "Break glass" button, RHF+Zod, `patientId` prop); and
  **`MyBreakGlassPage.tsx`** (route `/break-glass` — a table of the caller's live grants, each linking to the now-
  reachable patient).
- **Patient-detail integration:** in `PatientDetailPage`'s error branch, a **404** for a **PROVIDER** renders
  `<BreakGlassPanel>` instead of the generic `ErrorScreen` (using `ApiClientError.status`); everything else still
  shows the error screen.
- **Wiring:** `api.breakGlass`/`api.listBreakGlass` + `BreakGlassGrant`/`CreateBreakGlassRequest` types; the
  `/break-glass` route; an **Emergency access** nav button gated to PROVIDER.
- **Honest limitations:** the entry point is the patient's URL/id (realistic when the provider already has the
  patient's link/id; a "search by MRN/name then break glass" flow would need a backend MRN lookup that bypasses the
  gate — not built); this page shows only the caller's own grants (admin/auditor oversight of *all* grants is the
  access-reviews slice); role-gating is convenience, the backend is the boundary.
- **Verify:** `npm run typecheck` clean, `npm test` green (139 → **145**; new `BreakGlassPanel.test.tsx` (2),
  `MyBreakGlassPage.test.tsx` (2), and two added `PatientDetailPage` cases — a PROVIDER 404 shows the panel, a
  non-provider 404 shows the error), `npm run build` succeeds. Live browser check skipped (in-app browser can't reach
  localhost here); RTL exercises the components against the mocked API.
- **Next:** Phase 7 — access reviews (admin/auditor oversight of break-glass grants + assignments), then retention.

### 2026-09-16 — Phase 7, slice 4 ✅ (break-glass emergency access — time-boxed override + audit, backend)
- **Why:** the classic HIPAA "break the glass". A PROVIDER who is not assigned to a patient can, in an emergency,
  **self-grant time-boxed access** by recording a justification — made safe by being fully audited and reviewable.
  It overrides ONLY the object/relationship gate (§21 layer 6), never tenant isolation. Backend-only.
- **Migration `V38__break_glass_grant.sql`:** a tenant-owned, immutable `break_glass_grant` (`app_user_id` provider,
  `patient_id`, `reason`, `created_at`, `expires_at`); composite FK `(patient_id, organization_id)` → `patient`
  (§32.10); index `(organization_id, app_user_id, expires_at)` for the active-grant lookup.
- **New `com.healthcloud.breakglass` package:** `BreakGlassGrant` entity, org-scoped `BreakGlassGrantRepository`
  (a per-patient `existsBy…ExpiresAtAfter` check + an active-grants list), `BreakGlassGrantDto`,
  `CreateBreakGlassRequest` (`@NotNull patientId`, `@NotBlank reason`), and **`BreakGlassService`**:
  `create(...)` is **PROVIDER**-gated, loads the patient **directly** by tenant (NOT via the guard — the whole point
  is reaching a patient the guard would 404 on; cross-tenant/unknown is still a secure 404), and writes the grant +
  a `BREAK_GLASS_INVOKED` audit event in **one transaction**; `listMine()` returns the caller's live grants.
- **Guard integration (`PatientAccessGuard`):** a provider-gated caller with no assignment is now also allowed if a
  **live break-glass grant** exists for `(caller, patient)`; and `accessiblePatientIdsIfGated` unions assigned +
  break-glass patient ids, so break-glass patients appear in list reads. The guard gains a
  `BreakGlassGrantRepository` dep (repository-only → no bean cycle). Because everything patient-gated routes through
  the guard, break-glass reaches the patient's whole record (requests, consent, documents, claims), not just the
  profile.
- **Endpoints:** `POST /api/v1/break-glass` (self-grant) + `GET /api/v1/break-glass` (my live grants).
- **Audit:** new `AuditAction.BREAK_GLASS_INVOKED` + `AuditService.RESOURCE_PATIENT`. The audit **detail is PHI-free**
  — it names the grant id + expiry, **not** the free-text reason (the reason stays on the grant row for review).
- **Config:** `healthcloud.break-glass.grant-duration-minutes` (default 60).
- **Verify:** backend `./mvnw -B clean verify` green (406 → **411** tests; new `BreakGlassApiIntegrationTest`, 5 cases
  — unassigned provider 404 → break-glass → 200 + listed + one `BREAK_GLASS_INVOKED` event with a PHI-free detail; an
  **expired** grant (inserted via `JdbcTemplate`) grants nothing; blank reason 400; cross-tenant patient 404; a
  non-provider (coordinator) 403).
- **Honest limitations:** self-service (no approval step — intentional for emergencies); the grant is audited at
  invocation, not on every subsequent read under it; no early admin revocation yet (grants expire on their own).
- **Next:** Phase 7 — the break-glass UI, then access reviews (admin/auditor oversight of all grants) and retention.

### 2026-09-16 — Phase 7, slice 3 ✅ (audit-trail UI — log viewer + Verify integrity button, frontend)
- **Why:** slices 1–2 built the backend audit trail + tamper-evidence. This slice puts it in front of a human so an
  auditor/admin can browse the trail and verify its integrity with one click. Frontend-only — no backend change.
- **New `src/audit/` feature folder** (the established pattern): `useAudit.ts` (`useAuditEvents` list query +
  `useVerifyAuditChain` mutation over the verify GET), `statusColor.ts` (`auditOutcomeColor`/`auditActionColor`
  chip colours), and **`AuditEventsPage.tsx`** (route `/audit`).
- **The page:** a heading + a **Verify integrity** button that calls `GET /api/v1/audit-events/verify` and shows the
  verdict as a banner — green *"Chain intact — N entries verified"* or red *"Tampering detected at sequence X — <reason>"*
  (the tangible payoff of slices 1–2). A table of recent events (newest first): When · Seq · Action (chip) · Resource
  (type + short id) · Outcome (chip) · Actor (short id, full in tooltip) · Detail · **Fingerprint** (truncated
  `entryHash`, full in tooltip — so the hash chain is *visible*). A small client-side **Action** filter dropdown over
  the loaded page.
- **Wiring:** `api.listAuditEvents` + `api.verifyAuditChain` + `AuditEvent`/`AuditChainVerification`/`AuditAction`/
  `AuditOutcome` types; the `/audit` route in `App.tsx`; an **Audit** nav button in `AppLayout` gated to
  AUDITOR/ORG_ADMIN (surfacing the previously-invisible AUDITOR role in the UI).
- **Honest limitations:** the Actor column shows the user id (no auditor-accessible user directory to resolve names
  yet — a later enhancement); role-gating in the nav/route is convenience, the backend is the boundary; verification
  is UI over the slice-2 endpoint (no crypto in the browser).
- **Verify:** `npm run typecheck` clean, `npm test` green (135 → **139**; new `AuditEventsPage.test.tsx`, 4 cases —
  lists events with action/detail/fingerprint, intact banner on a valid verdict, tampering banner on a broken
  verdict, action filter narrows the rows), `npm run build` succeeds. Live browser check skipped (the in-app browser
  can't reach localhost here); RTL exercises the components against the mocked API.
- **Next:** Phase 7 — break-glass emergency access (or access reviews / retention).

### 2026-09-16 — Phase 7, slice 2 ✅ (tamper-evident audit chain — per-org HMAC hash chain + verify, backend)
- **Why:** slice 1's audit trail was honest but unprotected — someone with DB access could edit or delete a row and
  nothing would show it. This slice makes it **tamper-evident**: each event becomes a link in a hash chain, so any
  edit, deletion, reorder, insertion or truncation of `audit_event` can be **detected**. This is the flagship of
  Phase 7.
- **How the chain works (plain terms):** each event gets a fingerprint `entry_hash = HMAC-SHA256(orgKey,
  canonical(event) + prev_hash)` — its own contents *plus the previous event's fingerprint*, like a blockchain.
  Change any old row and its fingerprint no longer matches, and because later rows chained off it, the break
  cascades. We use **HMAC** with a secret key, so an attacker can't just recompute a valid fingerprint after
  tampering.
- **Per-org key, master secret OUT of the DB:** each org's chain uses key = `HMAC(masterSecret, orgId)`; the
  **master secret lives in configuration** (`healthcloud.audit.hmac-secret`, env-overridable dev default — Phase-10
  KMS/HSM later), **never in the database it protects**. So tampering with `audit_event` alone can't forge a valid
  fingerprint. (`AuditSigningKeys` derives the per-org key.)
- **Migration `V37__audit_event_chain.sql`:** adds `sequence_no` (per-org monotonic), `prev_hash`, `entry_hash`
  (VARCHAR(64)) to `audit_event` + `UNIQUE(organization_id, sequence_no)`; new `audit_chain_head` (per-org tip:
  `last_hash` + `next_sequence`). Assumes a fresh/reset DB (no production data; V36 was one commit prior).
- **Append path (`AuditService.record`):** now locks the org's `audit_chain_head` (`insertIfAbsent` +
  `PESSIMISTIC_WRITE`, the `benefit_accumulator` row-lock pattern) so concurrent audit writes for an org serialize
  and the chain can't fork; computes `entry_hash` from the locked `last_hash`; saves the event with its
  `sequence_no`/`prev_hash`/`entry_hash`; advances the head — all still inside the caller's transaction.
- **Pure core `AuditHashChain`** (no Spring/DB): the ONE canonical serialization + the HMAC compute, shared by the
  writer and the verifier (a pure policy class, unit-tested). Timestamp fingerprinted as a UTC instant truncated to
  micros so a DB round-trip reproduces it exactly.
- **Verify endpoint `GET /api/v1/audit-events/verify`** (AUDITOR/ORG_ADMIN): walks the org chain in sequence order,
  re-checking each position, `prev_hash` link and recomputed fingerprint, then cross-checks the head (catches
  truncation). Returns `{valid, entriesChecked, brokenAtSequence, reason}`.
- **Verify:** backend `./mvnw -B clean verify` green (384 → **406** tests): new `AuditHashChainTest` (6, pure —
  determinism, field-change detection, key dependence) + `AuditChainApiIntegrationTest` (6 — intact chain valid; a
  field modified directly in the DB detected then restored; a row deleted from the DB detected then restored;
  tampering one org doesn't flip another (per-org keys); verify gated to AUDITOR/ORG_ADMIN, reviewer/patient 403;
  requires auth 401). Tamper tests use `JdbcTemplate` to mutate/delete rows behind the app's back, then restore so
  the shared chain is left intact.
- **Deferred:** slice 3 = the auditor-facing **UI** (log viewer + a "verify integrity" button); then break-glass,
  access reviews, retention.
- **Next:** Phase 7, slice 3 — the audit-trail UI.

### 2026-09-16 — Phase 7, slice 1 ✅ (security audit event log — the audit-trail foundation, backend)
- **Why:** Phase 7 is advanced security/governance. Its headline features — a **tamper-evident per-org HMAC audit
  chain**, **break-glass** emergency access, access reviews, retention — all hang off one thing we did not have yet:
  a **security audit trail**. The "audit event" named in CLAUDE.md's one-transaction pattern
  (`domain change + history + audit event + outbox`) had been *aspirational* — we wrote status-history rows but never
  a general-purpose audit log. This slice builds that foundation. (Bonus: the **AUDITOR** role, seeded back in V2 and
  unused ever since, finally gets a job.)
- **Migration `V36__audit_event.sql`:** a tenant-owned, **append-only, immutable** `audit_event` — `organization_id`
  (tenant key), `occurred_at`, nullable `actor_user_id`, `action`, `resource_type`/`resource_id`, `outcome`
  (SUCCESS/DENIED), `correlation_id`, PHI-free `detail`. No version column, no UPDATE/DELETE path. Indexes on
  `(organization_id, occurred_at DESC)` and `(organization_id, resource_type, resource_id)`.
- **New `com.healthcloud.audit` package:** `AuditEvent` entity (immutable, like `claim_anomaly_signal`),
  `AuditAction` + `AuditOutcome` enums, org-scoped `AuditEventRepository` (capped `findTop200…`), `AuditEventDto`,
  and **`AuditService`** with two jobs: `record(...)` — writes an event **inside the caller's transaction** (§31.6),
  deriving tenant + actor from the backend `UserContext` and the correlation id from `CorrelationId` (deliberately
  *not* `@Transactional` so it joins the domain tx, commits atomically, or both roll back) — and `list(...)`, the
  read, gated to **AUDITOR/ORG_ADMIN**.
- **Read API `GET /api/v1/audit-events`** (thin `AuditController`): the tenant's events newest-first, or one
  resource's history via `?resourceType=&resourceId=`. A **role-gated list** → a disallowed role is a flat **403**
  (not a secure 404).
- **Wired into 2 exemplar actions** (proving the mechanism generalizes across domains): `AdjudicationService.adjudicate`
  → `CLAIM_ADJUDICATED` (money) and `ConsentDirectiveService.revoke` → `CONSENT_REVOKED` (privacy), each recorded in
  the action's existing transaction. Remaining actions get audit events in later slices — slice 1 establishes the
  pattern, not exhaustive coverage.
- **Seed:** an `auditor@<org>` login ("Avery Auditor", AUDITOR) so the endpoint is demoable (seeded members per org
  6 → 7).
- **Verify:** backend `./mvnw -B clean verify` green (**394** tests; new `AuditApiIntegrationTest`, 10 cases —
  requires-auth 401, adjudication writes exactly one `CLAIM_ADJUDICATED` in the same tx with a correlation id +
  PHI-free detail, re-adjudication appends a 2nd event, consent-revoke writes `CONSENT_REVOKED`, AUDITOR/ORG_ADMIN
  200, reviewer/provider/patient 403, tenant-scoped isolation; updated `DevDataSeederTest` member count 6 → 7).
- **Deferred (the Phase 7 arc):** slice 2 = **tamper-evident** — add `prev_hash`/`entry_hash` + a per-org HMAC
  signing key + a `verify` endpoint (the flagship); slice 3 = the auditor-facing **UI**; later = break-glass, access
  reviews, retention.
- **Next:** Phase 7, slice 2 — make the trail tamper-evident (per-org HMAC hash chain + verification).

### 2026-09-16 — Phase 6, slice 21 ✅ (rendering-provider picker on claim create — GET /api/v1/providers + form field)
- **Why:** the last piece of provider network. The backend already accepted a `renderingProviderId` on claim
  create (slice 18) and the engine already reads it (slice 19), but there was no UI to set it and no way to list a
  tenant's providers to pick from. This slice adds both, so an out-of-network claim can be produced end-to-end in
  the browser. **This completes Phase 6's advanced-claims areas.** No migration, no engine change.
- **Backend — a provider directory read (`GET /api/v1/providers`):** new `identity` package pieces —
  `ProviderDto(userId, fullName)` (minimum-necessary), `ProviderDirectoryService.list()` (the caller's tenant's
  **active PROVIDERs**, sorted by name — same "active same-tenant PROVIDER" resolution as
  `PlanNetworkProviderService.listCandidates`), and a thin `ProviderController`. Gated to the **claim-create roles**
  (PROVIDER/CARE_COORDINATOR/ORG_ADMIN) so a PATIENT (or a CLAIMS_REVIEWER, who doesn't create claims) can't
  enumerate staff. Read-only over existing tables — no Flyway migration.
- **Frontend:** `Provider` type + `api.listProviders` + `useProviders` hook (in `src/claims/`); an optional
  **"Rendering provider"** native `<select>` on `CreateClaimForm` (blank → omit); the claim detail header now shows
  "· rendered by <name>" (resolved id → name via `useProviders`, best-effort). Also caught up the frontend
  `Claim`/`ClaimSummary`/`CreateClaimRequest` types to carry `renderingProviderId` (the backend DTOs have since
  slice 18 — no UI had consumed it yet), which meant adding `renderingProviderId: null` to several existing
  ClaimSummary/Claim test fixtures.
- **Design note (unchanged from the plan):** this is now the **4th** copy of the "validate an active same-tenant
  PROVIDER" logic (ProviderPatientAssignmentService, PlanNetworkProviderService, ClaimService, and this directory
  read reuses the membership+role lookup) — still flagged for a future shared `ProviderValidator`/directory extract,
  deliberately not done here to keep the slice small.
- **Verify:** backend `./mvnw -B clean verify` green (378 → **384** tests; new `ProviderDirectoryApiIntegrationTest`,
  6 cases — tenant listing, non-provider exclusion, coordinator-read, patient-403, reviewer-403, tenant isolation).
  Frontend `typecheck` + `test` (132 → **134**) + `build` all green. Live browser check skipped (the in-app browser
  can't reach localhost here); the integration test exercises the real HTTP endpoint against real Postgres.
- **Next:** Phase 6's advanced-claims areas are done. Options for the next session: a Phase 6 wrap/review pass
  (`/code-review`), or begin **Phase 7** (advanced security/governance — break-glass, tamper-evident audit chains).

### 2026-09-16 — Phase 6, slice 20 ✅ (provider-network UI part 1 — OUT_OF_NETWORK chip + plan network admin card)
- **Why:** put the slice-17/19 backend in the browser: see out-of-network results, and manage a plan's network.
  The rendering-provider picker on claim create (which needs a new `GET /api/v1/providers` read) is deferred to
  slice 21 to keep each slice small and single-concern. **Frontend-only.**
- **OUT_OF_NETWORK chip:** added `'OUT_OF_NETWORK'` to the `LineOutcome` type and an `error` case to
  `lineOutcomeColor` — the adjudication breakdown card already renders line outcomes through it, so OON lines get a
  proper chip automatically (AUTH_REQUIRED stays warning; OON is error — the member owes the full charge).
- **Network providers card** on `CoveragePlanDetailPage` (mirrors the Exclusions / Prior-auth cards, but the
  participant is a provider): lists the plan's in-network PROVIDERs by resolved `providerName` with a **Remove**
  (ORG_ADMIN), and an **Add** control = a native provider `<select>` populated from the candidates endpoint (+ an
  Add button). Reads open to same-tenant; add/remove/candidates ORG_ADMIN.
- **Wiring:** `api/types.ts` (`PlanNetworkProvider`, `AddNetworkProviderRequest`; reused the existing
  `AssignmentCandidate` for candidates), `api/client.ts` (`listNetworkProviders`/`listNetworkProviderCandidates`/
  `addNetworkProvider`/`removeNetworkProvider`), `src/coverage/useCoverage.ts` (`useNetworkProviders`,
  `useNetworkProviderCandidates(planId, enabled)` — gated so a non-admin viewer doesn't call the ORG_ADMIN-only
  candidates endpoint, `useAddNetworkProvider`, `useRemoveNetworkProvider`).
- **Tests (+4 → 132 frontend, all green; `typecheck` + `build` green):** a `statusColor` test for
  `lineOutcomeColor('OUT_OF_NETWORK')`, plus `CoveragePlanDetailPage` cases — renders a network provider; an admin
  adds one via the picker (`addNetworkProvider('pl1', {providerUserId})`); an admin removes one.
- **Verification note:** the in-app browser can't render `localhost` in this environment, so no browser screenshot;
  behavior is covered by the RTL tests + typecheck + build (UI), slice-17 integration tests (endpoints), and
  slice-16's proxy check (the SPA↔backend path).
- **Next:** slice 21 — the rendering-provider picker on claim create (+ `GET /api/v1/providers`), completing Phase 6.

### 2026-09-16 — Phase 6, slice 19 ✅ (provider network wired into adjudication — OUT_OF_NETWORK, backend)
- **Why:** the slice where provider network affects the money. The 3rd of the ~4 provider-network slices (only the
  UI remains). **Backend-only.**
- **`LineOutcome.OUT_OF_NETWORK`** (migration `V35` extends the `adjudication_line.outcome` CHECK, mirroring V27's
  AUTH_REQUIRED add). Additive; existing rows unaffected.
- **`AdjudicationService`:** injects `PlanNetworkProviderRepository`; in the covered branch computes a claim-level
  `outOfNetwork` = the covering plan defines a network **and** the claim has a rendering provider **and** that
  provider is not in the network. When true, every non-excluded line is `OUT_OF_NETWORK` (allowed 0, plan pays 0,
  member owes the charge, no deductible/OOP — reuses `deniedLine`), and its charge is added to the header member
  responsibility. **Precedence: exclusion > out-of-network > auth requirement > covered.**
- **Rule (a) for a null rendering provider** (as agreed): no penalty — so every existing claim/test (all
  null-provider) and a plan with no network are unaffected. Opt-in.
- **Seeder:** a 2nd PROVIDER per org (`provider2@`/Morgan, unassigned) + the PPO's network = {Dana}
  (`plan_network_provider`), so a claim rendered by Morgan on the PPO adjudicates OUT_OF_NETWORK — a demoable
  scenario. (The seeded claim + amount-asserting PPO tests have null rendering providers → unaffected.)
- **Tests (+3 adjudication → 378; fixed the seeder member-count test 5→6 for the 2nd provider; all green,
  `./mvnw -B clean verify`):** an out-of-network rendering provider → OUT_OF_NETWORK lines (plan pays 0, member
  owes charge); an in-network provider → COVERED; a null rendering provider on a networked plan → COVERED. Each
  test creates its own plan + network via the slice-17 endpoint and uses the two seeded providers.
- **Not in this slice:** the frontend (the OUT_OF_NETWORK chip + `LineOutcome` type + rendering-provider picker +
  network admin card) is slice 20; between 19 and 20 an OON line renders with a default chip color.
- **Next:** slice 20 — provider-network UI, which completes Phase 6's advanced-claims areas.

### 2026-09-16 — Phase 6, slice 18 ✅ (rendering provider on the claim, backend)
- **Why:** the claim-side data provider network needs — who rendered the service — so slice 19's engine rule can
  tell in- from out-of-network. The 2nd of the ~4 provider-network slices. **Backend-only.**
- **Migration `V34__claim_rendering_provider.sql`:** `ALTER TABLE claim ADD COLUMN rendering_provider_id UUID`
  (nullable), plain FK → `app_user(id)` (app_user isn't tenant-keyed, so the role/tenant check is in-service).
- **`Claim` entity:** a nullable, `updatable = false` `renderingProviderId` set at creation, with a getter. A
  delegating 6-arg constructor (no provider) keeps every existing caller — the seeder + repository tests —
  compiling unchanged; the new 7-arg constructor takes the provider.
- **`CreateClaimRequest`:** an **optional** `renderingProviderId` (nullable, not `@NotNull`).
- **`ClaimService.create`:** when supplied, validates the provider is an **active same-tenant PROVIDER** (else 400,
  no existence leak) via `OrganizationMembershipRepository` + `UserRoleRepository` (same pattern as
  `ProviderPatientAssignmentService` / `PlanNetworkProviderService` — the 3rd copy; a shared `ProviderValidator`
  extract is a noted future cleanup), then stamps it on the claim.
- **`ClaimDto` + `ClaimSummaryDto`:** expose `renderingProviderId` as a raw id (like `createdBy`; the UI resolves
  the name in slice 20).
- **Design (as agreed):** header-level, one rendering provider per claim (per-line is a later refinement);
  optional/backward-compatible (existing + no-provider claims are null); slice 19 treats a null rendering provider
  as no out-of-network penalty. No seeding yet (a rendering provider is seeded in slice 19 when it changes a result).
- **Tests (+3 → 375 backend, all green; `./mvnw -B clean verify` green):** in `ClaimApiIntegrationTest` — a claim
  records a rendering provider (echoed on create + single read + list summary); a claim without one is allowed
  (`renderingProviderId: null`); a non-PROVIDER same-tenant user → 400.
- **Next:** slice 19 — wire the rendering provider + a plan's network into adjudication: a covered line rendered by
  a provider not in the covering plan's network → a new `OUT_OF_NETWORK` line outcome.

### 2026-09-16 — Phase 6, slice 17 ✅ (provider network config — which providers are in a plan's network, backend)
- **Why:** the "provider network" item on Phase 6's advanced-claims list — the last area. It's ~4 slices (a plan
  needs a network, a claim needs a rendering provider, the engine must apply an out-of-network rule, then UI); this
  is the first: the plan-side config, the lowest-risk, most pattern-consistent starting point (mirrors how
  prior-auth started with its plan-side config table, wired to the engine later). **Backend-only.**
- **New in `com.healthcloud.coverage`:** `PlanNetworkProvider` (entity: `coveragePlanId` + `providerUserId`,
  immutable, no `@Version`), `PlanNetworkProviderRepository` (org-scoped finders + `existsBy…CoveragePlanId` — the
  engine's "is a network defined?" hook), `PlanNetworkProviderDto` (id · plan · provider · resolved name),
  `AddNetworkProviderRequest` (`providerUserId`), `PlanNetworkProviderService`, `PlanNetworkProviderController`
  (`GET/POST /api/v1/coverage-plans/{planId}/network-providers`, `GET .../candidates`, `DELETE .../{id}`).
- **Same plan-config shape as `plan_prior_auth_requirement`** (tenant-owned, not patient-scoped; reads open to
  same-tenant, **add/remove + candidates ORG_ADMIN**; duplicate → 409; cross-tenant plan → secure 404) — **but the
  participant is a provider (`app_user`)**, not a catalog code: `app_user` isn't tenant-keyed so there's no
  FK-with-org on the provider; the service validates it's an **active same-tenant PROVIDER** (else 400, no
  existence leak) via `OrganizationMembershipRepository` + `UserRoleRepository`, exactly as
  `ProviderPatientAssignmentService` does, and offers a **candidate picker** (same-tenant PROVIDERs not already in
  the network → `AssignmentCandidateDto`).
- **Migration `V33__plan_network_provider.sql`:** FK-with-org to `coverage_plan`, plain FK `provider_user_id →
  app_user(id)`, `UNIQUE(organization_id, coverage_plan_id, provider_user_id)`, index.
- **Inert config this slice** (documented): it does not affect adjudication until slice 19 wires it (a covered line
  rendered out-of-network → `OUT_OF_NETWORK`); a plan with no network rows imposes no restriction (opt-in). No
  seeding yet — a demo network is seeded in slice 19 when it changes a result.
- **Tests (+7 → 372 backend, all green; `./mvnw -B clean verify` green):** `PlanNetworkProviderApiIntegrationTest`
  — auth (401); admin add/list/remove with the provider name resolved; reads open to a coordinator but add → 403;
  a non-PROVIDER same-tenant user → 400; duplicate → 409; candidates exclude already-added providers; another
  tenant's plan → secure 404.
- **Next:** slice 18 — rendering provider on the claim (a nullable `renderingProviderId`, validated as a same-tenant
  PROVIDER), the claim-side data the engine needs.

### 2026-09-16 — Phase 6, slice 16 ✅ (reprocessing UI — batch queue + detail + Run form)
- **Why:** slice 15 shipped the reprocessing backend; this puts it in the browser (the backend-then-UI rhythm),
  completing reprocessing end-to-end. **Frontend-only.**
- **New feature folder `src/reprocessing/`** — deliberately simpler than the decision-aggregate UIs because a batch
  is a **job, not a state machine**: no `transitions.ts`, no decision buttons, no reason prompts, no optimistic
  locking. Files: `statusColor.ts` (`reprocessingStatusColor` COMPLETED→success / COMPLETED_WITH_ERRORS→warning /
  RUNNING→info; `itemOutcomeColor` SUCCEEDED→success / FAILED→error), `useReprocessing.ts`
  (`useReprocessingBatches`/`useReprocessingBatch`/`useRunReprocessingBatch`), `ReprocessingBatchesPage.tsx` (work
  queue: Batch # · plan name from the summary DTO · status chip · succeeded/failed/total · run time; + the Run form
  for run roles), `ReprocessingBatchDetailPage.tsx` (header — plan · status · counts · started/finished — + a
  per-claim items table: claim # resolved via `useClaims` and linked to `/claims/:id`, an outcome chip, the new
  adjudication version on success, the PHI-free message on failure), `CreateReprocessingBatchForm.tsx` (a single
  coverage-plan `<select>` from `useCoveragePlans`, RHF+Zod; on run navigates to the new batch; the button shows a
  running state — the backend batch is synchronous).
- **`api/types.ts` + `api/client.ts`:** `ReprocessingBatchStatus`/`ReprocessingItemOutcome`/`ReprocessingItem`/
  `ReprocessingBatch`/`ReprocessingBatchSummary`/`CreateReprocessingBatchRequest` + `listReprocessingBatches`/
  `getReprocessingBatch`/`runReprocessingBatch`.
- **`App.tsx`** (+2 routes: `reprocessing`, `reprocessing/:id`); **`layout/AppLayout.tsx`** (+ a **Reprocessing**
  nav button gated to **CLAIMS_REVIEWER/ORG_ADMIN** — the run/monitor audience).
- **Role-aware UI (convenience; backend still enforces):** the Run form + nav are shown only to CLAIMS_REVIEWER/
  ORG_ADMIN, mirroring the backend `REPROCESS_ROLES`.
- **Tests (+8 → 128 frontend, all green; `typecheck` + `build` green):** `statusColor.test.ts` (2), plus
  `ReprocessingBatchesPage` (lists batches; empty state; reviewer sees the Run form, a provider does not),
  `ReprocessingBatchDetailPage` (header + items table — claim resolved, outcomes, version, failure message), and
  `CreateReprocessingBatchForm` (picking a plan runs with that plan id; blocks submit with no plan).
- **Live check:** DB reset → backend restarted (Flyway applied through **V32**, both reprocessing tables present).
  Verified the exact same-origin path the SPA uses: adjudicated the seeded PPO claim, then **through the Vite
  `:5173` proxy** ran `POST /api/v1/reprocessing-batches` → COMPLETED, total/succeeded 1, item SUCCEEDED with
  `adjudicationVersion: 2` and `coveragePlanName: "Standard PPO"` (matching the client types), and the batch listed
  back. (The in-app browser wouldn't render `localhost` in this environment; component rendering/role-gating/forms
  are covered by the RTL tests, and the proxy/data path by this check.)
- **Next:** provider network — the last Phase-6 area (in/out-of-network providers affecting adjudication).

### 2026-09-16 — Phase 6, slice 15 ✅ (reprocessing — batch re-adjudication of a plan's claims after a config change, backend)
- **Why:** the "reprocessing" item on Phase 6's advanced-claims list, and the natural build-on from slice 11's
  re-adjudication engine: after a plan-config change (a fixed fee schedule, a new exclusion/prior-auth requirement,
  a retroactive enrollment) an admin/reviewer re-runs the plan's already-ADJUDICATED claims so their money reflects
  the new config. **Backend-only** (the reprocessing UI is a later slice).
- **New package `com.healthcloud.reprocessing`:** `ReprocessingBatchStatus` (RUNNING/COMPLETED/COMPLETED_WITH_ERRORS),
  `ReprocessingItemOutcome` (SUCCEEDED/FAILED), `ReprocessingBatch` (entity: scope `coveragePlanId`, `RPB-XXXXXXXX`
  number, status + total/succeeded/failed counts, `finish(...)`), `ReprocessingItem` (immutable child: claim,
  outcome, new `adjudicationVersion` on success, PHI-free `message` on failure), repositories, DTOs
  (`ReprocessingBatchDto` header+items / `ReprocessingBatchSummaryDto` / `ReprocessingItemDto`),
  `CreateReprocessingBatchRequest` (`coveragePlanId`), `ReprocessingService`, `ReprocessingController`
  (`GET/POST /api/v1/reprocessing-batches`, `GET .../{id}`).
- **Orchestrates only, no math change:** `createAndRun` selects the tenant's ADJUDICATED claims whose **current**
  adjudication is on the plan (new `AdjudicationRepository.findDistinctClaimIdsByCoveragePlan` + a status/current-plan
  filter), then calls the unchanged slice-11 `AdjudicationService.adjudicate(claimId)` for each — appending a new
  immutable adjudication version per claim.
- **A job record, NOT a state machine:** MVP runs synchronously and the batch lands COMPLETED / COMPLETED_WITH_ERRORS
  — no client transitions, no status-history table (the batch + its items are the record).
- **Deliberate transaction-shape departure from the one-tx rule (§31):** `createAndRun` is
  `@Transactional(propagation = NOT_SUPPORTED)` (no surrounding tx), so each `adjudicate(claimId)` (a separate bean's
  `@Transactional` method) commits/rolls back on its own; a single claim's failure is caught and recorded as a
  FAILED item, never rolling back the batch or the other claims. Documented in CLAUDE.md.
- **Authorization:** gated to **CLAIMS_REVIEWER/ORG_ADMIN** (the engine command's roles — broad, so every tenant
  claim is reachable and the per-claim re-adjudication's own §21 gate composes cleanly); an in-tenant plan is
  required (else 400); reads are tenant-scoped (another tenant's batch → secure 404). Not consent field-masked.
- **Migration `V32__reprocessing_batch.sql`:** `reprocessing_batch` (FK-with-org to `coverage_plan`, unique number,
  `UNIQUE(id, organization_id)`) + `reprocessing_item` (FK-with-org to batch + claim); status/outcome CHECKs.
- **Honest MVP limits:** synchronous (async/recoverable outbox+worker is Phase 8 — a crashed batch can stay
  RUNNING, no recovery yet); single-plan scope (no date-range/all-plans); no Idempotency-Key; each claim
  reversed/recomputed independently (carries over slice 11's limit).
- **Tests (+6 → 365 backend, all green; `./mvnw -B clean verify` green):** `ReprocessingApiIntegrationTest` — auth
  (401); happy path (run a batch → the plan's claim gets version 2, COMPLETED, total/succeeded=1, a SUCCEEDED item
  with `adjudicationVersion:2`); a provider → 403; a plan outside the tenant → 400; a batch touches only its own
  plan's claims (plan B total=0, plan A's claim untouched at v1); another tenant's batch → secure 404.
- **Next:** reprocessing UI (queue/detail — batches + per-claim outcomes; a Run form for reviewers/admins), or
  provider network (the last Phase-6 area).

### 2026-09-16 — Phase 6, slice 14 ✅ (manual-review UI — queue + detail + decisions + open form)
- **Why:** slice 13 shipped the claim-review backend; this puts it in the browser (the backend-then-UI rhythm),
  completing manual review end-to-end. **Frontend-only.**
- **New feature folder `src/claimreview/`** (mirrors `src/appeal/`): `statusColor.ts` (`claimReviewStatusColor` —
  RESOLVED→success, OPEN→info, CANCELLED→default), `transitions.ts` (client mirror of `ClaimReviewTransitions` —
  Resolve gated to **CLAIMS_REVIEWER/ORG_ADMIN**, Cancel to the opener roles; **`reasonRequired` true for all**),
  `useClaimReview.ts` (`useClaimReviews`/`useClaimReview`/`useClaimReviewHistory`/`useCreateClaimReview`/
  `useChangeClaimReviewStatus`), `ClaimReviewsPage.tsx` (queue Review # · patient · claim # · status, resolving names
  via `usePatients`/`useClaims`, + the New-review form for opener roles), `ClaimReviewDetailPage.tsx` (header + a
  link to the reviewed claim + reason + resolution + status timeline + optimistic-locked decision buttons that always
  prompt for a reason), `CreateClaimReviewForm.tsx` (a claim select — any claim — + an optional multiline reason,
  RHF+Zod).
- **`api/types.ts` + `api/client.ts`:** `ClaimReviewStatus`/`ClaimReview`/`ClaimReviewSummary`/`ClaimReviewStatusHistory`/
  `ClaimReviewStatusChange`/`CreateClaimReviewRequest` + `listClaimReviews`/`getClaimReview`/`createClaimReview`/
  `changeClaimReviewStatus`/`getClaimReviewHistory`.
- **`App.tsx`** (+2 routes: `claim-reviews`, `claim-reviews/:id`); **`layout/AppLayout.tsx`** (+ a **Reviews** nav
  button, CARE_COORDINATOR/CLAIMS_REVIEWER/ORG_ADMIN — no PROVIDER, mirroring who can open/resolve).
- **Tests (+14 → 120 frontend, all green; `typecheck` + `build` green):** `transitions.test.ts` (5), plus
  `ClaimReviewsPage`/`ClaimReviewDetailPage`/`CreateClaimReviewForm` tests (reviewer sees Resolve+Cancel; coordinator
  sees Cancel only; a provider sees the queue but no open form; resolve prompts for a reason then PATCHes; the form
  submits {claimId, reason} and blocks when no claim is chosen).
- **Live-verified end-to-end:** fresh backend boot applied V31; logged in as `reviewer@northcare`, opened the
  **Reviews** queue (seeded MRV-6AD801 for Sam Sample, OPEN), opened its detail (link to the reviewed claim + reason
  + Resolve/Cancel + timeline), used the Resolve reason prompt; the resolve endpoint returned OPEN → RESOLVED (the
  in-browser PATCH hit a flaky Vite dev-proxy timeout, so the transition was confirmed via the API directly and the
  reloaded queue then showed RESOLVED).
- **Docs:** CLAUDE.md `claimreview` blurb (UI shipped) + new `src/claimreview/` frontend blurb + Phase-6 header
  1–13 → 1–14. **No backend change.**

### 2026-09-16 — Phase 6, slice 13 ✅ (claim manual review — open/resolve/cancel a review case on a claim, backend)
- **Why:** the "manual review" item on Phase 6's advanced-claims list, and the durable human workflow on top of
  slice-11 anomaly signals (which are advisory and replaced on every rescan, so a poor home for a human decision).
  The **7th decision aggregate** — a near-mirror of appeal — so it reuses the whole established pattern.
  **Backend-only.**
- **New package `com.healthcloud.claimreview`** (13 classes): `ClaimReviewStatus` (OPEN/RESOLVED/CANCELLED),
  `ClaimReview` (entity — claimId + patientId denormalized from the claim + reviewNumber `MRV-XXXXXXXX` + reason
  (why opened) + resolution (the conclusion) + openedBy/resolvedBy/resolvedAt + @Version; `resolve()`),
  `ClaimReviewStatusHistory`, `ClaimReviewTransitions` (pure policy — the 6th state machine: OPEN → RESOLVED
  [CLAIMS_REVIEWER/ORG_ADMIN] / CANCELLED [opener roles CARE_COORDINATOR/CLAIMS_REVIEWER/ORG_ADMIN]; reason required
  on every transition; isDecision=RESOLVED), repositories, DTOs (`ClaimReviewDto`/`SummaryDto`/`StatusHistoryDto`),
  `CreateClaimReviewRequest`/`ClaimReviewStatusChangeRequest`, `ClaimReviewService`, `ClaimReviewController`.
- **`ClaimReviewService`** mirrors `AppealService`: **open** requires an opener role + a reachable claim (patient-gated
  → secure 404) + **no existing OPEN review** (409), stamps the patient from the claim; **changeStatus** checks
  exists → legal move → role → reason → optimistic version, resolves (stamping the resolver) or cancels, status +
  history in one tx; list scoped via `accessiblePatientIdsIfGated`.
- **Migration V31 `claim_review` + `claim_review_status_history`:** FK-with-org to both `claim` and `patient`
  (§32.10), `UNIQUE(org, review_number)` + `UNIQUE(id, org)`, a **partial unique index on `(org, claim_id) WHERE
  status='OPEN'`** (one open review per claim), indexes on (org,status)/(org,claim_id)/(org,patient_id).
- **Seeder:** opens one OPEN review (MRV-…01) on the seeded REJECTED claim so a demo review queue returns something.
- **Tests (+16 → 359 backend, all green via `./mvnw -B clean verify`):** `ClaimReviewTransitionsTest` (5, pure) +
  `ClaimReviewApiIntegrationTest` (11, RANDOM_PORT + real PG — open creates OPEN with an MRV number; a reviewer
  resolves; resolve requires a reason; a coordinator opens+cancels but **can't resolve** (403); duplicate open → 409;
  illegal transition → 409; stale version → 409; a provider sees only assigned patients' reviews; cross-tenant →
  secure 404; unauthenticated → 401).
- **Docs:** CLAUDE.md new `claimreview` blurb + `ClaimReviewTransitions` in the pure-policy convention + seeder blurb
  + Phase-6 header 1–12 → 1–13.
- **Honest MVP limitations:** a review is a **tracking** record — opening one neither holds the claim nor changes its
  status (the reviewer still uses accept/reject/adjudicate); not structurally linked to specific anomaly signals; no
  UI yet (a manual-review queue/detail is a later slice).

### 2026-09-16 — Phase 6, slice 12 ✅ (anomaly UI — Anomalies card + Scan button on the claim detail page)
- **Why:** slice 11 shipped the anomaly-detection backend; this puts it in the browser (the backend-then-UI rhythm),
  completing anomaly signals end-to-end. **Frontend-only.**
- **`api/types.ts`:** `AnomalySeverity`, `AnomalySignalType`, `ClaimAnomalySignal` (mirror the DTO).
- **`api/client.ts`:** `scanClaimAnomalies(id)` (`POST /api/v1/claims/{id}/anomaly-scan`) + `listClaimAnomalies(id)`
  (`GET .../anomalies`).
- **`claims/useClaims.ts`:** `anomaliesKey`, `useAnomalies(id)`, `useScanAnomalies(id)` (invalidates the signals on
  success).
- **`claims/statusColor.ts`:** `anomalySeverityColor` (HIGH→error, MEDIUM→warning, LOW→info).
- **`claims/ClaimDetailPage.tsx`:** a new **Anomalies card** (rendered for any claim status, after the version
  history) — the current signals as severity chip + type + PHI-free detail + detected time, with a **Scan** button
  gated to CLAIMS_REVIEWER/ORG_ADMIN (client mirror of the backend role gate; the backend still enforces it). Empty
  state prompts a reviewer to scan.
- **Tests (+2 → 106 frontend, all green; `typecheck` + `build` green):** in `ClaimDetailPage.test.tsx`, a reviewer
  scans and sees the returned HIGH DUPLICATE_CLAIM signal; a coordinator sees the signal list but **no Scan button**.
  Existing tests updated to mock the two new api methods.
- **Live-verified end-to-end:** created two identical claims for Sam Sample via the API, logged in as
  `reviewer@northcare` in the browser, opened the second claim, clicked **Scan** → a red **HIGH** `DUPLICATE_CLAIM`
  chip appeared naming the first claim (same service date, shared procedure 99213). Fresh backend boot applied
  migration V30.
- **Docs:** CLAUDE.md claims-frontend blurb (+Anomalies card) + anomaly blurb (UI shipped) + Phase-6 header 1–11 →
  1–12. **No backend change.**

### 2026-09-16 — Phase 6, slice 11 ✅ (claim anomaly signals — a deterministic detector + reviewer scan)
- **Why:** the "anomaly signals" item on Phase 6's advanced-claims list (flag suspicious claims). A self-contained
  slice that introduces a **new kind** of building block — a pure *detector* policy — without touching the claim
  state machine or the money math. **Backend-only.**
- **New package `com.healthcloud.anomaly`:** `AnomalySeverity` (LOW/MEDIUM/HIGH), `AnomalySignalType`
  (DUPLICATE_CLAIM/HIGH_TOTAL_CHARGE), `ClaimAnomalySignal` (immutable entity — no `@Version`),
  `ClaimAnomalySignalRepository` (org-scoped finders + a bulk delete-by-claim), `ClaimAnomalyDetector` (the pure
  policy), `ClaimAnomalySignalDto`, `ClaimAnomalyService`, `ClaimAnomalyController`.
- **`ClaimAnomalyDetector` (pure, no Spring/DB — the point of the slice):** given a `Subject` (the claim) + a
  `Context` (its sibling claims + a threshold), returns `List<DetectedSignal>`. Two deterministic heuristics:
  **DUPLICATE_CLAIM** (HIGH — a sibling claim for the same patient with the same service date sharing ≥1 procedure
  code; one signal per matching sibling, naming it) and **HIGH_TOTAL_CHARGE** (MEDIUM — the backend-computed total
  exceeds `healthcloud.anomaly.high-total-charge-threshold`, default **$5000**). A *detector*-shaped pure-policy
  class — it emits findings rather than gating a transition. Honest: a synthetic demo heuristic, not a measured
  fraud model.
- **`ClaimAnomalyService`:** `scan(claimId)` — role gate **CLAIMS_REVIEWER/ORG_ADMIN**, load claim (patient-gated
  → secure 404), gather sibling claims + line procedure codes, run the detector, **replace** the claim's signals
  (delete + insert) in one `@Transactional` → **idempotent**. `list(claimId)` — patient-gated read. Detection is
  **additive**: it never changes the claim's status or the adjudication result.
- **API (nested under the claim, like adjudication):** `POST /api/v1/claims/{id}/anomaly-scan` (reviewer),
  `GET /api/v1/claims/{id}/anomalies` (any same-tenant caller who can reach the claim).
- **Migration V30 `claim_anomaly_signal`:** tenant-owned, FK-with-org to `claim(id, organization_id)` (§32.10),
  a `severity` CHECK, PHI-free `detail`, index on `(organization_id, claim_id)`. No version column (rows replaced
  wholesale). Config: `healthcloud.anomaly.high-total-charge-threshold: 5000.00` in application.yml.
- **Tests (+10 → 343 backend, all green via `./mvnw -B clean verify`):** `ClaimAnomalyDetectorTest` (4, pure —
  duplicate fires on same-date+shared-code; not on different date / disjoint codes; high-total fires strictly
  above the threshold; clean claim → none). `ClaimAnomalyApiIntegrationTest` (6, RANDOM_PORT + real Postgres —
  auth required; a scan flags a duplicate and it persists; **rescan is idempotent** (count stable); a high-total
  claim is flagged; a coordinator can reach the claim but **cannot scan → 403** (reviewer's action); another
  tenant's claim → **secure 404**). The per-resource cross-tenant test satisfies the "every new tenant-owned
  resource" rule.
- **Docs:** CLAUDE.md new `anomaly` blurb + Phase-6 header 1–10 → 1–11 + `ClaimAnomalyDetector` added to the
  pure-policy convention (as a detector).
- **Honest MVP limitation:** detection is a manual reviewer-triggered scan (no auto-trigger at submit/adjudicate);
  no anomaly-resolution / manual-review workflow yet (a later slice builds on these signals); no UI yet (a Scan
  button + Anomalies card on the claim detail page is a later frontend slice).

### 2026-09-16 — Phase 6, slice 10 ✅ (appeal overturn wired into re-adjudication)
- **Why:** slice 8 shipped the appeal backend with a deliberate deferral — "an OVERTURNED appeal records the
  outcome only; wiring it into re-adjudication is a later slice." This is that slice: an overturn now actually
  moves the money by re-running the engine on the disputed claim. **Backend-only, no new tables** — it reuses the
  versioned re-adjudication the engine already supports (Phase 5 slice 11). Closes the loop between the appeal
  aggregate (slices 8–9) and the adjudication engine.
- **`AppealService` now depends on `AdjudicationService`** (constructor injection; no bean cycle — adjudication
  doesn't depend on appeals). In `changeStatus`, after the OVERTURNED appeal + its history row are saved, if the
  disputed claim is currently `ADJUDICATED` the service calls `adjudication.adjudicate(claim.getId())` **in the
  same `@Transactional`** (§31.6) — the overturn and the new adjudication version commit or roll back together.
- **Authorization stays consistent:** overturning is already gated to CLAIMS_REVIEWER/ORG_ADMIN by
  `AppealTransitions`, exactly the roles the engine command requires — no privilege widening.
- **Honest MVP limitation kept explicit:** a **REJECTED** claim's overturn records the outcome only. A REJECTED
  claim was never adjudicated and REJECTED is terminal on the claim state machine, so re-opening it into the
  pipeline is a later slice (would need a claim-machine change). Only ADJUDICATED claims auto-re-adjudicate.
- **Tests (+2 → 333 backend, all green via `./mvnw -B clean verify`):** in `AppealApiIntegrationTest`, a new
  `adjudicatedClaimId` helper drives create→submit→accept→adjudicate; `overturning_an_appeal_on_an_adjudicated_claim_reajudicates`
  asserts `GET .../adjudication/versions` goes 1 → 2 after the overturn; `overturning_..._rejected_claim_records_the_outcome_only`
  asserts a REJECTED claim's overturn produces no adjudication (`GET .../adjudication` → 404). Both are real
  HTTP+session+Postgres flows against the embedded server — the end-to-end proof.
- **Docs:** CLAUDE.md `appeal` blurb updated (overturn-wiring paragraph replaces the deferral note); Phase-6
  header count 1–9 → 1–10.
- **No frontend change:** the effect is already visible on the claim detail page's existing **Version history**
  card (a second version appears after an overturn).

### 2026-09-16 — Phase 6, slice 9 ✅ (appeal UI — queue + detail + decisions + submit form)
- **Why:** slice 8 shipped the appeal backend; this puts it in the browser, mirroring the referral frontend
  (`src/referral/`), completing the claims lifecycle in the UI (submit → adjudicate → appeal). **Frontend-only.**
- **New feature folder `src/appeal/`** (mirrors `src/referral/`): `statusColor.ts` (`appealStatusColor` —
  OVERTURNED→success, UPHELD/WITHDRAWN→default, SUBMITTED→info), `transitions.ts` (client mirror of
  `AppealTransitions` — Uphold/Overturn gated to **CLAIMS_REVIEWER/ORG_ADMIN**, Withdraw to the submitter;
  **`reasonRequired` true for all**), `useAppeal.ts` (`useAppeals`/`useAppeal`/`useAppealHistory`/`useCreateAppeal`/
  `useChangeAppealStatus`), `AppealsPage.tsx` (queue Appeal # · patient · claim # · status, resolving
  `patientId→name` via `usePatients` and `claimId→claimNumber` via `useClaims`, + the New-appeal form for submitter
  roles), `AppealDetailPage.tsx` (header + a link to the disputed claim + reason + status timeline +
  optimistic-locked decision buttons that always prompt for a reason), `CreateAppealForm.tsx` (a claim select
  filtered client-side to **appealable** ADJUDICATED/REJECTED claims + a multiline reason, RHF+Zod).
- **API + wiring:** `api/types.ts` (+`AppealStatus`/`AppealSummary`/`Appeal`/`AppealStatusHistory`/
  `AppealStatusChange`/`CreateAppealRequest`), `api/client.ts` (+`listAppeals`/`getAppeal`/`getAppealHistory`/
  `changeAppealStatus`/`createAppeal`), `App.tsx` (+2 routes), `AppLayout.tsx` (+an **Appeals** nav button gated to
  PROVIDER/CARE_COORDINATOR/CLAIMS_REVIEWER/ORG_ADMIN — the reviewer is included, unlike referrals).
- **Verified — automated:** `npm run typecheck` clean, `npm test` → **104 tests pass** (+13: `transitions.test`
  ×4, `AppealsPage.test` ×3, `AppealDetailPage.test` ×4, `CreateAppealForm.test` ×2), `npm run build` green.
- **Verified — live** (reviewer + coordinator in the browser): the reviewer's queue showed the seeded/created
  appeals with patient + claim numbers resolved; opening the SUBMITTED one showed Uphold/Overturn (no Withdraw);
  Uphold prompted for a reason → UPHELD live with the timeline appending `SUBMITTED → UPHELD` + decision reason;
  the coordinator's New-appeal form rendered with the claim picker correctly filtered to the two REJECTED claims.
- **Files:** +`frontend/src/appeal/` (8 files incl. 4 tests); changed `api/types.ts`, `api/client.ts`, `App.tsx`,
  `layout/AppLayout.tsx`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-16 — Phase 6, slice 8 ✅ (appeals — dispute a claim's decision + resolution lifecycle, backend)
- **Why:** referrals are complete end-to-end; appeals are the next Phase-6 area and round out the claims
  lifecycle (submit → adjudicate → **appeal**). A third instance of the proven decision-aggregate pattern
  (prior-auth → referral → appeal), so low-risk. **Backend-only** (like the other aggregates' first slices);
  the appeal UI is the next slice.
- **New package `com.healthcloud.appeal`** (13 classes mirroring `referral`): `AppealStatus`, `Appeal` (entity +
  `decide(...)`), `AppealStatusHistory`, `AppealTransitions` (pure policy — the 6th exemplar), `AppealRepository`,
  `AppealStatusHistoryRepository`, `AppealDto`, `AppealSummaryDto`, `AppealStatusHistoryDto`, `CreateAppealRequest`,
  `AppealStatusChangeRequest`, `AppealService`, `AppealController`. Endpoints `GET/POST /api/v1/appeals`,
  `GET .../{id}`, `PATCH .../{id}/status`, `GET .../{id}/history` (+ `?claimId=`/`?status=`).
- **Domain:** an appeal disputes a **claim** — `claim_id` (FK-with-org to `claim`), `patient_id` (**denormalized
  from the loaded claim**, never the client, so gating + `accessiblePatientIdsIfGated` list scoping reuse the
  patient machinery), `appeal_number` (`APL-XXXXXXXX`, unique per tenant, server-allocated), `reason` (required,
  the dispute rationale — claims-domain, not PHI), lifecycle fields. **Not consent field-masked** (like a claim).
- **State machine (`AppealTransitions`, pure):** SUBMITTED → UPHELD/OVERTURNED (**CLAIMS_REVIEWER**/ORG_ADMIN —
  a decision stamps `decidedBy`/`decidedAt`) or WITHDRAWN (submitter roles); terminal. **A reason is required on
  EVERY transition** (a small per-domain variation — an appeal outcome/withdrawal always needs a rationale). Same
  check order as the claim/referral machine (exists → legal move → role → reason → optimistic version), one-tx
  status + `appeal_status_history` (null → SUBMITTED on creation). **Submit** gates through the parent claim
  (`PatientAccessGuard` via the claim's patient → secure 404), and validates the claim is **appealable**
  (ADJUDICATED/REJECTED → else 400) with **no existing open (SUBMITTED) appeal** (→ 409). Top-level but gated by
  patient; provider sees only assigned patients' appeals, broad roles get the tenant queue.
- **Migration `V29__appeal.sql`:** two tables (`appeal`, `appeal_status_history`) mirroring V28 — FK-with-org to
  **both** `claim` and `patient`, `UNIQUE(org, appeal_number)` + `UNIQUE(id, org)`, status CHECK, indexes on
  `(org, status)`/`(org, claim_id)`/`(org, patient_id)`. **Seeder:** `seedAppeal` — one additional REJECTED sample
  claim (full null→DRAFT→SUBMITTED→REJECTED history) + one SUBMITTED appeal on it per org's first patient, so a
  demo appeal queue returns something (and the claims queue now shows a realistic 2 claims).
- **Verified — automated:** `./mvnw -B clean verify` → **331 tests pass** (+21: `AppealTransitionsTest` ×5,
  `AppealRepositoryTest` ×4 — tenant-scoped lookup, per-tenant number uniqueness + cross-tenant reuse, the
  open-appeal existence check, history order, claim-FK integrity; `AppealApiIntegrationTest` ×12 — auth required,
  submit→SUBMITTED+number+claim link, reviewer uphold (decider stamped), deciding needs a reason, non-appealable
  claim 400, duplicate open appeal 409, a submitter cannot decide 403, submitter withdraw, illegal transition 409,
  stale version 409, provider sees only assigned patients' appeals + secure 404, cross-tenant secure 404).
  BUILD SUCCESS.
- **Verified — live** (backend on V29, fresh db-reset): the reviewer's queue showed the seeded `APL-…` (SUBMITTED);
  uphold without a reason → 400, with a reason → UPHELD + `decidedBy` set, v→1; appealing a fresh DRAFT claim → 400
  (not appealable); a second open appeal on the same claim → 409; a Green Valley admin GET of a NorthCare appeal
  → 404 (tenant isolation).
- **Files:** +`backend/.../appeal/` (13 classes) +`V29__appeal.sql` +3 test classes; changed `DevDataSeeder.java`,
  `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-16 — Phase 6, slice 7 ✅ (referral UI — queue + detail + decisions + request form)
- **Why:** slice 6 shipped the referral backend; this puts it in the browser, mirroring the prior-auth frontend
  (`src/priorauth/`) exactly. **Frontend-only** — the backend endpoints already exist and are tested.
- **New feature folder `src/referral/`** (mirrors `src/priorauth/`): `statusColor.ts` (`referralStatusColor`),
  `transitions.ts` (client mirror of `ReferralTransitions` for button-gating — Approve/Deny gated to
  **CARE_COORDINATOR/ORG_ADMIN** (not CLAIMS_REVIEWER, matching the backend), Cancel to the requester;
  reason required to deny/cancel), `useReferral.ts` (`useReferrals`/`useReferral`/`useReferralHistory`/
  `useCreateReferral`/`useChangeReferralStatus`), `ReferralsPage.tsx` (work queue: Ref # · patient · specialty ·
  reason · status, + the New-request form for requester roles), `ReferralDetailPage.tsx` (header + timeline +
  optimistic-locked decision buttons with a reason prompt on Deny/Cancel), `CreateReferralForm.tsx` (RHF+Zod;
  patient select + specialty text + a **diagnosis** `MedicalCodePicker`).
- **Reusable picker generalized:** `MedicalCodePicker` gained an optional `category?: 'Procedure' | 'Diagnosis'`
  prop (default `'Procedure'`, so all existing callers are untouched); the referral form passes `'Diagnosis'` so
  the reason picker searches ICD-10-CM codes.
- **API + wiring:** `api/types.ts` (+`ReferralStatus`/`ReferralSummary`/`Referral`/`ReferralStatusHistory`/
  `ReferralStatusChange`/`CreateReferralRequest`), `api/client.ts` (+`listReferrals`/`getReferral`/
  `getReferralHistory`/`changeReferralStatus`/`createReferral`), `App.tsx` (+2 routes), `AppLayout.tsx`
  (+a **Referrals** nav button, gated to PROVIDER/CARE_COORDINATOR/ORG_ADMIN — no reviewer, matching the decision
  model).
- **Verified — automated:** `npm run typecheck` clean, `npm test` → **91 tests pass** (+14: `transitions.test`
  ×4, `ReferralsPage.test` ×3, `ReferralDetailPage.test` ×5, `CreateReferralForm.test` ×2), `npm run build` green.
- **Verified — live** (coordinator in the browser): the Referrals queue showed the seeded `REF-…` (Cardiology,
  I10); the New-request form created a fresh referral (Fern Fixture, Dermatology, reason **J45.909** chosen from
  the diagnosis-filtered picker) → navigated to its detail as REQUESTED; Approve → APPROVED live with the timeline
  appending `REQUESTED → APPROVED`.
- **Files:** +`frontend/src/referral/` (7 files incl. 4 tests); changed `api/types.ts`, `api/client.ts`,
  `App.tsx`, `layout/AppLayout.tsx`, `claims/MedicalCodePicker.tsx`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-16 — Phase 6, slice 6 ✅ (referrals — a care-coordination advanced-claims aggregate, backend)
- **Why:** prior authorization is complete end-to-end; referrals are the next Phase-6 area (PLAN.md Part B, Phase
  6). A referral is a care-coordination request that a patient be seen by a specialty for a coded reason. It is a
  deliberate near-mirror of the proven prior-auth slice-1 aggregate, so the shape is familiar and low-risk.
  **Backend-only** (like prior-auth slice 1); the referral UI (queue/detail/decisions + form) is the next slice.
- **New package `com.healthcloud.referral`** (13 classes mirroring `priorauth`): `ReferralStatus`,
  `Referral` (entity + `decide(...)`), `ReferralStatusHistory`, `ReferralTransitions` (pure policy — the 5th
  exemplar), `ReferralRepository`, `ReferralStatusHistoryRepository`, `ReferralDto`, `ReferralSummaryDto`,
  `ReferralStatusHistoryDto`, `CreateReferralRequest`, `ReferralStatusChangeRequest`, `ReferralService`,
  `ReferralController`. Endpoints `GET/POST /api/v1/referrals`, `GET .../{id}`, `PATCH .../{id}/status`,
  `GET .../{id}/history`.
- **Domain:** `patient_id`, `referral_number` (`REF-XXXXXXXX`, unique per tenant, server-allocated), `specialty`
  (required text, the target), `reason_code_system`+`reason_code` (FK to the global `medical_code`, validated as an
  active **ICD-10-CM diagnosis** — unknown/non-diagnosis → 400), lifecycle fields (`status`, `decision_reason`,
  `decided_by`, `decided_at`, `requested_by`, timestamps, `@Version`). **Deliberately dropped** from the prior-auth
  shape: the coverage-plan FK and service-window (a referral needs neither). **Not consent field-masked** — coded,
  coordination-relevant data only (no narrative), so a coordinator can route it without unrestricted context.
- **State machine (`ReferralTransitions`, pure):** REQUESTED → APPROVED/DENIED (**CARE_COORDINATOR**/ORG_ADMIN —
  a decision stamps `decidedBy`/`decidedAt`; *different* from prior auth's CLAIMS_REVIEWER, showing the layered
  pattern generalizes across roles) or CANCELLED (requester roles); terminal, reason to deny/cancel. Same check
  order as the claim/prior-auth machine (exists → legal move → role → reason → optimistic version), status change
  + a `referral_status_history` row in one `@Transactional` (null → REQUESTED on creation). Top-level but gated by
  its patient (`PatientAccessGuard`): a provider sees only assigned patients' referrals; broad roles get the
  tenant's queue; cross-tenant → secure 404.
- **Migration `V28__referral.sql`:** two tables (`referral`, `referral_status_history`) mirroring V25 — FK-with-org
  to patient, FK to `medical_code` for the reason, `UNIQUE(org, referral_number)` + `UNIQUE(id, org)`, status
  CHECK, indexes. **Seeder:** `seedReferral` — one sample REQUESTED referral (Cardiology, I10) per org's first
  patient, so a demo referral queue returns something.
- **Verified — automated:** `./mvnw -B clean verify` → **310 tests pass** (+21: `ReferralTransitionsTest` ×5,
  `ReferralRepositoryTest` ×4 — tenant-scoped lookup, per-tenant number uniqueness + cross-tenant reuse, history
  order, reason-FK integrity; `ReferralApiIntegrationTest` ×12 — auth required, create→REQUESTED+number, coordinator
  approve (decider stamped), deny needs a reason, a reviewer cannot decide (403), requester cancel, unknown reason
  400, a procedure code is not a valid reason 400, illegal transition 409, stale version 409, provider sees only
  assigned patients' referrals + secure 404, cross-tenant secure 404). BUILD SUCCESS.
- **Verified — live** (backend on V28, fresh db-reset): coordinator's queue showed the seeded `REF-…` (Cardiology,
  I10, REQUESTED); approving it → APPROVED with `decidedBy` set, version→1, history `null→REQUESTED` then
  `REQUESTED→APPROVED`; the provider (assigned to Sam Sample) saw the referral; a Green Valley admin GET of a
  NorthCare referral → **404** and their own queue held only Green Valley's referral (tenant isolation).
- **Files:** +`backend/.../referral/` (13 classes) +`V28__referral.sql` +3 test classes; changed
  `DevDataSeeder.java`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-16 — Phase 6, slice 5 ✅ (plan-prior-auth-requirement admin card — set which procedures need prior auth in the browser)
- **Why:** slice 2 built the `plan_prior_auth_requirement` backend (the config the engine reads to mark a line
  `AUTH_REQUIRED`) but left "no plan-prior-auth-requirement admin UI yet" as the last Phase-6 UI gap — an admin
  could only manage it via the API or the seeder. This adds the browser surface. **Frontend-only** — the
  `GET/POST/DELETE /api/v1/coverage-plans/{planId}/prior-auth-requirements` endpoints already exist (slice 2).
- **`PriorAuthRequirementsCard`** on the coverage-plan detail page (`src/coverage/CoveragePlanDetailPage.tsx`),
  rendered after the Fee schedule card — a **near-twin of the Exclusions card** (code list + remove; add via the
  reusable `MedicalCodePicker`, no amount field). Reads open to same-tenant staff; the add/remove controls show
  only to ORG_ADMIN (`canAdmin`), matching the backend gate — the server still enforces it. Copy explains the
  effect: a claim line billing one of these procedures adjudicates as needing prior authorization unless an
  approved authorization covers the service date.
- **Plumbing (mirrors the exclusion pattern exactly):** `api/types.ts` (+`PlanPriorAuthRequirement`,
  `AddPriorAuthRequirementRequest`), `api/client.ts` (`listPriorAuthRequirements`/`addPriorAuthRequirement`/
  `removePriorAuthRequirement`), `coverage/useCoverage.ts` (`priorAuthRequirementsKey` +
  `usePriorAuthRequirements`/`useAddPriorAuthRequirement`/`useRemovePriorAuthRequirement`, invalidating the list
  on success). No backend, no migration, no new form library (matches the sibling cards' `useState` add control).
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **77 pass** (+3 in
  `CoveragePlanDetailPage.test.tsx`: renders a requirement + a non-admin sees no add/remove control; an admin adds
  via the picker (asserts `addPriorAuthRequirement('pl1', {procedureCode:'99214'})`); an admin removes (asserts
  `removePriorAuthRequirement('pl1','pa1')`)). `npm run build` OK.
- **Verified — live** (admin@northcare, backend + Vite, fresh db-reset): the PPO detail page showed the
  **Prior-auth requirements** card with the seeded **99214** (CPT) + a REMOVE button; typed 99213 into the picker
  (debounced catalog search surfaced the option), **Add requirement** → 99213 appeared alongside 99214 live, then
  **Remove** → back to just 99214. Also API round-trip via curl: add real code → 201, add unknown 99215 → 400
  `VALIDATION_FAILED` (backend code validation), remove → 204.
- **Files:** changed `src/api/types.ts`, `src/api/client.ts`, `src/coverage/useCoverage.ts`,
  `src/coverage/CoveragePlanDetailPage.tsx` (+`CoveragePlanDetailPage.test.tsx`), `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 6, slice 4 ✅ (prior-authorization request form — create a request in the browser)
- **Why:** slice 3 gave the queue/detail/decisions but no way to *create* a prior auth in the browser (only the
  seeded one or the API). This adds a **New request** form, mirroring the claims create form (slice 6).
  **Frontend-only** — the `POST /api/v1/prior-authorizations` endpoint already exists (slice 1).
- **`CreatePriorAuthForm`** on the queue page, shown to the requester roles (PROVIDER/CARE_COORDINATOR/ORG_ADMIN,
  mirroring the backend `REQUEST_ROLES`; a reviewer sees the queue but no form): patient select (`usePatients`),
  coverage-plan select (`useCoveragePlans`), the reusable **`MedicalCodePicker`** (from claims), and service
  from/to dates. RHF + Zod mirroring `CreatePriorAuthorizationRequest` with a client `to ≥ from` window check;
  all fields are strings so no `z.coerce`/3-generic gymnastics (simpler than the claims form). On create it
  navigates to the new auth's detail (REQUESTED).
- **Plumbing:** `api/types.ts` (+`CreatePriorAuthorizationRequest`), `api/client.ts` (`createPriorAuthorization`),
  `priorauth/usePriorAuth.ts` (`useCreatePriorAuthorization`), `PriorAuthorizationsPage.tsx` (renders the form for
  requester roles, now reads `useCurrentUser`).
- **Scope boundary:** no plan-prior-auth-requirement **admin UI** yet (a later coverage-UI touch, mirroring the
  exclusions/fee-schedule cards); no edit/NEEDS_INFO.
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **74 pass** (+3:
  `CreatePriorAuthForm.test.tsx` ×2 — requests with the entered patient/plan/procedure/date (asserts the API
  body), blocks submit when required fields are missing; `PriorAuthorizationsPage.test.tsx` +1 — a requester role
  sees "New request", a reviewer does not). `npm run build` OK.
- **Verified — live in browser** (provider@northcare, backend + Vite): opened **Prior auth** → the **New request**
  form showed only the provider's **assigned** patients (relationship-gated: Fern Fixture + Sam Sample) and the
  plan options; filled Sam Sample · Standard PPO · 99213 · 2026-08-01 → **Request** → landed on the new
  **PA-0B49FAD7** detail (REQUESTED, "open-ended"), where as a provider the only action was **Cancel** (not
  Approve/Deny) — correct role gating.
- **Files:** +`src/priorauth/CreatePriorAuthForm.tsx` +`CreatePriorAuthForm.test.tsx`; changed `api/types.ts`,
  `api/client.ts`, `priorauth/usePriorAuth.ts`, `priorauth/PriorAuthorizationsPage.tsx`
  (+`PriorAuthorizationsPage.test.tsx`), `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 6, slice 3 ✅ (prior-authorization frontend — queue + detail + decision actions)
- **Why:** slices 1–2 were backend-only. This surfaces prior auth in the browser (a reviewer sees the queue and
  approves/denies) and renders slice 2's new `AUTH_REQUIRED` line outcome on the claim breakdown. **Frontend-only**
  — mirrors the claims UI (slice 5). Following the claims precedent, the request (create) form is a later slice.
- **New `src/priorauth/`:** `usePriorAuth.ts` (list/detail/history queries + a change-status mutation that
  invalidates the auth, its history, and the list), `transitions.ts` (client mirror of `PriorAuthTransitions` —
  Approve/Deny for reviewer/admin, Cancel for requester roles; reason required to deny/cancel), `statusColor.ts`,
  `PriorAuthorizationsPage.tsx` (the work queue: auth # · patient · procedure · requested-from · status) and
  `PriorAuthorizationDetailPage.tsx` (header + decision info + a status timeline + Approve/Deny/Cancel buttons
  with a reason prompt, optimistic-locked via the loaded `version`). Role-aware UI (the backend still enforces).
- **Surfacing slice 2:** added `AUTH_REQUIRED` to the frontend `LineOutcome` type and to
  `claims/statusColor.ts`'s `lineOutcomeColor` (a `warning` chip), so a claim with an auth-required line renders
  the outcome in the adjudication breakdown.
- **Plumbing:** `api/types.ts` (+`PriorAuthorizationStatus`, `PriorAuthorizationSummary`, `PriorAuthorization`,
  `PriorAuthStatusHistory`, `PriorAuthStatusChange`; `AUTH_REQUIRED` on `LineOutcome`), `api/client.ts`
  (`listPriorAuthorizations`, `getPriorAuthorization`, `getPriorAuthHistory`, `changePriorAuthStatus`), `App.tsx`
  (+2 routes), `layout/AppLayout.tsx` (a **Prior auth** nav button for staff roles).
- **Scope boundary:** no request (create) form yet (slice 4); no plan-prior-auth-requirement **admin UI** (a
  later coverage-UI touch); no NEEDS_INFO/edit.
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **71 pass** (+11:
  `PriorAuthorizationsPage.test.tsx` ×2 — lists auths with patient/procedure/status, empty state;
  `PriorAuthorizationDetailPage.test.tsx` ×5 — renders header + timeline, a reviewer sees Approve/Deny, a
  coordinator sees Cancel not Approve, approve sends the loaded version, deny prompts for a reason;
  `transitions.test.ts` ×4). `npm run build` OK (pre-existing chunk-size advisory).
- **Verified — live in browser** (reviewer@northcare, backend + Vite): the **Prior auth** queue listed the seeded
  REQUESTED auth (PA-…01, 99213); opened it → header (Standard PPO · service window) + **Approve/Deny**; clicked
  **Approve** → the chip flipped to **APPROVED** (green), the buttons disappeared (terminal), and the timeline
  showed `REQUESTED → APPROVED`. Also drove a $220 99214 claim for Sam Sample on 2025-11-01 (inside eligibility,
  outside his approved 2026 auth window) → adjudicated **AUTH_REQUIRED** → the claim's adjudication breakdown
  rendered the AUTH_REQUIRED line chip.
- **Files:** +`src/priorauth/` (statusColor, transitions, usePriorAuth, PriorAuthorizationsPage,
  PriorAuthorizationDetailPage) +3 test files; changed `api/types.ts`, `api/client.ts`, `App.tsx`,
  `layout/AppLayout.tsx`, `claims/statusColor.ts`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 6, slice 2 ✅ (prior authorization wired into the adjudication engine — AUTH_REQUIRED)
- **Why:** slice 1 gave a prior-auth lifecycle that was **inert** — approving/denying an auth affected nothing.
  This makes it matter: a claim line for a procedure that **requires** prior auth is denied unless an APPROVED
  authorization covers it — the actual "advanced claims" behavior and the §60 explainability payoff. Mirrors the
  plan-exclusions slice (Phase 5 slice 4). **Backend-only.**
- **The config (mirrors `plan_exclusion`):** a `plan_prior_auth_requirement` per `(coverage_plan, procedure)` —
  "this procedure requires prior auth under this plan." `GET/POST /api/v1/coverage-plans/{planId}/prior-auth-requirements`,
  `DELETE .../{id}`; reads same-tenant, **add/remove ORG_ADMIN**, duplicate 409, unknown/non-procedure code 400,
  cross-tenant plan secure 404.
- **The engine (`AdjudicationService`):** for a **covered** line whose procedure the covering plan requires prior
  auth for, it calls the new `PriorAuthorizationRepository.existsApprovedCovering(org, patient, plan, system, code,
  serviceDate)` — an APPROVED `prior_authorization` whose window covers the claim's service date. **Missing /
  denied / wrong-date → the new `LineOutcome.AUTH_REQUIRED`**: allowed 0, plan pays 0, member owes the charge, and
  (like an exclusion) it skips the cost-sharing math so it does not consume the deductible/OOP. The claim is still
  `ADJUDICATED` (a mix of COVERED / NOT_COVERED / AUTH_REQUIRED lines). Exclusion takes precedence over an auth
  requirement.
- **Interplay with re-adjudication (slice 11):** approving a covering authorization and re-running
  `POST .../adjudicate` writes a new version where the line flips to COVERED — a denied-for-auth line becomes
  payable without editing the claim.
- **Migrations:** `V26__plan_prior_auth_requirement.sql` (the config table, mirroring `V23__plan_exclusion`) +
  `V27__adjudication_line_auth_required.sql` (extend the `adjudication_line.outcome` CHECK to add `AUTH_REQUIRED`).
- **Seeder:** marks **99214** as requiring prior auth on the seeded PPO (deliberately NOT 99213/80053 — the
  accumulator/fee-schedule tests assert exact amounts for those on the seeded PPO; lesson from slice 9).
- **Files:** +`V26…`, +`V27…`, +`coverage/PlanPriorAuthRequirement{,Repository,Dto,Service,Controller}.java` +
  `AddPriorAuthRequirementRequest.java` (6), +3 test classes; changed `adjudication/LineOutcome.java`,
  `adjudication/AdjudicationService.java`, `priorauth/PriorAuthorizationRepository.java`
  (`existsApprovedCovering`), `devdata/DevDataSeeder.java`, `CLAUDE.md`, `docs/PROGRESS.md`.
- **Scope boundary (later slices):** the prior-auth **frontend**; NEEDS_INFO; auth quantity/units; auto-creating
  an auth request from a denied line; then referrals / provider network / anomaly signals / appeals.
- **Verified — automated:** `./mvnw -B clean verify` → **289 pass** (+10: `PlanPriorAuthRequirementRepositoryTest`
  ×2; `PlanPriorAuthRequirementApiIntegrationTest` ×5 — add/list/remove, non-admin 403, dup 409, unknown 400,
  cross-tenant 404; `AdjudicationPriorAuthApiIntegrationTest` ×3 — a required line with no auth is AUTH_REQUIRED
  and doesn't touch the deductible (a second covered claim consumes the full $1,500 → plan $380); approving a
  covering auth + re-adjudicating flips it to COVERED (v2); an auth outside the service window doesn't apply). The
  accumulator/fee-schedule tests on the seeded PPO stay green (99214 requirement doesn't touch them).
- **Verified — live** (fresh `db-reset` + backend, curl, seeded PPO requires 99214): created a $200 99214 claim →
  submit → accept → adjudicate → **AUTH_REQUIRED** (member $200, plan $0); requested + **approved** a covering
  99214 authorization; **re-adjudicated** → **v2 COVERED** (the line's $175 now runs through the deductible). The
  seeded PPO's `prior-auth-requirements` listed 99214.

### 2026-09-15 — Phase 6, slice 1 ✅ (prior authorization — request intake + decision lifecycle)
- **Why:** Phase 6 (advanced claims) begins. Of its seven areas (provider network, prior auth, referrals,
  anomaly signals, manual review, appeals, reprocessing), **prior authorization** is the most self-contained —
  it needs nothing new from Phase 6, only patients + procedure codes + coverage plans (all present), and reuses
  every mastered pattern. It's the canonical entry: before a costly service, a provider requests pre-approval and
  a reviewer approves/denies. **Backend-only.**
- **The aggregate:** a `prior_authorization` — **top-level, gated by its patient** (like `claim`, not nested) —
  carrying only coded, claim-relevant data (a single procedure code + a service window + the coverage plan), so
  like a claim it is **not consent field-masked**. Server-allocated `auth_number` (`PA-XXXXXXXX`, unique per
  tenant). Plus an append-only `prior_authorization_status_history` child (null → REQUESTED on creation).
- **State machine** — new pure policy `PriorAuthTransitions` (the 4th pure-policy exemplar after
  `RequestTransitions`/`ClaimTransitions`/`ConsentPolicy`): REQUESTED → APPROVED/DENIED (**CLAIMS_REVIEWER**/
  ORG_ADMIN — a decision stamps `decidedBy`/`decidedAt`) or CANCELLED (requester roles PROVIDER-assigned/
  CARE_COORDINATOR/ORG_ADMIN); APPROVED/DENIED/CANCELLED terminal, reason required to deny/cancel. Same check
  order as the claim machine (exists → legal move → role → reason → optimistic `expectedVersion`), status change
  + history row in one `@Transactional` (§31.6).
- **Authorization (§21):** tenant → role → object/relationship (`PatientAccessGuard` via the auth's patient) →
  secure 404. Requesting also validates the procedure (unknown/non-procedure → 400) and the coverage plan (not
  in-tenant → 400). List scoping reuses `accessiblePatientIdsIfGated` (provider → assigned; broad roles → tenant
  work queue).
- **Endpoints (new `com.healthcloud.priorauth` package):** `GET/POST /api/v1/prior-authorizations`,
  `GET .../{id}`, `PATCH .../{id}/status`, `GET .../{id}/history`. Thin controller; logic in
  `PriorAuthorizationService`.
- **Migration `V25__prior_authorization.sql`:** two tables, FK-with-org to `patient` and `coverage_plan`, FK to
  `medical_code`, `UNIQUE(org, auth_number)` + `UNIQUE(id, org)` (for the child FK-with-org), a status CHECK and
  a service-window CHECK.
- **Seeder:** requests one REQUESTED prior auth (99213 under the PPO) for the first patient per org, mirroring
  the sample DRAFT claim.
- **Scope boundary (later Phase-6 slices):** wiring an APPROVED auth into adjudication (a claim line that requires
  prior auth); NEEDS_INFO step; multi-procedure lines; a consent-masked clinical justification; expiry/effective
  enforcement; a **frontend** UI; and the other Phase-6 areas (referrals, provider network, anomaly signals,
  manual review, appeals, reprocessing).
- **Verified — automated:** `./mvnw -B clean verify` → **279 pass** (+21: `PriorAuthTransitionsTest` ×5 pure
  policy; `PriorAuthorizationRepositoryTest` ×4 — tenant scoping, auth-number uniqueness per tenant + reuse
  across tenants, history order, procedure FK; `PriorAuthorizationApiIntegrationTest` ×12 — 401; request creates
  REQUESTED; reviewer approves (stamps decidedBy); deny-without-reason 400 then deny with reason; non-reviewer
  decide 403; requester cancels; unknown code 400; unknown plan 400; illegal transition 409; stale version 409;
  provider sees only assigned patients' auths + secure 404; cross-tenant secure 404). `DevDataSeederTest` still
  green (seeder change safe).
- **Verified — live** (fresh `db-reset` + backend, curl): the seeded `PA-…01` showed REQUESTED in the reviewer's
  queue; a coordinator created `PA-8F1B2017` (plan name resolved) → coordinator APPROVE **403 ACCESS_DENIED** →
  reviewer DENY-without-reason **400 VALIDATION_FAILED** → reviewer APPROVE **200 APPROVED** (decidedBy stamped)
  → re-approve **409 INVALID_STATE_TRANSITION** → history listed null→REQUESTED then REQUESTED→APPROVED
  chronologically → Green Valley admin read **404 NOT_FOUND**.
- **Files:** +`V25__prior_authorization.sql`, +`priorauth/` package (11: status enum, entity, history entity,
  `PriorAuthTransitions`, 2 repositories, 3 DTOs, 2 request records, service, controller — 13 classes), +3 test
  classes; changed `devdata/DevDataSeeder.java`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Tooling: HealthCloud-specific code-reviewer + security-reviewer subagents ✅
- **Why:** `docs/PLAN.md` Part C.1 (#5) / C.3 called for **project-specific** review subagents at Phase 3 —
  the built-in `/code-review` and `/security-review` are generic, and HealthCloud's real risks are tenant
  isolation, the `PatientAccessGuard` object/relationship gate, consent+purpose, field masking, one-transaction
  history, and financial-accumulator correctness. Now that the MVP (Phase 0–5) is feature-complete, these give a
  repeatable, codebase-aware review gate for every future slice.
- **What:** two agent definitions under `.claude/agents/`, grounded in the actual code (I read
  `PatientAccessGuard`, `UserContextAccessor`, `AdjudicationService`, `BenefitAccumulatorRepository`, the
  controllers, and `transitions.ts` first so the checklists match how we really built it):
  - **`code-reviewer.md`** — bugs + quality vs the project's invariants: tenant scoping (no bare `findById`, org
    from context never the client), thin controllers, §21 authz layering, one-transaction + status/history writes,
    engine-command-owned statuses (ADJUDICATED/ASSIGNED not bare status changes), pure policy classes, optimistic
    `expectedVersion` + row-lock concurrency, `BigDecimal` money rules, the supersede/versioning pattern, Flyway
    migration discipline, plus frontend rules (role-UI-is-not-security, `MemoryRouter` tests, RHF/Zod 3-generic,
    query invalidation) and the `.gitignore`-anchoring gotcha. Outputs severity-grouped findings + a verdict.
  - **`security-reviewer.md`** — high-confidence, exploitable issues in the real threat model: tenant isolation,
    the object/relationship gate on every patient-linked endpoint, secure-404-not-403, role gates, consent/purpose
    bypass, field-masking leaks, native-SQL parameterization / path traversal, document scan-status withholding,
    CSRF/session/`SecurityConfig` widening, PHI/bytes in logs, and financial double-apply. Tuned for low false
    positives (>80% confidence; excludes DoS/dep-CVEs/theoretical races), and aware this is synthetic-data-only.
- **Read-only by design:** both get `Read, Grep, Glob, Bash`; **no `Edit`/`Write`** so they can't change code.
  `Bash` is constrained in each agent's instructions to inspection only (`git diff/log/show/status`, `grep`, `cat`)
  — never mutating the repo/working tree/git state. They report; the human applies fixes.
- **Test-driven both** (checklists run inline, since agent files are only spawnable in a *new* session — Claude
  Code loads `.claude/agents/*.md` at startup): the code-reviewer on slice 12 (frontend) → clean, surfaced 3
  accurate nits (redundant TanStack versions-invalidation via prefix matching; double-fetch of the latest
  adjudication; silent-null on a versions error) and a commit verdict; the security-reviewer on slice 11 (backend)
  → **no High/Medium findings**, confirmed every new query is org-scoped, the new `/adjudication/versions`
  endpoint routes through the tenant→role→patient gate, and the re-adjudication accumulator reversal is sound under
  the row lock with a `UNIQUE(org, claim_id, version)` backstop (the concurrent-re-adjudication race is backstopped,
  not corrupting — noted as robustness, not a vuln). Neither manufactured issues.
- **How to use:** in a fresh session, `use the code-reviewer subagent on my current changes` /
  `run the security-reviewer` (they pull their own `git diff main...HEAD`). Realizes the Phase-3 custom review
  subagents from PLAN.md Part C; also noted in `CLAUDE.md` (Custom tooling).
- **Files:** +`.claude/agents/code-reviewer.md`, +`.claude/agents/security-reviewer.md`, `docs/PROGRESS.md`.
  (Committed `877e7c0`, pushed.)

### 2026-09-15 — Phase 5, slice 12 ✅ (adjudication version history + re-adjudicate in the claims UI)
- **Why:** slice 11 added re-adjudication + a versions endpoint, but the UI only showed the current breakdown and
  an Adjudicate button on ACCEPTED claims. This surfaces re-adjudication and the version history in the browser —
  the last deferred piece of the Phase-5 frontend. **Frontend-only** (the `POST .../adjudicate` re-run and
  `GET .../adjudication/versions` endpoints already exist from slice 11).
- **Re-adjudicate button** on the claim detail page: shown on an ADJUDICATED claim to CLAIMS_REVIEWER/ORG_ADMIN
  (client mirror `canReadjudicate` in `transitions.ts`), reusing the existing `useAdjudicate` mutation (the same
  backend command handles first-vs-re-adjudication) with a caption "Re-runs the engine and records a new version".
- **Current version label:** the Adjudication card title is now `Adjudication — version N` from
  `adjudicationVersion`.
- **Version history card:** appears when ADJUDICATED and there's more than one version — a table of every version
  (version #, outcome chip, plan, plan-paid, member, adjudicated-at) newest-first, from a new
  `useAdjudicationVersions` hook; hidden for a single-version claim (the breakdown card covers it).
- **Plumbing:** `api/client.ts` gained `getAdjudicationVersions`; `useClaims.ts` gained `useAdjudicationVersions`
  + `adjudicationVersionsKey`, and `useAdjudicate` now also invalidates the versions query so the history refreshes
  after a re-adjudication.
- **Scope boundary:** the history shows per-version summary totals, not each version's full per-line breakdown
  expanded (the current version's full breakdown is the Adjudication card); no confirm dialog (re-adjudication is
  additive/non-destructive).
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **60 pass** (+4 in
  `ClaimDetailPage.test.tsx`: a reviewer re-adjudicates an ADJUDICATED claim, a non-reviewer sees no Re-adjudicate,
  the version history lists >1 version, it hides for a single version; plus the breakdown test now asserts the
  version label). `npm run build` OK. Backend untouched (258 green).
- **Verified — live in browser** (reviewer@northcare, fresh `db-reset` + backend + Vite): drove the seeded claim
  to ADJUDICATED (v1) via curl; opened it → "Adjudication — version 1" + a **Re-adjudicate** button; clicked it →
  the card became "version 2" and a **Version history** card listed both versions newest-first (both $190.00 —
  identical, confirming the reversal for an unchanged re-run).
- **Files:** changed `api/client.ts`, `claims/useClaims.ts`, `claims/transitions.ts`,
  `claims/ClaimDetailPage.tsx` (+`ClaimDetailPage.test.tsx`), `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 11 ✅ (re-adjudication versioning — the engine's last core deferral)
- **Why:** an adjudicated claim was frozen (a second `adjudicate` → 409). After a fee-schedule/exclusion/eligibility
  change you need to re-run adjudication. This lets an ADJUDICATED claim be re-adjudicated, producing a **new
  immutable version** while every prior version is retained — the "immutable adjudication versions" the
  source-of-truth calls for. **Backend-only, no migration** (the `adjudication_version` column + `UNIQUE(org,
  claim_id, version)` existed from slice 1).
- **Trigger (reuse `POST /api/v1/claims/{id}/adjudicate`):** ACCEPTED → first adjudication (v1), advance to
  ADJUDICATED; ADJUDICATED → re-adjudicate (v = prior max + 1), status unchanged; any other status → 409.
- **Accumulator reversal (the careful part):** on re-adjudication the engine first **backs out the prior latest
  version's contribution** — the deductible applied and the covered lines' member responsibility, read back from
  that version's own immutable line snapshot — from its `(plan, year)` accumulator under the lock, then recomputes
  the new version against the corrected remaining deductible/OOP and adds its contribution. So re-adjudicating a
  claim unchanged yields identical amounts (proves no double-count); `BenefitAccumulator.subtract(...)` clamps at 0.
  **Honest limitation:** it reverses/recomputes *this claim only*, not other claims in the same benefit year.
- **Reads:** `GET .../adjudication` now returns the **latest** version (repo switched to
  `findFirstBy…OrderByAdjudicationVersionDesc` — the old single-row finder would throw once >1 version exists);
  **new** `GET .../adjudication/versions` returns all versions newest-first. No status-history row on
  re-adjudication (status unchanged) — the immutable adjudication row (who/when/version/correlationId) is the record.
- **Files:** `AdjudicationRepository` (latest + all-versions finders), `BenefitAccumulator` (`subtract`),
  `AdjudicationService` (version selection, `reversePriorContribution`, first-vs-re-adjudication status handling,
  `getByClaim` → latest, `listVersions`), `AdjudicationController` (versions endpoint).
- **Scope boundary:** backend-only (surfacing version history in the claims UI is a later slice); no Idempotency-Key
  (each call is an intentional new version); no retroactive re-adjudication of other claims in the year.
- **Verified — automated:** `./mvnw -B clean verify` → **258 pass** (+3 net: new
  `AdjudicationReadjudicationApiIntegrationTest` ×3 — re-adjudicate after a fee-schedule change writes v2 & retains
  v1 (latest read + versions list), re-adjudicating unchanged doesn't double-count the deductible, a denied claim
  flips to covered after enrollment; the old "adjudicated only once → 409" test rewritten to assert v2; the repo
  test switched to the latest finder). Frontend untouched (56 green).
- **Verified — live** (admin@northcare, fresh `db-reset` + backend): adjudicated the seeded claim → **v1 allowed
  190.00**; priced 99213 at $100 on the plan, re-adjudicated → **v2 allowed 140.00**; `GET .../adjudication` = v2;
  `GET .../adjudication/versions` listed both, newest first.
- **Files:** changed `adjudication/AdjudicationRepository.java`, `adjudication/BenefitAccumulator.java`,
  `adjudication/AdjudicationService.java`, `adjudication/AdjudicationController.java`,
  `adjudication/AdjudicationRepositoryTest.java`, `adjudication/AdjudicationApiIntegrationTest.java`,
  +`adjudication/AdjudicationReadjudicationApiIntegrationTest.java`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 10 ✅ (fee-schedule admin UI — pricing procedures in the browser)
- **Why:** slice 9 added the fee schedule but it could only be managed via the API. This adds a **Fee schedule**
  card to the coverage-plan detail page so an admin can price procedures in the browser, mirroring the Exclusions
  card and reusing the same `MedicalCodePicker`. **Frontend-only** — the `GET/POST/DELETE
  /api/v1/coverage-plans/{id}/fee-schedule` endpoints already exist (slice 9).
- **`FeeScheduleCard`** on `CoveragePlanDetailPage.tsx` (after the Exclusions card): a table of Code / System /
  **Allowed** (via the `money()` helper) + a Remove action for ORG_ADMIN, and an add row — the `MedicalCodePicker`
  plus an "Allowed amount" number field (Add disabled until a code and a valid non-negative amount are entered).
  Server errors (409 duplicate, 400 unknown code / negative amount) surface via `ApiClientError` + correlationId,
  same `reportError` pattern as the exclusions card. Reads open to same-tenant; add/remove ORG_ADMIN only
  (role-aware UI — the backend enforces it).
- **New hooks** (`src/coverage/useCoverage.ts`): `useFeeSchedule` / `useAddFeeSchedule` / `useRemoveFeeSchedule`
  keyed by `['coverage-plans', id, 'fee-schedule']` (invalidated on add/remove).
- **Plumbing:** `api/types.ts` gained `PlanFeeScheduleEntry` + `AddFeeScheduleRequest`; `api/client.ts` gained
  `listFeeSchedule` / `addFeeSchedule` / `removeFeeSchedule`. No new route/nav — the card lives on the existing
  plan detail page.
- **Scope boundary:** no edit-in-place (remove + re-add, like exclusions — no backend update endpoint); adjudication
  behavior unchanged (slice 9 already applied the fee schedule).
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **56 pass** (+3 in
  `CoveragePlanDetailPage.test.tsx`: renders an entry with its allowed amount (non-admin sees no controls), an
  admin adds via picker + amount, an admin removes). `npm run build` OK. Backend untouched (255 green).
- **Verified — live in browser** (admin@northcare, fresh `db-reset` + backend + Vite): opened Standard PPO → the
  Fee schedule card showed the seeded **80053 · $40.00**; added **99213 · $130.00** via the picker + amount → it
  appeared immediately (list invalidation); **Remove** → back to just 80053.
- **Files:** changed `api/types.ts`, `api/client.ts`, `coverage/useCoverage.ts`,
  `coverage/CoveragePlanDetailPage.tsx` (+`CoveragePlanDetailPage.test.tsx`), `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 9 ✅ (fee-schedule allowed amounts — allowed is no longer just the charge)
- **Why:** the engine used `allowed = charge` everywhere — a provider could bill any amount and the plan/member
  split off the full charge. This adds a per-plan fee schedule so `allowed = min(charge, fee-schedule amount)`
  (the standard in-network model; `charge − allowed` is a provider write-off no one pays). Every downstream amount
  (copay, deductible, coinsurance, OOP, plan/member split) already keys off allowed, so the whole computation
  becomes realistic. **Backend-only** — mirrors `plan_exclusion` (slice 4) almost exactly.
- **Migration `V24__plan_fee_schedule.sql`:** `plan_fee_schedule` per `(coverage_plan_id, code_system, code)` →
  `allowed_amount` (+ non-negative CHECK), tenant key, FK-with-org to `coverage_plan`, FK to `medical_code`,
  `UNIQUE(org, plan, code_system, code)` — identical shape to `plan_exclusion`.
- **Coverage package:** `PlanFeeScheduleEntry`, `PlanFeeScheduleRepository`, `PlanFeeScheduleDto`,
  `AddFeeScheduleRequest` (procedureCode + allowedAmount), `PlanFeeScheduleService` (list open to same-tenant;
  add/remove **ORG_ADMIN**; catalog-validated code → 400; duplicate → 409; cross-tenant plan → secure 404),
  `PlanFeeScheduleController` — `GET/POST /api/v1/coverage-plans/{planId}/fee-schedule`, `DELETE .../{entryId}`.
- **Calculator:** `LineCharge` gains `allowedAmount` (with a 2-arg convenience ctor = charge, so existing call
  sites/tests and the no-entry fallback are unchanged); the line's `allowed = min(allowedAmount, charge)`.
- **Engine (`AdjudicationService`):** loads the covering plan's fee schedule into a `code → allowed` map and
  resolves each covered line's allowed from it (else the charge). No change to the exclusion/denial/accumulator paths.
- **Seeder:** prices **80053 at $40.00** on the seeded PPO (billed $45.50 on the seeded claim) so a demo shows
  allowed < charge; the 99213 line stays unpriced → allowed = charge, so one adjudication shows both paths. (Chose
  80053 deliberately: the accumulator test asserts exact 99213 amounts on the seeded PPO, so pricing 99213 there
  would have broken it.)
- **Verified — automated:** `./mvnw -B clean verify` → **255 pass** (+10: 3 calculator — fee-schedule split,
  allowed capped at charge, no-entry fallback; 6 `PlanFeeScheduleApiIntegrationTest` — add/list/remove, non-admin
  403, duplicate 409, unknown 400, negative amount 400, cross-tenant 404; 1 `AdjudicationFeeScheduleApiIntegrationTest`
  — a priced line is allowed the fee amount and an unpriced line falls back to charge). Frontend untouched (53 green).
- **Verified — live** (admin@northcare, fresh `db-reset` + backend): adjudicated the seeded claim → the 80053 line
  came back `chargeAmount 45.50 / allowedAmount 40.00` (the $5.50 write-off) while 99213 stayed `150.00 / 150.00`;
  `totalAllowedAmount 190.00` vs `totalChargeAmount 195.50` — both paths in one decision.
- **Scope boundary:** no fee-schedule **admin UI** yet (slice 10, mirroring the exclusions card); no member
  balance-billing of the write-off (in-network model); per-plan (not org-level) fee schedules.
- **Files:** +`V24__plan_fee_schedule.sql`, +`coverage/PlanFeeScheduleEntry|Repository|Dto|Service|Controller.java`,
  +`AddFeeScheduleRequest.java`, +2 tests (`coverage/PlanFeeScheduleApiIntegrationTest`,
  `adjudication/AdjudicationFeeScheduleApiIntegrationTest`); changed `AdjudicationCalculator.java`,
  `AdjudicationService.java`, `AdjudicationCalculatorTest.java`, `DevDataSeeder.java`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 8 ✅ (patient eligibility enrollment UI — enroll a patient in a plan in the browser)
- **Why:** eligibility existed only via the API (the seeder enrolled the first patient); a coordinator couldn't
  enroll a patient in the browser, and the adjudication engine's `findCovering(serviceDate)` input had no UI.
  This closes the last browser gap in the coverage/eligibility story. **Frontend-only** — no backend/migration
  change (the `GET/POST /api/v1/patients/{id}/eligibility` endpoints already existed from Phase 4 slice 6).
- **`EligibilityCard`** on the patient detail page (after Care team, before Documents), mirroring the existing
  cards: lists the patient's eligibility (plan name · member ID · effective from · effective to, showing
  "Open-ended" when the period has no end), and — for **CARE_COORDINATOR/ORG_ADMIN** — an **Enroll in a plan**
  form (RHF + Zod mirroring `EnrollEligibilityRequest`: plan select from `useCoveragePlans`, member ID, coverage
  start required, coverage end optional). The in-tenant-plan (400) and non-overlap (409) checks are the server's,
  surfaced via `ApiClientError`. Role-aware UI only — the backend enforces the write gate.
- **New `src/coverage/useEligibility.ts`:** `useEligibility(patientId)` + `useEnrollEligibility(patientId)`
  (invalidates the `['patient', id, 'eligibility']` key on success so the list refreshes live).
- **Plumbing:** `api/types.ts` gained `PatientEligibility` + `EnrollEligibilityRequest`; `api/client.ts` gained
  `listEligibility` + `enrollEligibility`. No new route/nav — the card lives on the existing patient detail page.
- **Scope boundary:** no edit/terminate-eligibility UI (the backend has no update endpoint yet — still deferred).
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **53 pass** (+3 in
  `PatientDetailPage.test.tsx`: renders an eligibility row (non-write role sees no form), a coordinator enrolls
  via the form, a provider sees the row but no enroll form). `npm run build` OK. Backend untouched (245 green).
- **Verified — live in browser** (coordinator@northcare, fresh `db-reset` + backend + Vite): enrolled Fern
  Fixture (NC-0002) in the HDHP via curl (201; overlap re-enroll → 409), then opened her detail page → the
  Coverage eligibility card showed the HDHP row (Open-ended); enrolled her in **Standard PPO** for 2020-01-01 →
  2020-12-31 through the UI form → the new row appeared immediately (list invalidation).
- **Files:** +`src/coverage/useEligibility.ts`; changed `api/types.ts`, `api/client.ts`,
  `patients/PatientDetailPage.tsx` (+`PatientDetailPage.test.tsx`), `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 7 ✅ (coverage-plan admin UI — plans + exclusions in the browser)
- **Why:** coverage plans and exclusions existed only via the API (the seeder set them up); an admin couldn't
  manage benefits in the browser. This adds a Coverage area and **reuses the slice-6 `MedicalCodePicker`** for
  excluding procedures. **Frontend-only** — no backend/migration change.
- **New `src/coverage/`:** `useCoverage.ts` (plans list/detail + create; exclusions list/add/remove hooks),
  `CoveragePlansPage.tsx` (a list — code/name/type/deductible/coinsurance-as-% /copay/OOP — plus a New-plan form
  for ORG_ADMIN, RHF+Zod mirroring `CreateCoveragePlanRequest`; input≠output form generics as in slice 6),
  `CoveragePlanDetailPage.tsx` (the plan parameters + an **Excluded procedures** card: list + Remove and an Add
  control using the code picker, all ORG_ADMIN; duplicate → 409 and unknown code → 400 surface via `ApiClientError`).
- **Plumbing:** `api/types.ts` gained `PlanType`, `CoveragePlan`, `CreateCoveragePlanRequest`, `PlanExclusion`,
  `AddPlanExclusionRequest`; `api/client.ts` gained `listCoveragePlans`, `getCoveragePlan`, `createCoveragePlan`,
  `listExclusions`, `addExclusion`, `removeExclusion`; `App.tsx` +2 routes; `AppLayout` a **Coverage** nav button
  for staff roles. Reads are open to same-tenant staff; writes are ORG_ADMIN (role-aware UI; backend enforces).
- **Scope boundary:** no **patient eligibility enrollment** UI yet (slice 8); no diagnosis picker; no fee schedule.
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **50 pass** (+5:
  `CoveragePlansPage.test.tsx` ×2 — lists plans with coinsurance as %, an admin creates a plan (a non-admin sees
  no form); `CoveragePlanDetailPage.test.tsx` ×3 — renders params + exclusions, an admin adds via the picker and
  removes, a non-admin sees no add/remove). `npm run build` OK. Backend untouched (245 tests still green).
- **Verified — live in browser** (admin@northcare, fresh `db-reset` + backend + Vite): Coverage listed the two
  seeded plans (coinsurance shown 10% / 20%); created **NC-EPO-1 "Basic EPO"** via the form → appeared in the
  list; opened Standard PPO → added an exclusion for **80053** via the picker (listed 80053 · CPT) → **Remove** →
  back to "No exclusions".
- **Files:** +`src/coverage/` (useCoverage, CoveragePlansPage, CoveragePlanDetailPage) +2 test files; changed
  `api/types.ts`, `api/client.ts`, `App.tsx`, `layout/AppLayout.tsx`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 6 ✅ (claim-creation form + medical-code picker — the claims UI is self-sufficient)
- **Why:** slice 5 could drive a claim but not create one (only the seeded DRAFT claim existed). This adds a
  create form so a claim can be made in the browser, and introduces a reusable procedure-code picker backed by
  the Phase-4 catalog search. **Frontend-only** — no backend/migration change.
- **`MedicalCodePicker`** (`src/claims/`): an MUI **Autocomplete** — freeSolo + debounced — that searches the
  catalog (`GET /api/v1/medical-codes?q=`) as you type and lists **PROCEDURE** codes (CPT/HCPCS; diagnoses
  filtered out). freeSolo means the value is always the code string (a user can type a raw code), and the
  backend still validates it. Reusable by any future code field.
- **`CreateClaimForm`** on `ClaimsPage` (create roles PROVIDER/CARE_COORDINATOR/ORG_ADMIN, mirroring the
  requests UI): RHF + Zod + `useFieldArray` — patient select, service date (defaults to today; Zod enforces
  ≤ today to mirror the backend `@PastOrPresent`), and 1+ lines (picker + units + charge, add/remove). On
  create it navigates to the new claim's detail. **Gotcha recorded:** the coercing Zod schema makes input ≠
  output types, so the form uses `useForm<z.input, unknown, z.output>` (added to CLAUDE.md).
- **Plumbing:** `api/types.ts` gained `MedicalCode`, `CreateClaimLine`, `CreateClaimRequest`; `api/client.ts`
  gained `createClaim` + `searchMedicalCodes`; `useClaims.ts` gained `useCreateClaim`.
- **Scope boundary:** procedures only (no diagnosis picker); no exclusions/coverage-plan/eligibility admin UI —
  later slices.
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **45 pass** (+5:
  `CreateClaimForm.test.tsx` ×2 — creates a claim with the entered patient/code/charge, and blocks submit with no
  patient; `MedicalCodePicker.test.tsx` ×2 — searches the catalog + filters to procedures, reports the typed code
  via freeSolo; `ClaimsPage.test.tsx` +1 — a create role sees the form, a reviewer does not). `npm run build` OK.
  Backend untouched.
- **Verified — live in browser** (coordinator@northcare, fresh `db-reset` + backend + Vite): opened Claims → the
  New claim form → selected Sam Sample, typed `99213` in the picker → the catalog returned "99213 —
  Office/outpatient visit… (CPT)", selected it, entered charge $150 → **Create claim** → landed on the new
  **CLM-…** detail (DRAFT, one 99213 line ×1, total $150.00).
- **Files:** +`src/claims/MedicalCodePicker.tsx`, +`src/claims/CreateClaimForm.tsx`, +3 test files; changed
  `api/types.ts`, `api/client.ts`, `useClaims.ts`, `ClaimsPage.tsx`, `ClaimsPage.test.tsx`, `CLAUDE.md`,
  `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 5 ✅ (the claims & adjudication frontend — the money engine, visible)
- **Why:** all of Phase 4/5 was backend-only. This surfaces the claims work queue, the claim lifecycle, and the
  adjudication breakdown in the browser — the portfolio payoff and the §60 "how every amount was computed" proof,
  made visible. **Frontend-only** — no backend/migration change.
- **New `src/claims/`:** `useClaims.ts` (list/detail/history/adjudication queries + change-status/adjudicate
  mutations; the adjudication query is enabled only when the claim is ADJUDICATED so a pre-adjudication claim
  doesn't 404), `transitions.ts` (client mirror of `ClaimTransitions`; ADJUDICATED is not a status button —
  `canAdjudicate` gates the dedicated Adjudicate command, like Assign on a request), `statusColor.ts`,
  `ClaimsPage.tsx` (the work queue: claim #, patient, service date, total charge, status), `ClaimDetailPage.tsx`
  (header + lines table + status timeline + lifecycle buttons with a reason prompt for reject/cancel + Adjudicate
  + the adjudication breakdown card: outcome, plan, per-line allowed/copay/deductible/coinsurance/OOP/plan-paid/
  member + totals). Role-aware UI (backend still enforces).
- **Plumbing:** `api/types.ts` gained the Claim + Adjudication types; `api/client.ts` gained `listClaims`,
  `getClaim`, `getClaimHistory`, `changeClaimStatus`, `adjudicateClaim`, `getAdjudication`; `App.tsx` gained the
  two routes; `AppLayout` enabled the **Claims** nav button (was a disabled placeholder) for
  provider/coordinator/reviewer/admin.
- **Scope boundary:** no claim-creation form (needs a medical-code picker) and no exclusions/coverage-plan admin
  UI — later slices. The seeded DRAFT claim is the demo entry point (an ORG_ADMIN can drive submit→accept→adjudicate).
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **40 pass** (+7:
  `ClaimsPage.test.tsx` ×2 — lists claims with patient name/status, empty state; `ClaimDetailPage.test.tsx` ×5 —
  renders header + lines + a coordinator's Submit; a wrong role sees no lifecycle actions; a reviewer adjudicates
  an ACCEPTED claim; the breakdown shows once adjudicated; submit sends the loaded version). `npm run build` OK
  (pre-existing chunk-size advisory only). Backend untouched (245 tests still green from slice 4).
- **Verified — live in browser** (admin@northcare, fresh `db-reset` + backend + Vite): opened the seeded DRAFT
  claim CLM-… → **Submit → Accept → Adjudicate** → the claim flipped to ADJUDICATED and the **Adjudication card**
  rendered the Standard PPO breakdown — 99213 COVERED (allowed $150, copay $25, deductible $125, member $150) +
  80053 COVERED (member $45.50), totals plan **$0.00** / member **$195.50** (all under the fresh deductible).
- **Files:** +`src/claims/` (5: statusColor, transitions, useClaims, ClaimsPage, ClaimDetailPage) + 2 test files;
  changed `api/types.ts`, `api/client.ts`, `App.tsx`, `layout/AppLayout.tsx`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 4 ✅ (plan exclusions — non-covered procedures)
- **Why:** a real plan doesn't cover everything. This lets a plan exclude specific procedure codes; an excluded
  line adjudicates NOT_COVERED even under coverage, enriching the explainable outcome with *why* a line wasn't
  paid. Backend-only.
- **The model:** exclusions are checked per line **before** the cost-sharing math. An excluded procedure →
  NOT_COVERED (allowed 0, plan 0, member owes the charge) and, because it skips the calculator, it does **not**
  consume the deductible or out-of-pocket max. Covered lines flow through the calculator as before. A claim with
  excluded lines is still `ADJUDICATED` (coverage existed) — a mix of COVERED and NOT_COVERED lines;
  `DENIED_NO_ELIGIBILITY` stays reserved for the no-coverage case. Header totals: covered amounts from the math
  plus excluded charges added to the member. `LineOutcome.NOT_COVERED` reused.
- **Migration `V23__plan_exclusion.sql`:** `plan_exclusion` — coverage_plan, code_system, code, created_by;
  composite FK-with-org to `coverage_plan`, FK `(code_system, code)` → `medical_code`;
  `UNIQUE(org, coverage_plan_id, code_system, code)`. Plan config (tenant-owned, not patient-scoped).
- **New `coverage` classes:** `PlanExclusion` entity, `PlanExclusionRepository` (org-scoped finders + `existsBy…`),
  `PlanExclusionDto`, `AddPlanExclusionRequest`, `PlanExclusionService` (add/remove ORG_ADMIN; procedure resolved
  + validated against the catalog, unknown/non-procedure → 400; duplicate → 409; plan not in tenant → secure 404),
  `PlanExclusionController` (`GET/POST /api/v1/coverage-plans/{planId}/exclusions`, `DELETE .../{id}`). Modified
  `AdjudicationService` — partitions claim lines into covered vs excluded, runs the calculator on covered only,
  builds NOT_COVERED lines for excluded, and assembles header totals.
- **Verified — automated:** `./mvnw -B clean verify` → **245 pass** (+8: `PlanExclusionRepositoryTest` ×2 —
  tenant scoping, list-by-plan, existence key; `PlanExclusionApiIntegrationTest` ×5 — admin add/list/remove, a
  non-admin → 403, duplicate → 409, unknown code → 400, cross-tenant plan → secure 404;
  `AdjudicationExclusionApiIntegrationTest` ×1 — a mixed claim: 99213 COVERED + 80053 NOT_COVERED, header totals,
  and a follow-up claim proving the excluded charge did not consume the deductible). Each API test creates its own
  plan so exclusions never contaminate the shared seeded plans.
- **Verified — live:** `db-reset` → fresh backend → admin created a PPO plan, excluded 80053 (duplicate → 409,
  unknown code → 400), enrolled a patient, and adjudicated a mixed claim → 99213 **COVERED** (member $150, plan
  $0), 80053 **NOT_COVERED** (member owes the full $45.50, allowed/plan $0); header allowed $150.00, member
  $195.50, plan $0.00.
- **Files:** +`V23__plan_exclusion.sql`, +`coverage/` (6: entity, repository, DTO, request, service, controller),
  +3 test classes; changed `AdjudicationService`, `CLAUDE.md`, `docs/PROGRESS.md`. **Seeder untouched.**

### 2026-09-15 — Phase 5, slice 3 ✅ (out-of-pocket-max enforcement — completes the core adjudication math)
- **Why:** slice 2 started tracking `out_of_pocket_met` but did not enforce the cap. This consumes it: once a
  member's cumulative cost-sharing for the year reaches the plan's out-of-pocket maximum, the plan pays 100% of
  everything beyond it. With this the core money math — eligibility, deductible carry-over, copay, coinsurance,
  OOP max — is complete (a natural MVP milestone).
- **The model:** OOP max counts all member cost-sharing (copay + deductible + coinsurance — the modern ACA
  definition). Per line, in order, the calculator computes the gross member amount, then caps it by the OOP
  remaining for the year (`max(0, plan.outOfPocketMax − out_of_pocket_met)`); the excess shifts to the plan and
  is recorded per line as `oopMaxAppliedAmount`. A null `outOfPocketMax` = no cap. The cap runs across lines
  within a claim and across claims via the accumulator (which accrues the real, post-cap member spend).
- **Explainability (§60):** the new per-line `oopMaxAppliedAmount` keeps the arithmetic reconciled —
  `memberResponsibility = copay + deductibleApplied + coinsurance − oopMaxApplied`, `planPaid = allowed − member`.
- **Migration `V22__adjudication_line_oop.sql`:** additive `oop_max_applied_amount NUMERIC(12,2) NOT NULL
  DEFAULT 0` (+ a non-negative CHECK) on `adjudication_line`; existing rows default 0.
- **Changed:** `AdjudicationCalculator` gains a 4-arg `adjudicate(plan, deductibleRemaining, oopRemaining, lines)`
  (the 2-/3-arg forms delegate with an uncapped OOP, so slice-1/2 tests stay valid) and `LineComputation` gains
  `oopMaxAppliedAmount`; `AdjudicationLine` (+column/field), `AdjudicationLineDto` (+field), `AdjudicationService`
  (computes OOP-remaining from the plan max and the locked accumulator, passes it to the calculator).
- **Scope boundary (later slices):** exclusions (non-covered procedures), fee-schedule allowed amounts
  (`allowed = charge`), re-adjudication versioning, frontend.
- **Verified — automated:** `./mvnw -B clean verify` → **237 pass** (+4: `AdjudicationCalculatorTest` +3 — the
  cap bites, a null OOP never caps, the cap spreads across lines; `AdjudicationAccumulatorApiIntegrationTest` +1 —
  a $40,000 PPO claim caps the member at the $6,000 OOP max (plan pays $34,000, $3,220 shifted) and a second
  same-year claim is fully plan-paid).
- **Verified — live:** `db-reset` → fresh backend → a $40,000 PPO claim → member $6,000, plan $34,000,
  `oopMaxApplied` $3,220; a follow-up $500 claim → member $0, plan $500 (OOP met → plan pays 100%).
- **Files:** +`V22__adjudication_line_oop.sql`; changed `AdjudicationCalculator`, `AdjudicationLine`,
  `AdjudicationLineDto`, `AdjudicationService`, `AdjudicationRepositoryTest`, `AdjudicationCalculatorTest`,
  `AdjudicationAccumulatorApiIntegrationTest`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 2 ✅ (the benefit accumulator — the annual deductible carries across claims)
- **Why:** slice 1's honest limitation was that the deductible started fresh on every claim (each claim behaved
  like the first of the year). This adds the financial accumulator so the annual deductible **carries across
  claims** within a benefit year — and does it under the source-of-truth's §31 rule (**row locks for financial
  accumulators**), so concurrent adjudications can't lose an update.
- **How:** a `benefit_accumulator` row per `(patient, coverage_plan, benefit_year)` holds `deductible_met` and
  `out_of_pocket_met`. Inside the adjudication transaction the engine (1) **insert-if-absent** (`ON CONFLICT DO
  NOTHING`) to guarantee the row exists, then (2) **locks it `FOR UPDATE`** (`@Lock(PESSIMISTIC_WRITE)`), computes
  `deductibleRemaining = max(0, plan.deductible − deductible_met)`, feeds that to the calculator, and increments
  the accumulator by this claim's deductible-applied + member responsibility. `benefit_year` = the claim's
  service-date calendar year (MVP: plan year = calendar year).
- **Calculator:** added `adjudicate(plan, deductibleRemaining, lines)`; the old 2-arg form delegates with the
  plan's full deductible (the "first claim of the year" case), so slice-1's tests stay valid.
- **Migration `V21__benefit_accumulator.sql`:** `benefit_accumulator` — patient, coverage_plan, benefit_year,
  `deductible_met`/`out_of_pocket_met` NUMERIC(12,2), `version`, audit; composite FKs-with-org to patient +
  coverage_plan (§32.10); `UNIQUE(org, patient, coverage_plan, benefit_year)` (the insert-if-absent + lock target).
- **New in `com.healthcloud.adjudication`:** `BenefitAccumulator` entity, `BenefitAccumulatorRepository`
  (`insertIfAbsent` native + `lockByKey` PESSIMISTIC_WRITE + an unlocked finder). Modified `AdjudicationService`
  (accumulator read-lock-update woven into the covered path; denial path untouched — no coverage, no accumulator)
  and `AdjudicationCalculator` (the remaining-deductible overload).
- **Scope boundary (slice 3):** `out_of_pocket_met` is tracked now but the **out-of-pocket-max cap is not yet
  enforced** — the same "build the hook now, consume it next" pattern as `findCovering`. Also deferred: exclusions,
  fee-schedule allowed amounts, re-adjudication, frontend. A true multi-threaded race test is inherently flaky, so
  concurrency safety rests on the pessimistic lock (proven by the repository test), stated honestly not shipped
  as a flaky test.
- **Verified — automated:** `./mvnw -B clean verify` → **233 pass** (+6: `AdjudicationCalculatorTest` +2
  remaining-deductible cases; `BenefitAccumulatorRepositoryTest` ×3 — insert-if-absent idempotent + locked read,
  tenant/year scoping, `add` accrual; `AdjudicationAccumulatorApiIntegrationTest` ×1 — two 2026 claims: the first
  is all deductible (plan pays 0), the second sees $525 remaining and the plan pays $360, and a 2025 claim resets).
- **Verified — live:** `db-reset` → fresh backend → PPO patient; a 2026 $1,000 claim → plan pays $0 (all
  deductible, $975 met after the $25 copay); a second 2026 $1,000 claim → deductible-applied $525, coinsurance
  $90, **plan pays $360**; a 2025 $1,000 claim → plan pays $0 again (a new benefit year resets the deductible).
- **Files:** +`V21__benefit_accumulator.sql`, +`adjudication/` (2: entity + repository), +2 test classes; changed
  `AdjudicationService`, `AdjudicationCalculator`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 5, slice 1 ✅ (the core claims-adjudication engine — ACCEPTED → ADJUDICATED)
- **Why:** Phase 5 completes the MVP — the deterministic, explainable adjudication engine. All the Phase-4
  building blocks are in (claims + state machine, coverage plans, patient eligibility), so this slice wires them
  together: turn an ACCEPTED claim into a recorded, explainable outcome and move it to ADJUDICATED. It delivers
  the §60 proof directly: for any decision, show which plan applied and how every amount was computed.
- **A dedicated engine command, not a bare status change:** `ADJUDICATED` is reached only by
  `POST /api/v1/claims/{id}/adjudicate` (like `ASSIGNED` via `PUT .../assignment`); slice 4 already refuses a
  bare `PATCH /status` to ADJUDICATED. The command advances ACCEPTED → ADJUDICATED and writes the adjudication +
  a `claim_status_history` row in **one transaction** (§31.6). A second attempt fails the ACCEPTED gate (the
  claim is now ADJUDICATED) → 409, so the state gate is the double-apply safety (no Idempotency-Key needed).
- **The math (pure policy — the third exemplar after `ClaimTransitions`/`ConsentPolicy`):**
  `AdjudicationCalculator` (no Spring/DB) takes the covering plan's parameters + line charges and, per line in
  order, computes `allowed = charge` → **copay** (min(copay, allowed)) → **deductible** consumed across the
  claim's lines → **coinsurance** = round(remainder × rate); `member = copay + deductible + coinsurance`,
  `plan = allowed − member`. Money `BigDecimal` scale 2 HALF_UP. Fully unit-tested (deductible-across-lines,
  zero-coinsurance, no-coverage handled by the service, rounding).
- **Outcomes:** coverage found on the service date (`findCovering`, 0/1 row) → `ADJUDICATED`, lines `COVERED`;
  no coverage → `DENIED_NO_ELIGIBILITY` (plan pays 0, member responsible for the charge), lines `NOT_COVERED` —
  still a recorded, explainable decision. The record is **immutable** and carries `adjudicationVersion` (1).
- **Authorization (§21):** tenant → role (**CLAIMS_REVIEWER/ORG_ADMIN** — the reviewer's action, mirrors
  accept/reject) → object/relationship (`PatientAccessGuard`, via the claim's patient → secure 404).
- **Migration `V20__adjudication.sql`:** `adjudication` (header: outcome CHECK, nullable coverage_plan_id +
  eligibility_id, total charge/allowed/plan-paid/member amounts, `adjudication_version`, audit + correlation id;
  FK-with-org to claim, `UNIQUE(id, org)`, `UNIQUE(org, claim_id, version)`) + `adjudication_line` (per-line
  outcome + allowed/copay/deductible/coinsurance/plan-paid/member; a self-contained immutable snapshot copying
  the procedure code + charge; FK-with-org to adjudication). Both append-only.
- **New `com.healthcloud.adjudication` package:** `AdjudicationOutcome` + `LineOutcome` enums, `Adjudication` +
  `AdjudicationLine` entities, `AdjudicationRepository` + `AdjudicationLineRepository` (org-scoped finders),
  `AdjudicationCalculator` (pure), `AdjudicationDto` + `AdjudicationLineDto` (the explainable read),
  `AdjudicationService`, `AdjudicationController` (`POST .../adjudicate`, `GET .../adjudication`).
- **Honest MVP limitations (later Phase-5 slices):** no cross-claim annual deductible/OOP accumulator (the
  deductible starts fresh per claim — the accumulator needs row locks, §31); no out-of-pocket-max; no exclusions;
  allowed = charge (no fee schedule); no re-adjudication; no frontend.
- **Verified — automated:** `./mvnw -B clean verify` → **227 pass** (+14: `AdjudicationCalculatorTest` ×5 pure
  math; `AdjudicationRepositoryTest` ×2 — tenant scoping + line order; `AdjudicationApiIntegrationTest` ×7 — 401;
  a reviewer adjudicates a covered claim → ADJUDICATED + reads the breakdown; no coverage → DENIED; a non-ACCEPTED
  claim → 409; a coordinator → 403; re-adjudication → 409; cross-tenant → secure 404).
- **Verified — live:** `db-reset` → fresh backend → enrolled a patient in the PPO, ran a $2,000 claim →
  `ADJUDICATED` with copay $25 + deductible $1,500 + coinsurance 20%×$475 = $95 → member $1,620, **plan pays
  $380**; an uncovered patient's claim → `DENIED_NO_ELIGIBILITY` (member owes the full $150.00, line NOT_COVERED);
  re-adjudicate → 409; a coordinator adjudicate → 403. Raw JSON preserves money scale (`195.50`, `0.00`).
- **Seeder unchanged** (the sample claim stays DRAFT so existing tests are undisturbed; the demo/tests drive
  submit→accept→adjudicate). **Backend-only** — a claims/adjudication UI arrives with the frontend slice.
- **Files:** +`V20__adjudication.sql`, +`adjudication/` package (11 files), +3 test classes; changed `CLAUDE.md`,
  `docs/PROGRESS.md`.

### 2026-09-15 — Phase 4, slice 6 ✅ (patient eligibility — enrollment in a coverage plan)
- **Why:** the last Phase-4 foundation. It records which patient is on which plan and when, so Phase 5 can ask
  "for this claim's patient + service date, what coverage was in effect?". Patient-scoped, so it reuses the
  Phase-3 stack (`PatientAccessGuard`, tenant scoping) and FKs the slice-5 coverage plan.
- **Migration `V19__patient_eligibility.sql`:** `patient_eligibility` — patient, coverage_plan, `member_id`,
  `effective_from`, nullable `effective_to`, audit cols, `version`. Two composite FKs (`(patient_id, org)` →
  patient, `(coverage_plan_id, org)` → coverage_plan, §32.10); CHECK `effective_to >= effective_from`.
- **New in `com.healthcloud.coverage`:** `PatientEligibility` entity, `PatientEligibilityRepository` (org+patient
  finders + a `findCovering(org, patient, date)` window query — the Phase-5 hook), `PatientEligibilityDto`
  (carries the plan name, resolved at read), `EnrollEligibilityRequest`, `PatientEligibilityService`,
  `PatientEligibilityController` (nested under the patient).
- **Behaviour:** enroll requires CARE_COORDINATOR/ORG_ADMIN (a PROVIDER/PATIENT → 403) and routes through the
  patient gate (unreachable → secure 404); the plan must be in-tenant (else 400); dates are validated; and
  periods for a patient are kept **non-overlapping** (enforced in the service → 409) so coverage-on-a-date is
  deterministic. Reads are gated (a patient reads their own; an assigned provider / broad roles read too);
  `GET .../eligibility?asOf=` returns just the covering period. Not consent field-masked (claims/benefits data).
- **Seeder:** enrolls the first patient per org in that org's PPO (open-ended, member `<PREFIX>-M0001`).
- **Verified — automated:** `./mvnw -B clean verify` → **213 pass** (+10: `PatientEligibilityRepositoryTest` ×2
  — tenant scoping + the `findCovering` window; `PatientEligibilityApiIntegrationTest` ×8 — 401; coordinator
  enroll + read-back; provider enroll → 403; a patient reads their own coverage; unknown plan → 400; overlap →
  409; `asOf` filters to the covering period; cross-tenant → secure 404).
- **Verified — live:** `db-reset` → fresh backend → Sam's seeded PPO enrollment reads back (member NC-M0001,
  open-ended); `asOf` today → 1 record, `asOf` 2020 → 0; an overlapping enroll → 409; an unknown plan → 400.
- **Files:** +`V19__patient_eligibility.sql`, +5 `coverage/` classes, +2 test classes; changed `DevDataSeeder`,
  `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 4, slice 5 ✅ (coverage plan foundation — the benefit plan)
- **Why:** Phase 5's adjudication engine needs benefit parameters to apply (deductible, copay, coinsurance) and
  a way to know a patient is covered. By dependency order the **plan comes first** (eligibility references it),
  so this slice builds the coverage plan; patient eligibility is slice 6; then Phase 5 does the math.
- **A different shape — tenant-owned but NOT patient-scoped:** a coverage plan is administrative benefit config,
  not PHI, so it uses the tenant pattern (org-scoped finders, cross-tenant → secure 404) with **no
  `PatientAccessGuard`**. Reads are open to any same-tenant authenticated user; **create requires ORG_ADMIN**.
- **Migration `V18__coverage_plan.sql`:** `coverage_plan` — `plan_code` (unique per tenant), `name`, `plan_type`
  (HMO/PPO/EPO/HDHP, CHECK), `deductible_amount`, `coinsurance_rate` NUMERIC(5,4) (CHECK 0..1, member share
  after deductible), `copay_amount`, nullable `out_of_pocket_max`, `active`, audit cols, `version`. `UNIQUE
  (organization_id, plan_code)` and `UNIQUE(id, organization_id)` so eligibility can FK-with-org next slice.
- **New `com.healthcloud.coverage` package:** `CoveragePlan` entity (money as `BigDecimal`), `PlanType` enum,
  `CoveragePlanRepository` (org-scoped finders + `existsByOrganizationIdAndPlanCode`), `CoveragePlanDto`,
  `CreateCoveragePlanRequest` (`@Valid`: coinsurance `@DecimalMin/@DecimalMax` 0..1, amounts `@PositiveOrZero`),
  `CoveragePlanService` (create requires ORG_ADMIN, pre-checks the code for a clean 409), `CoveragePlanController`
  (`POST` / `GET` list / `GET /{id}`).
- **Seeder:** two synthetic plans per org — a Standard PPO ($1,500 deductible / 20% coinsurance / $25 copay /
  $6,000 OOP) and an HDHP ($4,000 / 10% / $0 / $8,000).
- **Verified — automated:** `./mvnw -B clean verify` → **203 pass** (+7: `CoveragePlanRepositoryTest` ×2 —
  tenant-scoped lookup + per-tenant code uniqueness / cross-tenant reuse; `CoveragePlanApiIntegrationTest` ×5 —
  401; ORG_ADMIN creates + reads + sees the seeded plan; a non-admin create → 403; duplicate code → 409;
  cross-tenant get → secure 404).
- **Verified — live:** `db-reset` → fresh backend → the seeded plans list for an admin; an admin created an EPO
  plan (201); a coordinator was refused (403 ACCESS_DENIED); a duplicate code returned 409 CONFLICT.
- **Files:** +`V18__coverage_plan.sql`, +`coverage/` package (6 files), +2 test classes; changed `DevDataSeeder`,
  `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 4, slice 4 ✅ (claim submission/validation state machine)
- **Why:** slice 3 created claims in DRAFT; this adds the controlled lifecycle — the "submission/validation
  workflow" — so a claim can be submitted, then accepted or rejected by a reviewer. Mirrors the `service_request`
  state machine exactly, and gives the **CLAIMS_REVIEWER their first write action** (accept/reject).
- **State machine:** DRAFT→SUBMITTED→{ACCEPTED,REJECTED}, plus CANCELLED (from DRAFT/SUBMITTED), and ADJUDICATED
  reserved for Phase 5. Roles: submitter side (PROVIDER-assigned/CARE_COORDINATOR/ORG_ADMIN) submits + cancels;
  **CLAIMS_REVIEWER** (+ORG_ADMIN) accepts/rejects. Logic in the pure `ClaimTransitions` policy class (legal
  moves, role rules, reason-required) — no Spring/DB — the second exemplar of the pure-policy pattern.
- **Endpoints:** `PATCH /api/v1/claims/{id}/status` (targetStatus + expectedVersion + optional reason) and
  `GET /api/v1/claims/{id}/history`. Check order in the service (mirrors the request machine): exists + patient
  gate → **reserved** (ADJUDICATED via a bare change → 409) → legal move → role → reason → **submit-validation**
  → optimistic version — then status change + a `claim_status_history` row in **one transaction**. Submitting
  **validates** the claim: ≥1 line and total charge > 0 (a $0 claim → 400). A reason is required to reject/cancel.
  `ClaimService.create` now also writes the null→DRAFT creation history row so the timeline is complete.
- **Migration `V17__claim_status_history.sql`:** append-only history (from/to status, actor, reason,
  correlation id), FK `(claim_id, organization_id)` → claim; mirrors `request_status_history`.
- **New in `com.healthcloud.claim`:** `ClaimTransitions`, `ClaimStatusHistory` entity + repository + DTO,
  `ClaimStatusChangeRequest`. Modified `ClaimService` (+history repo, `changeStatus`, `getHistory`, creation
  row), `ClaimController` (+PATCH/status, +GET/history), `DevDataSeeder` (creation history row for the sample).
- **Verified — automated:** `./mvnw -B clean verify` → **196 pass** (+12: `ClaimTransitionsTest` ×4 pure;
  `ClaimStateMachineApiIntegrationTest` ×8 — submit→accept records the 3-row history; reject requires a reason;
  illegal DRAFT→ACCEPTED → 409; a reviewer can't submit + a provider can't accept → 403; stale version → 409;
  ADJUDICATED via the bare endpoint → 409; a zero-total claim can't be submitted → 400; cross-tenant → 404).
- **Verified — live:** `db-reset` → fresh backend → coordinator submitted the seeded claim, reviewer accepted
  it, and `GET /history` showed `None→DRAFT (created) → DRAFT→SUBMITTED → SUBMITTED→ACCEPTED` with actors. (The
  role-negative curls returned 404 only because of a greedy `sed` grabbing a line id — the automated tests are
  the authoritative 403 proof.)
- **Files:** +`V17__claim_status_history.sql`, +4 `claim/` classes, +2 test classes; changed `ClaimService`,
  `ClaimController`, `DevDataSeeder`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 4, slice 3 ✅ (claims intake — the claim header + claim lines aggregate)
- **Why:** the money side of Phase 4. A claim is a header + one or more lines, each line billing a *procedure*
  code (CPT/HCPCS) from the slice-1 catalog. It's the first Phase-4 resource with a **parent→child aggregate**
  and with **monetary amounts**, and it sets up Phase 5's adjudication engine (which will read these lines). It
  reuses the Phase-3 stack (tenant scoping + `PatientAccessGuard`) rather than adding new access machinery.
- **Design choice — top-level, gated by patient (like `service_request`, NOT nested like clinical summaries):**
  a reviewer needs a cross-patient **work queue**, so claims live at `/api/v1/claims` and the list scopes via the
  request module's `accessiblePatientIdsIfGated` — a provider sees only assigned patients' claims; a broad role
  (coordinator/admin/reviewer) sees the tenant's claims. Directly serves the §60 reviewer proof.
- **Migration `V16__claim.sql`:** `claim` header (tenant key, `patient_id`, unique-per-tenant `claim_number`,
  `status` DEFAULT DRAFT with the forward lifecycle in the CHECK, `service_date`, `total_charge_amount`
  NUMERIC(12,2), audit cols, `version`) + `claim_line` child (`line_number` unique per claim, procedure
  code+system, `units`>0, `charge_amount`≥0). Structural integrity via **two FKs**: `(patient_id,
  organization_id)`→patient and `(procedure_code_system, procedure_code)`→the global `medical_code`; plus
  `UNIQUE(id, organization_id)` so lines FK-with-org, and `UNIQUE(organization_id, claim_number)`.
- **New `com.healthcloud.claim` package:** `Claim` + `ClaimLine` entities (money as `BigDecimal`), `ClaimStatus`
  enum, `ClaimRepository`/`ClaimLineRepository` (org-scoped finders + list-by-accessible-patients),
  `ClaimDto`(header+lines)/`ClaimLineDto`/`ClaimSummaryDto`(header-only list), `CreateClaimRequest` +
  `CreateClaimLineRequest` (`@Valid`), `ClaimService`, `ClaimController`.
- **Behaviour:** create is **one transaction** (§31.6 aggregate) — validate every line's procedure code first
  (system resolved from the code among CPT/HCPCS; unknown or a diagnosis code → **400 VALIDATION_FAILED**, the
  canonical spelling is stored), compute the header total from the lines (never the client), then write header +
  lines atomically. `claim_number` is server-allocated (`CLM-XXXXXXXX`, unique-checked). Reads route through the
  patient gate (unreachable/cross-tenant → secure 404); list optionally filters `?patientId=` / `?status=`.
  Create roles: PROVIDER (must be actively assigned)/CARE_COORDINATOR/ORG_ADMIN — a CLAIMS_REVIEWER reads, not
  writes. Created in DRAFT only.
- **§60 proof (reviewer half):** a claim carries only coded, claim-relevant data — **no clinical narrative** —
  so a reviewer works the claim queue without unrestricted medical context; the narrative stays consent-masked
  in `clinical_summary`. Claims are not consent field-masked.
- **Seeder:** one sample DRAFT claim (two CPT lines, total 195.50) for the first patient per tenant; reference
  codes already seed before the orgs (slice 2) so the new catalog FK is satisfied.
- **Verified — automated:** `./mvnw -B clean verify` → **184 pass** (+11: `ClaimRepositoryTest` ×4 — tenant
  scoping, per-tenant number uniqueness + cross-tenant reuse, line ordering + per-claim line-number uniqueness,
  the catalog FK; `ClaimApiIntegrationTest` ×7 — 401; create returns the aggregate with a computed total; single
  read; unknown + diagnosis-as-procedure → 400; reviewer lists/reads claim data (§60); a provider sees only
  assigned patients' claims (gate); cross-tenant → secure 404).
- **Verified — live:** `db-reset` → fresh backend → the reviewer's queue shows the seeded DRAFT claim (total
  195.5); a coordinator created a mixed CPT+HCPCS claim (lowercase `j1815` resolved to canonical `J1815`, total
  240.00 computed); a diagnosis code as a procedure line returned a clean `400`.
- **Deferred to slice 4:** `claim_status_history` + the controlled submission/validation state machine (only
  DRAFT is created now). Also still open: consent masking of claim fields (`CLAIMS_BENEFITS`) and the fuller
  CLAIMS_REVIEWER business-need scoping — this slice delivers the structural half (claims have no narrative).
- **Files:** +`V16__claim.sql`, +`claim/` package (11 files), +2 test classes; changed `DevDataSeeder`,
  `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-15 — Phase 4, slice 2 ✅ (clinical summaries — patient clinical context, consent-masked)
- **Why:** with the code vocabulary in place (slice 1), the next Phase-4 building block is a clinical summary —
  a short clinical note about a patient encounter that records a *diagnosis code*. It is the first Phase-4
  resource that is **about a patient**, so it deliberately reuses the whole Phase-3 stack (tenant scoping, the
  `PatientAccessGuard` object/relationship gate, consent field masking) rather than adding new machinery. This
  is also where the **Phase-4 §60 proof begins**: the coded, claim-relevant diagnosis stays visible while the
  free-text clinical narrative is consent-controlled. Backend-only.
- **Migration `V15__clinical_summary.sql`:** `clinical_summary` — tenant key `organization_id`, `patient_id`,
  `summary_type` (ENCOUNTER/DIAGNOSIS/TREATMENT/LAB_RESULT, CHECK-constrained), `encounter_date`, `title`,
  `diagnosis_code_system`+`diagnosis_code`, `narrative` (consent-controlled), author, timestamps, `lock_version`
  (`@Version`). Two FKs enforce integrity structurally: a **composite FK `(patient_id, organization_id)` →
  patient** (a summary can't attach to another tenant's patient, §32.10) and a **FK `(diagnosis_code_system,
  diagnosis_code)` → the global `medical_code`** catalog (the diagnosis must be a real code). Index on
  `(organization_id, patient_id)` for the list read.
- **New `com.healthcloud.clinical` package:** `ClinicalSummary` entity, `ClinicalSummaryType` enum,
  `ClinicalSummaryRepository` (org+patient-scoped finders only — newest-encounter-first listing, no bare
  `findById`), `ClinicalSummaryDto` (field-safe: masks `narrative`), `ClinicalSummaryFieldPolicy`
  (`narrative` → `CLINICAL_CONTEXT`/`SENSITIVE_HEALTH_DATA` — the `PatientFieldPolicy`-shaped map),
  `ClinicalSummaryCreateRequest` (`@Valid`), `ClinicalSummaryService`, `ClinicalSummaryController`.
- **Endpoints (nested under the patient):** `GET/POST /api/v1/patients/{patientId}/clinical-summaries` and
  `GET .../{summaryId}`. Every op routes through `PatientAccessGuard.requireAccessibleInTenant` first (org taken
  from the loaded patient, never the client) → unreachable patient / cross-tenant is a **secure 404**. Writes
  require PROVIDER (must be actively assigned)/CARE_COORDINATOR/ORG_ADMIN (403 otherwise); the diagnosis is
  validated as an **active ICD-10-CM** code (unknown/non-diagnosis → **400 VALIDATION_FAILED**, and the catalog's
  canonical spelling is stored). **Consent masking (§22.5/§23):** the read fixes purpose = CARE_COORDINATION and
  masks `narrative` deny-by-default via `ConsentPolicyService.decideForActor`; the coded diagnosis stays visible.
  Write responses are unmasked.
- **Seeder:** reordered `run()` to seed the global codes **before** the orgs (the new catalog FK needs them),
  then seeds a couple of synthetic clinical summaries per assigned patient (E11.9, I10). With no consent
  directive seeded, a demo read masks the narrative by default; recording a `CLINICAL_CONTEXT` grant reveals it.
- **Verified — automated:** `./mvnw -B clean verify` → **173 pass** (+8: `ClinicalSummaryRepositoryTest` ×2 —
  tenant/patient scoping + newest-first, and the catalog FK; `ClinicalSummaryApiIntegrationTest` ×6 — 401
  unauth; unmasked write vs masked read (deny-by-default); a CLINICAL_CONTEXT grant reveals the narrative; a
  reviewer sees the coded diagnosis but not the narrative on an ungranted patient; unknown/procedure code → 400;
  cross-tenant → secure 404).
- **Verified — live:** `db-reset` → fresh backend → the seeded summaries read newest-first with `narrative`
  masked for the coordinator; an org-wide `CLINICAL_CONTEXT` grant flips the narrative visible; a bad code POST
  returns a clean `400 VALIDATION_FAILED` ("Unknown ICD-10-CM diagnosis code: NOPE.0").
- **Honest limitation:** the reviewer restriction here is **deny-by-default consent**, so an org-wide grant
  reveals the narrative to everyone including a reviewer. The stronger "a CLAIMS_REVIEWER sees claims data
  *regardless* of consent, but never unrestricted clinical context" business-need rule is the deferred
  permission-matrix work — it lands with the claims slices.
- **Files:** +`V15__clinical_summary.sql`, +`clinical/` package (8 files), +2 test classes; changed
  `DevDataSeeder`, `CLAUDE.md`, `docs/PROGRESS.md`.

### 2026-09-14 — Phase 4, slice 1 ✅ (medical code catalog — the shared clinical/claims vocabulary)
- **Why:** Phase 4 (clinical context & claims intake) opens here. Its building blocks have a dependency order —
  a clinical summary records a *diagnosis code*, a claim line records a *procedure code*, so both need a shared
  code vocabulary to point at first. This slice builds that catalog (ICD-10-CM / HCPCS / CPT). Backend-only.
- **A deliberately new pattern — global reference data, NOT tenant-owned:** unlike every business table since
  Phase 2, `medical_code` has **no `organization_id`, no `PatientAccessGuard`, no consent** — codes are public
  national standards, identical for both tenants (the app's first shared business-reference table, like `role`).
  Documented that reasoning in the migration + `MedicalCode`/`MedicalCodeRepository` javadoc + CLAUDE.md so the
  deviation is intentional. Public code vocabularies are reference data, not PHI, so seeding real-format values
  respects the synthetic-only rule (which governs patient/employer/client data).
- **Migration `V14__medical_code.sql`:** `medical_code` — `code_system` (ICD10CM/HCPCS/CPT, CHECK-constrained),
  `code`, `description`, `active`, `created_at`. **Unique `(code_system, code)`** (a code is unique within its
  system; that index also serves system-scoped prefix search). No `@Version`/`updated_at` — reference rows are
  immutable in-app (loaded by data import, not user edits).
- **New `com.healthcloud.coding` package:** `CodeSystem` enum (carries a human `label` + `category`
  Diagnosis/Procedure), `MedicalCode` entity, `MedicalCodeRepository` (a `search(system, term, pageable)` JPQL
  — optional system, optional term matching a code prefix OR a description substring, active-only, bounded — and
  an exact case-insensitive lookup), `MedicalCodeDto`, `MedicalCodeService` (caps results at 50; requires an
  authenticated caller — no tenant/gate), `MedicalCodeController`.
- **Endpoints (authenticated, any role; not tenant-scoped):** `GET /api/v1/medical-codes?system=&q=` (search)
  and `GET /api/v1/medical-codes/{system}/{code}` (single; 404 miss). An unknown `system` binds to no enum
  constant → **400 VALIDATION_FAILED** via the existing `MethodArgumentTypeMismatchException` handler (added
  slice-2), not a 500.
- **Seeder:** `DevDataSeeder` now seeds a small illustrative catalog **once, globally** (17 real-format codes:
  8 ICD-10 diagnoses, 6 CPT, 3 HCPCS) after the two orgs — so a demo search returns something.
- **Verified — automated:** `./mvnw -B clean verify` → **165 pass** (+9: `MedicalCodeRepositoryTest` ×4 —
  system+term search, case-insensitive exact lookup scoped to its system, `(system, code)` uniqueness, page-size
  cap; `MedicalCodeApiIntegrationTest` ×5 — 401 unauth; search filters by system & term; single hit + 404 miss;
  unknown system → 400; the catalog reads identically for both tenants). Flyway applied V14 cleanly.
- **Verified — live (curl, fresh `db-reset` so the seeder ran):** 401 without a session; coordinator search
  `?system=ICD10CM&q=diabetes` → E11.9 (no CPT rows); `?system=CPT&q=992` → the three 992xx office-visit codes;
  single lookup `ICD10CM/E11.9` → 200; `ICD10CM/NOPE.0` → 404 NOT_FOUND; `?system=BOGUS` → 400 VALIDATION_FAILED;
  a Green Valley admin read the same `ICD10CM/I10` (global, cross-tenant). (Killed the old :8080 backend and ran
  the fresh build first.)
- **No frontend change** — this is the backend foundation; a code-picker UI arrives when a slice first consumes
  codes (clinical summary / claim line).
- **Next:** slice 2 — clinical summaries (encounter + diagnosis referencing an ICD-10 code, patient-scoped →
  reuses `PatientAccessGuard` + field masking); then claim header + lines. Good moment for `/security-review`.

### 2026-09-14 — Phase 3, slice 16 ✅ (documents UI — the §19 loop is now visible in the browser)
- **Why:** slices 14–15 built secure documents entirely on the backend (store, gate, scan, quarantine); this
  surfaces them so you can upload, see the scan verdict, download a clean file, and watch a malicious one get
  quarantined. **Frontend-only — no backend change, no migration.**
- **Plumbing:** `api/types.ts` gained `DocumentScanStatus` + `PatientDocument`; `api/client.ts` gained
  `listDocuments`, `uploadDocument` (multipart `FormData` — no explicit `Content-Type` so the browser sets the
  boundary; the CSRF header still injects on POST) and `downloadDocument` (a dedicated `fetch` returning the
  `Blob`, throwing `ApiClientError` on a non-2xx so a quarantined 409 surfaces its message). New
  `src/documents/useDocuments.ts` — `useDocuments` (list) + `useUploadDocument` (invalidates the list on success).
- **UI:** a **Documents card** on `PatientDetailPage` (after Care team, before Consent) — a table of filename /
  type / human-readable size / a **scan-status chip** (CLEAN green, PENDING amber, QUARANTINED red) / actions. A
  **Download** button shows only for CLEAN documents (it fetches the blob and triggers a browser save via an
  object URL); a QUARANTINED/PENDING row shows its status and no download. An **Upload** control (file picker +
  button) is shown to `DOCUMENT_WRITE_ROLES` = PATIENT (own record) + CARE_COORDINATOR/ORG_ADMIN (role-aware UI;
  the backend enforces). Errors (disallowed type → 400, too large, a racing 409) surface via `ApiClientError`.
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **33 pass** (+4
  `PatientDetailPage.test.tsx`: renders a CLEAN doc with Download; a QUARANTINED doc shows the status and no
  Download; a coordinator uploads a file (asserts `uploadDocument` called); a PROVIDER sees the list but no upload
  control). `npm run build` OK (pre-existing chunk-size advisory only). Backend untouched.
- **Verified — live in browser** (patient@northcare on their own record Sam Sample): the Documents card listed
  the seeded `eicar.txt` as **QUARANTINED** (shown "Quarantined", no download) and `clean.txt`/`original.txt` as
  **CLEAN** with **Download**; the upload control was present (a patient may upload to their own record). Full
  stack, end to end, visibly. (The multipart upload + CLEAN/QUARANTINED download paths themselves were proven by
  the slice-14/15 integration tests + curl.)
- **Next:** Phase 3 is essentially complete — optional polish (more field-masking; a decision "explain" view) or
  move to Phase 4 (clinical context & claims intake). Strong moment for `/security-review` first.

### 2026-09-14 — Phase 3, slice 15 ✅ (document malware scan + quarantine — flagged files can't be downloaded)
- **Why:** slice 14 stored documents but left `scan_status` defaulting to CLEAN. This adds the other half of §19:
  every upload is scanned, a flagged file is quarantined, and a quarantined (or not-yet-scanned) file cannot be
  downloaded. Purely additive — the column/enum/lifecycle were already in place, so **no migration**.
- **Synchronous scan now, async later (decided):** the real §19 flow is asynchronous (upload → event → scanner →
  status), but event infra is Phase 8. To keep the slice deterministic and verifiable, the scan runs synchronously
  at upload behind a swappable `DocumentScanner` component; PENDING stays in the model and the download gate
  defends it, so Phase 8 (write PENDING → worker flips it) is a drop-in with no API-shape change. (An after-commit
  event listener was rejected on purpose — it would make tests timing-flaky.)
- **New `DocumentScanner` interface + `FakeDocumentScanner`:** flags any file containing the **EICAR** test
  signature (the standard, harmless AV test string — a realistic, zero-risk, deterministic trigger), else CLEAN.
  The signature is assembled from fragments at runtime so the contiguous string never appears as a literal in the
  source/class (otherwise a dev's own antivirus could quarantine the build). Reason is logged, never file contents.
- **Service:** `upload` now scans the stored bytes and persists the row with the verdict — upload always succeeds
  (201) and reports `scanStatus`; a flagged file is retained QUARANTINED (audit trail; the gate withholds it),
  not rejected. `download` refuses anything not CLEAN via a new `DocumentNotAvailableException` (new
  `ErrorCode.DOCUMENT_NOT_AVAILABLE`, HTTP **409**) — QUARANTINED and PENDING get distinct messages. Not a secure
  404: an authorized caller already sees the document (with its status) in the listing.
- **Verified — automated:** `./mvnw -B clean verify` → **156 pass** (+6: `FakeDocumentScannerTest` ×4 — clean /
  EICAR / EICAR embedded mid-file / empty; `DocumentMalwareScanApiIntegrationTest` ×2 — a clean upload is CLEAN +
  downloadable; an EICAR upload is 201 QUARANTINED, listed with that status, and download → 409
  DOCUMENT_NOT_AVAILABLE). The slice-14 round-trip test still passes (its file is clean).
- **Verified — live (curl):** coordinator uploaded a clean text file → CLEAN, download **200**; uploaded an EICAR
  file → **QUARANTINED**, download → **409 DOCUMENT_NOT_AVAILABLE** with the "quarantined by a malware scan"
  message. (Restarted the local backend onto slice-15 code after the `clean`.)
- **No migration, no frontend change** (the list DTO already carries `scanStatus`; the UI surfaces it in slice 16).
- **Next:** slice 16 — documents UI (upload/list/download + show the quarantined state). Strong moment for
  `/security-review`.

### 2026-09-14 — Phase 3, slice 14 ✅ (secure documents, part 1 — metadata + storage abstraction + gated upload/download)
- **Why:** secure documents (§19) is the last big Phase-3 pillar before the MVP. The design is "private S3 for
  the BYTES + PostgreSQL for the METADATA". Split into slices: **14 (this one)** builds the document object,
  a storage abstraction with a local-filesystem stand-in, and patient-gated upload/download; **15** adds the
  fake malware scanner + quarantine download gate; **16** the documents UI. Backend-only this slice.
- **Migration `V13__patient_document.sql`:** `patient_document` — tenant key `organization_id`, `patient_id`,
  `file_name`, `content_type`, `size_bytes`, `storage_key` (opaque key into the blob store), `scan_status`
  (PENDING/CLEAN/QUARANTINED — CHECK-constrained), `uploaded_by_user_id`, `uploaded_at`, `lock_version`
  (`@Version`). **Composite FK** `(patient_id, organization_id) → patient` (§32.10); index `(org, patient)`;
  **unique** `storage_key`. Bytes are NOT in the table.
- **New `com.healthcloud.document` package:** `PatientDocument` + `DocumentScanStatus`, tenant-safe
  `PatientDocumentRepository`, `DocumentDto` (metadata only — bytes never enter a DTO/log/event, §23.4).
  **`DocumentStorage` interface** (`store/load/delete`, owns the key layout) with `LocalFileSystemDocumentStorage`
  (writes under `${healthcloud.documents.dir}`, layout `org/patient/uuid`, path-traversal guarded) — the seam
  private S3 plugs into at Phase 10 with no service/controller change. `PatientDocumentService` +
  `PatientDocumentController` (nested under the patient).
- **Endpoints:** `POST /api/v1/patients/{id}/documents` (multipart upload; write roles PATIENT/CARE_COORDINATOR/
  ORG_ADMIN, patient only their own record), `GET .../documents` (list metadata), `GET .../documents/{docId}/content`
  (re-authorized byte stream, `Content-Disposition: attachment`). **Every path routes through
  `PatientAccessGuard`**, so document access inherits the §21 layer-6 gate: an assigned provider (or the patient)
  can download, an unassigned provider is a secure 404, cross-tenant is a secure 404. Providers/reviewers can't
  upload (not a write role → 403). Validation: non-empty, ≤ 10 MiB (app cap; `healthcloud.documents.max-size-bytes`),
  content-type allowlist (pdf/png/jpeg/gif/txt/csv) → clean 400. Multipart transport limits raised in
  `application.yml`; `MaxUploadSizeExceededException` mapped to 400 as a backstop. `scan_status` defaults CLEAN
  this slice (scanner is slice 15). `var/` git-ignored (never commit uploaded bytes).
- **Verified — automated:** `./mvnw -B clean verify` → **150 pass** (+8: `PatientDocumentApiIntegrationTest` ×6 —
  staff upload/list/download round-trips the exact bytes; a PATIENT manages their own record's docs but another
  patient's is a 404; an unassigned provider → 404 on list+download; a reviewer upload → 403; cross-tenant → 404;
  a disallowed content type → 400. `PatientDocumentRepositoryTest` ×2 — tenant-scoped lookup/listing; unique
  storage key. `PatientNestedEndpointGate…` extended to include `documents` in all three loops). Storage points
  at `target/test-documents` in the test so `clean` leaves nothing behind.
- **Verified — live (curl, existing dev DB; Flyway applied V13 on restart):** coordinator uploaded a text file to
  Sam Sample → 201 CLEAN, listed, downloaded and byte-diffed identical; provider Dana (assigned to Sam, not Mock)
  → **404** list+download on Mock's doc, **200** list on Sam, **403** upload on Sam (not a write role); a
  `application/zip` upload → **400**; a Green Valley coordinator reading NC Sam's docs → **404**. (Restarted the
  local backend onto slice-14 code first.)
- **Next:** slice 15 — fake malware scanner + quarantine download gate (additive; the column/lifecycle are in
  place); or slice 16 — documents UI. Strong moment for `/security-review`.

### 2026-09-14 — Phase 3, slice 13 ✅ (patient self-service consent — the patient controls their own sharing)
- **Why:** consent writes were staff-only (CARE_COORDINATOR/ORG_ADMIN). With the slice-12 patient-user↔patient
  link in place, a PATIENT can now record/revoke directives on their OWN record — the ethical heart of a
  "consent-aware" platform: the patient controls what is shared, from their own hand.
- **Backend (`ConsentDirectiveService`):** added `PATIENT` to the write roles, and switched the write-path
  patient check from a tenant-only lookup to `accessGuard.requireAccessibleInTenant(patientId)` — so a PATIENT
  may write only for the profile linked to their login (another patient → secure 404), while staff stay broad
  and providers/reviewers still can't write consent (not a write role → 403). Removed the now-unused
  `requirePatientInTenant`/`PatientRepository`. No migration, no API-shape change.
- **Frontend (`PatientDetailPage`):** split the single write flag into `canManageConsent` (PATIENT + staff) and
  `canManageCareTeam` (staff only) — so a patient sees the consent record/revoke controls on their own detail
  page but never the care-team assign/revoke controls.
- **Verified — automated:** `./mvnw -B clean verify` → **142 pass** (+3 `PatientSelfConsentApiIntegrationTest`:
  a PATIENT records+revokes on their own record; a PATIENT writing for another patient → 404; a PROVIDER writing
  consent → 403). Frontend `npm run typecheck` clean, `npm test` → **29 pass** (+1: a PATIENT sees the consent
  form + revoke but no care-team controls), `npm run build` OK.
- **Verified — live in browser** (patient@northcare, their own record Sam Sample): DOB showed **"Restricted"**;
  the patient recorded a GRANT / CARE_COORDINATION / DEMOGRAPHICS_CONTACT / ORGANIZATION directive → their own
  **DOB unmasked to `1985-03-14`** and the directive appeared ACTIVE with a Revoke they own; the care-team card
  showed members but no assign/revoke controls. (Restarted the local backend onto slice-13 code first; no reseed
  needed — no new migration.)
- **Next:** slice 14 fork — secure S3 documents (§19) is the last big Phase-3 pillar; or extend field-masking.
  Strong moment for `/security-review`.

### 2026-09-14 — Phase 3, slice 12 ✅ (patient self-service access — the patient-user↔patient link)
- **Why:** a PATIENT-role user was treated as *broad* — they could list/read every patient in the tenant (and,
  after slice 11, every request), because nothing narrowed them. And no login was tied to a patient profile, so
  "a patient sees only their own data" wasn't even possible. This closes that over-exposure and builds the
  patient-user↔patient link the docs had flagged as a prerequisite.
- **Migration `V12__patient_user_link.sql`:** nullable `patient.app_user_id` (FK to `app_user`) + a partial
  unique index `WHERE app_user_id IS NOT NULL` (a login maps to at most one profile). Entity gained
  `appUserId` + `setAppUserId`.
- **`PatientAccessGuard` (the single choke point) extended:** a new **patient-self** rule — a PATIENT who is
  neither broad nor a provider may reach only the patient row whose `app_user_id` is their user id (else secure
  404). Generalized list-scoping into `accessiblePatientIdsIfGated(caller, org)` → the id set a gated caller may
  see (provider → assigned; PATIENT → their one linked profile) or `Optional.empty()` for broad roles; both
  `PatientService.list` and `ServiceRequestService.list` now filter through it. Requests and consent inherit the
  patient-self gate automatically (single reads already route through `requireAccessibleInTenant`).
- **Seeder:** the `patient@` login is now patient **Sam Sample**'s own portal account (display name renamed to
  match) and linked to that profile (index 0). A tidy convergent demo — Sam is also the patient the provider and
  coordinator are assigned to. No change to the 3-patient set / counts other tests rely on.
- **Scope boundary:** CLAIMS_REVIEWER stays broad — meaningful business-need scoping needs claims (Phase 4), so
  it's premature here. This slice adds only the PATIENT-self rule.
- **Verified — automated:** `./mvnw -B clean verify` → **139 pass** (+7: `PatientSelfAccessApiIntegrationTest`
  ×4 — patient lists/reads only their own patient, own vs other 404, own-only requests, own vs other consent;
  `PatientRepositoryTest` ×2 — link lookup + partial-unique "one profile per login"; `DevDataSeederTest` ×1 —
  patient login is linked). Fixed two request tests that used "the first patient" so the patient participant now
  acts on the patient they're linked to (Sam). Frontend untouched (no API shape change — a patient just sees
  fewer rows).
- **Verified — live (curl, fresh DB reseed for V12):** patient@northcare's `/patients` returned **only Sam
  Sample** (coordinator saw all three); own record/consent → **200**, another patient (Fern) → **404**; another
  patient's requests → **404**.
- **Next:** slice 13 fork — secure S3 documents (§19); patient self-service consent (now unblocked); or extend
  field-masking. Good moment for `/security-review`.

### 2026-09-14 — Phase 3, slice 11 ✅ (object/relationship gate extended to service requests)
- **Why:** the §21 layer-6 gate protected the patient object (a provider reads only assigned patients → secure
  404), but requests *about* a patient were only tenant-scoped — so a PROVIDER could read or act on a request
  for a patient they aren't assigned to, side-stepping the gate via the `/requests` route. A request is "about"
  a patient, so it should inherit the patient's gate. Backend-only, no schema change.
- **The fix (reuse `PatientAccessGuard` — no new gate logic):** `ServiceRequestService` and
  `RequestAssignmentService` now inject the guard. A private `requireAccessibleRequest(id)` loads the request in
  tenant then calls `accessGuard.requireAccessibleInTenant(request.getPatientId())`; every single-request read
  (`getById`, `getHistory`, `getComments`, `getCurrentAssignment`) and the participant writes (`changeStatus`,
  `addComment`) route through it → secure 404 for an unreachable patient. `list()`: with `?patientId=` it calls
  the guard first (inaccessible patient → 404, consistent with `GET /patients/{id}`); unfiltered, a
  provider-gated caller is scoped to `activePatientIdsFor(...)` (new repo finder
  `findByOrganizationIdAndPatientIdInOrderByCreatedAtDesc`); broad roles (coordinator/admin) unchanged.
  `assign`/`assignable-users` are coordinator/admin-only, so the gate is a no-op there.
- **Scope boundary (still deferred):** finer PATIENT (own requests) and CLAIMS_REVIEWER (business-need) rules
  need the patient-user link / permission matrix. This slice extends exactly the existing PROVIDER relationship
  gate to requests, mirroring patient reads.
- **Verified — automated:** `./mvnw -B clean verify` → **132 pass** (+5 new `RequestRelationshipGateApiIntegrationTest`:
  assigned provider reaches the request everywhere; unassigned provider → 404 on get/history/comments/assignment
  and `?patientId=`, and is excluded from the unfiltered list; coordinator broad; provider can't comment/transition
  an unassigned patient's request). Fixed 2 pre-existing state-machine tests that used "the first patient in the
  list" and now needed a patient the provider is assigned to — pointed `createDraft` at the seeded Sam Sample
  (by name, so it works in either tenant). Frontend untouched (no API shape change — a provider just gets fewer
  rows, same as the patients list).
- **Verified — live (curl):** created a fresh patient + request as coordinator (provider Dana not assigned) →
  provider got **404** on get/history/comments/assignment and `?patientId=`, coordinator got **200**, and the
  provider still got **200** for a patient they are assigned to (Sam). Had to restart the local backend first —
  it was still running the slice-10 build.
- **Next:** slice 12 fork — secure S3 documents (§19); the permission-matrix / finer PATIENT+CLAIMS_REVIEWER
  rules; or extend field-masking. Good moment for `/security-review`.

### 2026-09-14 — Phase 3, slice 10 ✅ (care-team assignment management UI — assign/revoke the people that drive consent)
- **Why:** slices 4 & 7 built the provider- and coordinator-assignment record APIs, and slice 8 made the care
  team drive CARE_TEAM/PROVIDER consent — but there was no way to *see or change* the care team in the browser.
  This surfaces it on the patient detail page, right beside the slice-9 consent UI, closing the loop: manage the
  care team **and** the consent that depends on it in one place.
- **Backend (small, cohesive):** the assign APIs take a user id, so the UI needs a list of eligible people to
  pick from. Added `GET /api/v1/patients/{id}/provider-assignments/candidates` and
  `.../coordinator-assignments/candidates` → `AssignmentCandidateDto{userId, fullName}`: same-tenant users
  holding the required role (PROVIDER / CARE_COORDINATOR), **minus anyone already currently assigned**, sorted by
  name, minimum-necessary. Coordinator/admin-gated (mirrors the write gate) and routed through the same
  `PatientAccessGuard` (another tenant's patient → secure 404). Reuses the exact membership+role pattern the
  request module already uses; factored a small `isProvider`/`isCoordinator` helper out of the existing
  assignee-validation.
- **Frontend:** `api/types.ts` + `api/client.ts` gained the coordinator-assignment types, the assign/revoke
  calls for both tables, and the two candidate-list calls; new `src/relationship/useAssignments.ts` hooks (every
  care-team mutation invalidates both assignment lists, both candidate lists, and the patient + patients-list
  queries — so a masked field driven by a PROVIDER/CARE_TEAM directive flips live). Added a **Care team card**
  to `PatientDetailPage` — Providers and Coordinators sections, each a list of current members with a **Revoke**
  button and an **Assign** form (candidate picker + optional effective dates). All write controls gated to
  CARE_COORDINATOR/ORG_ADMIN (role-aware UI; backend still enforces).
- **Verified — automated:** backend `./mvnw -B clean verify` → **127 pass** (+5: candidate list returns
  eligible users, excludes the already-assigned and wrong-role, is coordinator/admin-gated, cross-tenant →
  secure 404 — across both assignment tests). Frontend `npm run typecheck` clean, `npm test` → **28 pass** (+4
  `PatientDetailPage.test.tsx`: renders care-team members; coordinator assigns from the candidate list;
  coordinator revokes; a non-write role sees no assign/revoke controls). `npm run build` OK.
- **Verified — live in browser** (coordinator@northcare, patient Mock Muller NC-0003): Care team card showed
  Dana Provider + Cory Coordinator (ACTIVE). Clicked **Revoke** on Dana → Providers went to "None assigned" and
  Dana reappeared in the "Add provider" picker (candidate list repopulated live). Selected Dana → **Assign** →
  Dana back as ACTIVE and the picker returned to "No one else available." Also confirmed the candidate/assign
  flow directly by curl. (Only one seeded provider per org, so the "no one else available" state is expected.)
- **Deferred → slice 11+:** secure S3 documents (§19); the function-permission matrix / `GET /requests?patientId=`
  gate; PROVIDER-scoped consent to an *un*assigned provider (candidates list eligible/assigned only).

### 2026-09-14 — Phase 3, slice 9 ✅ (consent management UI — the flagship is finally visible in the browser)
- **Why:** slices 1–8 built the whole consent/relationship/authorization system entirely on the backend; the
  last frontend work was slice 3 (showing "Restricted"). This slice surfaces the existing consent APIs so you
  can record a directive and watch a masked field flip live — the single most compelling thing to demo.
  **Frontend-only** — no new endpoints, no migration.
- **New patient detail page** (`src/patients/PatientDetailPage.tsx`, route `patients/:id`): a summary card
  (name · MRN · DOB or a muted "Restricted" · status) + a **Consent directives** card — a table of the current
  directives (effect/purpose/category/scope/status/effective dates) each with a **Revoke** button, and a
  **Record directive** form (RHF + Zod mirroring the backend: effect/purpose/dataCategory/scopeType selects,
  a provider picker shown only for PROVIDER scope sourced from the patient's provider-assignments, optional
  effective dates). Record + revoke are gated to CARE_COORDINATOR/ORG_ADMIN (role-aware UI; backend still
  enforces). Server errors (e.g. 409 on a stale revoke) surface via `ApiClientError` (message + Reference ID).
- **Plumbing:** `api/client.ts` gained `getPatient`, `listConsentDirectives`, `recordConsent`, `revokeConsent`,
  `listProviderAssignments`; `api/types.ts` gained the consent enums + `ConsentDirective`/`RecordConsentRequest`/
  `ProviderAssignment`; new `src/consent/useConsent.ts` hooks (record/revoke invalidate the directive list **and**
  the patient + patients-list queries, so a masked field updates immediately). Patient-list rows now link to the
  detail page; `App.tsx` has the `patients/:id` route.
- **Verified — automated:** frontend `npm run typecheck` clean, `npm test` → **24 pass** (+4
  `PatientDetailPage.test.tsx`: renders summary + a directive row; coordinator records a directive; masked DOB
  shows "Restricted"; a non-write role sees no form and no Revoke). Had to wrap the existing
  `PatientsPage.test` render in a `MemoryRouter` (the new name-link needs router context). `npm run build` OK
  (pre-existing chunk-size advisory only). Backend untouched.
- **Verified — live in browser** (coordinator@northcare): opened Sam Sample → DOB **"Restricted"** → recorded a
  GRANT / CARE_COORDINATION / DEMOGRAPHICS_CONTACT / ORGANIZATION directive → DOB **flipped to `1985-03-14`** and
  the directive row appeared ACTIVE → clicked **Revoke** → DOB back to **"Restricted"** and the list emptied.
  Full stack, end to end, visibly.
- **Deferred → slice 10+:** assignment management UI (provider + coordinator assign/revoke); a decision "explain"
  view; PROVIDER-scoped directives to *un*assigned providers (the picker lists assigned providers only); secure
  S3 documents.

### 2026-09-14 — Phase 3, slice 8 ✅ (CARE_TEAM consent scope wired — the §22.5 engine is complete across all three tiers)
- **What this closes:** the "known limitation" `ConsentPolicy` has carried since slice 2 — CARE_TEAM directives
  were filtered out (`scopeApplies` returned `false`) for lack of care-team data. Slice 7 added that data
  (`care_coordinator_assignment` + the existing `provider_patient_assignment`); this slice makes CARE_TEAM
  evaluable, so the **PROVIDER > CARE_TEAM > ORGANIZATION** specificity ladder now works end-to-end.
- **`ConsentPolicy` stays pure.** Added one parameter to `decide(...)`: `boolean actorOnCareTeam`; `scopeApplies`
  is now `CARE_TEAM -> actorOnCareTeam` (ORGANIZATION/PROVIDER unchanged). The policy still has zero DB/Spring
  deps — the caller supplies the fact, exactly like `today`.
- **New `CareTeamService`** (relationship pkg) — the one authority for "is this user on the patient's care
  team?": an in-force **ACTIVE provider assignment OR ACTIVE coordinator assignment** to that patient. Reuses
  `PatientAccessGuard.isActivelyAssigned` for the provider half and the coordinator-assignment repo for the
  coordinator half; depends only on the guard + a repo → no bean cycle.
- **`ConsentPolicyService`** computes `actorOnCareTeam` via `CareTeamService` and passes it into the policy in
  **both** paths — `decide` (the `/decision` endpoint) and `decideForActor` (the field-masking hook) — so
  masking honors CARE_TEAM consistently. Removed the now-stale "not evaluable" caveats from `ConsentPolicy`,
  `ConsentPolicyService`, and CLAUDE.md.
- **Verified — automated:** `./mvnw -B verify` → **122 tests pass**. `ConsentPolicyTest` 11→14 (+3: CARE_TEAM
  applies only to a member; CARE_TEAM DENY overrides ORG GRANT; PROVIDER GRANT overrides CARE_TEAM DENY; plus
  DENY-wins within CARE_TEAM — and every existing `decide` call updated for the new arg). +2
  `ConsentCareTeamDecisionApiIntegrationTest` (end-to-end, "same role, different result" by membership): a
  coordinator with broad access gets **DENY** on a CARE_TEAM grant while unassigned, then **GRANT via CARE_TEAM**
  once assigned; an assigned provider gets GRANT via CARE_TEAM.
- **Verified — live (curl):** recorded a CARE_TEAM grant on a fresh patient → coordinator decision **DENY None**
  (not on the team, despite broad access) → assigned the coordinator → decision **GRANT CARE_TEAM**.
- **No migration, no frontend change.** **Gotcha (recurring):** the machine keeps creating `" 2"` duplicate
  copies of compiled files under `target/` (e.g. `TestcontainersConfiguration 2.class`), which breaks Surefire
  / the jar repackage with a "wrong name" or "single main class" error. Fix: `./mvnw -B clean verify` (target/
  is git-ignored, so it never affects the commit). Prefer `clean verify` when a stray appears.
- **Deferred → later:** perf (batch care-team + consent lookups on list reads); the finer PATIENT/CLAIMS_REVIEWER
  rules; secure S3 documents; a consent/relationship management UI.

### 2026-09-14 — Phase 3, slice 7 ✅ (care_coordinator_assignment — the other half of the care team; §14.3, §22)
- **Why:** a CARE_TEAM-scoped consent directive (§22) applies to the patient's whole care team = the providers
  **and** coordinators actively assigned to them. We had the provider half (`provider_patient_assignment`, slice
  4); this slice adds the **coordinator half** as a data model. **Records only** — exactly like slice 4 did for
  providers; the CARE_TEAM consent wiring that consumes it is slice 8. Coordinators already have broad read
  access, so this changes no existing read behavior; it's purely additive.
- **`V11__care_coordinator_assignment.sql`:** mirrors V10 — tenant key `organization_id`, `patient_id`,
  `coordinator_user_id` (→ `app_user`), `assigned_by_user_id`, `status` (PENDING/ACTIVE/EXPIRED/REVOKED),
  `effective_from`/`to`, `assigned_at`, `ended_at`, `@Version`. Composite FK `(patient_id, organization_id) →
  patient` (§32.10); **partial unique index** `WHERE status IN ('ACTIVE','PENDING')` on `(patient_id,
  coordinator_user_id)` → at most one current per pair; indexes on `(org, patient)` and partial `(org,
  coordinator)` WHERE ACTIVE (for slice 8's care-team lookup).
- **`com.healthcloud.relationship` additions** (parallel to the provider assignment): `CareCoordinatorAssignment`
  (`revoke()`/`isCurrent()`), `CareCoordinatorAssignmentStatus`, tenant-safe repository, DTO (with
  `coordinatorName` + `expectedVersion`), `AssignCoordinatorRequest` / `RevokeCoordinatorAssignmentRequest`,
  `CareCoordinatorAssignmentService`, controller. Endpoints nested under the patient:
  `GET/POST /api/v1/patients/{id}/coordinator-assignments`, `POST …/{id}/revoke`.
- **Rules (same shape as provider assignment):** assign gated to CARE_COORDINATOR/ORG_ADMIN (else 403); target
  must be an active same-tenant **CARE_COORDINATOR** (else 400, no existence leak); duplicate current pair → 409;
  cross-tenant patient → secure 404; revoke optimistic-locked (stale → 409, non-current → 409 invalid-transition).
  The **list read routes through `PatientAccessGuard`** (the slice-6 rule — every patient-nested endpoint passes
  the gate).
- **`DevDataSeeder`:** the coordinator (Cory) is now assigned to patients 0 and 2 (Sam + Mock) per org — varied
  overlap with the provider baseline (provider → Sam + Fern) so slice 8's CARE_TEAM tier has demonstrable data.
  `admin` (Alex) is captured and used as the assigner.
- **Verified — automated:** `./mvnw -B verify` → **117 tests pass** (+7 `CareCoordinatorAssignmentApiIntegrationTest`
  mirroring the provider set: assign→ACTIVE+listed; revoke→REVOKED+delisted; duplicate→409; non-assigner→403;
  non-coordinator target→400; stale version→409; cross-tenant→404. +2 `CareCoordinatorAssignmentRepositoryTest`:
  two currents violate the partial unique index; revoking frees the pair. `PatientNestedEndpointGate…` extended
  to include `coordinator-assignments` in all three loops — the gate covers the new nested endpoint too).
  (Note: a stray `target/classes/…/HealthcloudApplication 2.class` build artifact — a Finder/editor copy, not in
  git — briefly broke the jar repackage; removed it. Source has no duplicate.)
- **Verified — live (curl, fresh `db-reset`):** V11 applied; NC-0001's seeded coordinator-assignment shows Cory
  ACTIVE; assign→201, duplicate→409, provider-as-target→400, revoke→200 then list empties; an unassigned
  provider GETs coordinator-assignments for NC-0003 → **404** (gate holds on the new endpoint).
- **No frontend change** (records only, like slice 4). **Deferred → slice 8:** wire CARE_TEAM into `ConsentPolicy`
  (compute care-team membership = active provider OR coordinator assignment, pass into the pure policy). Later
  forks: secure S3 documents; permission matrix incl. the `GET /requests?patientId=` gate; more field masking.

### 2026-09-14 — Phase 3, slice 6 ✅ (close the gate's back doors — the §21 layer-6 gate now covers the patient-nested endpoints)
- **The hole this closed:** slice 5 gated the patient read itself, but the endpoints *nested* under a patient
  (`consent-directives` list + `/decision`, `provider-assignments` list) resolved only the **tenant**, not the
  relationship. So an unassigned provider — 404'd on the patient — could still read that patient's consent set,
  probe consent via `/decision`, and enumerate their provider assignments, leaking existence + data **around**
  the gate. §21 says each layer is an independent check; a nested route must not be a way past it.
- **New `PatientAccessGuard`** (in `com.healthcloud.patient`) — the **single choke point** for "may this caller
  reach this patient at all?": load the patient in the caller's tenant (cross-tenant → secure 404), then a
  **PROVIDER without a broad coordinator/admin role** must be **actively assigned** (else secure 404). It owns
  the "actively assigned" query (`activePatientIdsFor`/`isActivelyAssigned`, moved out of
  `ProviderPatientAssignmentService`) and depends **only on repositories** — so every service can use it with
  no bean cycle. `isProviderGated(...)` also lives here now.
- **Wired the guard into every patient-scoped read:** `PatientService` (`getById`/list — refactored to the
  guard, behavior unchanged, its inline gate + direct PPA-service dep removed), `ConsentDirectiveService.list`,
  `ConsentPolicyService.decide` (the `/decision` endpoint; the low-level `decideForActor` masking hook is
  **not** re-gated — it runs after the gate has already passed), and `ProviderPatientAssignmentService.listCurrent`.
  Consent record/revoke + assignment assign/revoke stay coordinator/admin-gated (a provider is 403 there before
  any patient check), so the gate is a no-op for them — left on the plain tenant check.
- **Blast radius (fixed in-slice):** `ConsentDecisionApiIntegrationTest`'s two provider-reads-a-decision tests
  predate assignments (slice 2) — they now **assign the provider first** (same pattern slice 5 used for masking).
  No other test moved: `assign` checks role (403) before the patient, and reviewers aren't provider-gated.
- **Verified — automated:** `./mvnw -B verify` → **108 tests pass** (+3 `PatientNestedEndpointGateApiIntegrationTest`:
  unassigned provider → secure 404 on all three nested endpoints; assigned provider → 200 on all three;
  coordinator → 200 with no assignment). All prior tests green.
- **Verified — live (curl, fresh seed):** as the seeded provider (Dana, assigned to NC-0001/0002, **not**
  NC-0003): NC-0003 consent-list / decision / provider-assignments all **404**, NC-0001 all **200**; coordinator
  gets **200** on NC-0003's three endpoints. Matches the acceptance intent exactly.
- **No migration, no frontend change** — pure backend authorization-hardening. **Deferred → slice 7+:** requests
  *about* a patient (`GET /requests?patientId=`, a separate resource with its own participant model — still
  ungated); consent PROVIDER/CARE_TEAM scope wiring; `care_coordinator_assignment`; finer PATIENT/CLAIMS_REVIEWER
  rules; secure S3 documents.

### 2026-09-14 — Phase 3, slice 5 ✅ (object/relationship gate — "a provider sees only assigned patients"; §60)
- **The relationship (slice 4) now enforces access.** `PatientService` reads apply the §21 layer-6 gate: a
  **PROVIDER** (without a broad role) may read only patients they are **actively assigned** to — `getById`
  of an unassigned patient is a **secure 404** (§21.5, not a 403 that would confirm existence), and the list
  is filtered to assigned patients. **CARE_COORDINATOR/ORG_ADMIN keep broad tenant access**; a user with both
  a broad role and PROVIDER gets broad access. (PATIENT/CLAIMS_REVIEWER unchanged this slice — finer rules
  belong with the permission matrix / claims phase.)
- **`ProviderPatientAssignmentService`** gained `activePatientIdsFor(org, provider)` / `isActivelyAssigned(...)`
  (status ACTIVE **and** in force today — PENDING/expired don't grant access) + a provider-status repo finder.
  `PatientService` injects it (no bean cycle). The gate sits **above** consent/field-masking: an assigned
  provider still gets DOB masked by consent — two independent layers.
- **`DevDataSeeder`:** each org's provider (Dana Provider) is now assigned to **2 of its 3 patients** (Sam
  Sample + Fern Fixture; Mock Muller left unassigned) so the gate is demonstrable and the provider view isn't
  empty. Idempotent; `createMember`/`seedPatients` refactored to return the created rows.
- **Existing tests updated (blast radius):** `PatientApiIntegrationTest`'s two tenant-isolation tests now sign
  in as a **broad role** (coordinator/admin) so they keep testing tenant isolation, not the new gate;
  `PatientFieldMaskingApiIntegrationTest`'s provider-DOB test now **assigns the provider first** (required to
  read at all).
- **Verified — automated:** `./mvnw -B verify` → **105 tests pass** (+4 `PatientRelationshipGateApiIntegrationTest`:
  provider sees only assigned patients (list + getById), unassigned → secure 404; coordinator sees all;
  revoking removes provider access; the seeded provider has its 2 baseline assignments). Frontend unchanged
  (the gate is backend; the list just shows fewer rows).
- **Verified — live (curl + browser, fresh `db-reset` seed):** provider@northcare's patient list = NC-0001 +
  NC-0002 only (not NC-0003); direct read of NC-0003 → 404; coordinator sees all three. In the browser the
  provider's Patients page showed exactly the two assigned patients (DOBs still "Restricted" — consent layer).
- **Deferred → later:** gating the nested endpoints (consent/assignment/requests about a patient); consent
  PROVIDER/CARE_TEAM scope wiring; `care_coordinator_assignment`; finer PATIENT/CLAIMS_REVIEWER rules;
  time-based status sweeps; a relationship-management UI.

### 2026-09-14 — Phase 3, slice 4 ✅ (provider↔patient assignment — the care relationship record; §14.3)
- **`V10__provider_patient_assignment.sql`:** tenant key `organization_id`, `patient_id`, `provider_user_id`
  (→ `app_user`), `assigned_by_user_id`, `status` (PENDING/ACTIVE/EXPIRED/REVOKED §14.3), `effective_from`/`to`,
  `assigned_at`, `ended_at`, `@Version`. **Composite FK** `(patient_id, organization_id) → patient` (§32.10);
  **partial unique index** `WHERE status IN ('ACTIVE','PENDING')` on `(patient_id, provider_user_id)` → at most
  one *current* assignment per pair (the "already assigned" guard + race backstop; many providers per patient
  allowed). Indexes on `(org, patient)` and (partial) `(org, provider)` for the next slice's "my patients".
- **New `com.healthcloud.relationship` package:** `ProviderPatientAssignment` (effective-dated; `revoke()`,
  `isCurrent()`), `ProviderPatientAssignmentStatus`, tenant-safe repository, DTO, `AssignProviderRequest` /
  `RevokeProviderAssignmentRequest`, `ProviderPatientAssignmentService`, controller. Endpoints nested under
  the patient: `GET /api/v1/patients/{id}/provider-assignments` (current ACTIVE/PENDING), `POST …` (assign),
  `POST …/{assignmentId}/revoke`.
- **Rules:** assign gated to CARE_COORDINATOR/ORG_ADMIN (else 403); the target must be an active same-tenant
  **PROVIDER** (else 400, no existence leak — reuses the request-assignment participant-validation pattern);
  a duplicate current assignment for a pair → 409 CONFLICT; cross-tenant patient → secure 404; revoke is
  optimistic-locked (stale → 409, non-current → 409 INVALID_STATE_TRANSITION). Assign/revoke are recorded,
  never deleted (§14.3 auditable); a revoked pair can be re-assigned (new row).
- **Records only this slice** — the object/relationship access GATE that consumes these ("a provider reads
  only assigned patients", §12.1/§21 layer 6) is slice 5, so existing patient reads are unchanged and no
  existing test moved.
- **Verified — automated:** `./mvnw -B verify` → **101 tests pass** (+7 `ProviderPatientAssignmentApiIntegrationTest`:
  assign→ACTIVE+listed; revoke→REVOKED+delisted; duplicate→409; non-assigner→403; non-provider target→400;
  stale version→409; cross-tenant patient→404. +2 `ProviderPatientAssignmentRepositoryTest`: two currents for
  a pair violate the partial unique index; revoking frees the pair).
- **Verified — live (curl, real server):** assigned Dana Provider → ACTIVE; duplicate → 409; list shows one
  ACTIVE; revoke → REVOKED (`ended_at` stamped); list empties. Flyway applied V10 on a fresh start.
- **Deferred → slice 5+:** the read gate + baseline seed + existing-test updates; consent PROVIDER/CARE_TEAM
  wiring; `care_coordinator_assignment`; PENDING→ACTIVE/→EXPIRED sweeps (scheduler); UI.

### 2026-09-13 — Phase 3, slice 3 ✅ (field-level masking on the patient read — §23; the flagship, end-to-end)
- **The consent decision (slice 2) now shapes a real read.** `dateOfBirth` is consent-controlled
  (`DEMOGRAPHICS_CONTACT` / classification `CONFIDENTIAL`); name/MRN/status stay role-visible. The patient
  read's purpose is **backend-fixed** to `CARE_COORDINATION` (§21.4) — not client-chosen.
- **`DataClassification`** enum (§23.1: INTERNAL/CONFIDENTIAL/SENSITIVE_HEALTH_DATA/AUDIT_ONLY/SECURITY_SECRET)
  and **`PatientFieldPolicy`** (the field→category+classification map; the extension point for more fields).
- **`PatientDto`** is now field-safe: `dateOfBirth` nullable + **`maskedFields: List<String>`** (§23.3 — the
  client shows "Restricted" without ever receiving the value). `PatientDto.from` = unmasked (write responses —
  the caller supplied the data); `PatientDto.masked(...)` = read view. **`PatientService`** reads
  (`getById` + list) build the masked DTO by asking `ConsentPolicyService.decideForActor(org, actor, patient,
  purpose, category)` per consent-controlled field — **deny-by-default**, so a sensitive field is withheld
  unless an applicable consent GRANT exists.
- **`GlobalExceptionHandler`** unchanged from slice 2 (enum type-mismatch → 400 already added).
- **Frontend:** `Patient.dateOfBirth` is `string | null` + optional `maskedFields`; the Patients list renders
  the date or a muted **"Restricted"**. Create/edit form unchanged (writes still send DOB).
- **Verified — automated:** backend `./mvnw -B verify` → **92 tests pass** (+4 `PatientFieldMaskingApiIntegrationTest`:
  no consent → DOB null + `maskedFields=[dateOfBirth]`, write response NOT masked; ORG grant → DOB revealed;
  **list** masks DOB without consent; a PROVIDER-scoped DENY re-masks for that provider while another actor
  keeps the org grant). Existing patient tests still green (they key on MRN/name, never DOB). Frontend:
  typecheck clean, `npm test` → **20 pass** (+1: masked DOB renders "Restricted"), build OK.
- **Verified — live in browser:** signed in as coordinator, opened `/patients` → **every DOB showed
  "Restricted"** (deny-by-default). Granted a DEMOGRAPHICS_CONTACT/CARE_COORDINATION ORG directive for Sam
  Sample via the API → refreshed → **Sam's row showed `1985-03-14` while all others stayed "Restricted"** —
  consent-driven field masking working through the full stack.
- **Scope note / deferred:** only `dateOfBirth` is gated this slice (the map extends trivially); list masking
  does one consent lookup per row (fine at synthetic scale — batch later); §23.4 log/event masking is a
  Phase-7 audit concern (the API omission is done); relationship-based access + PROVIDER/CARE_TEAM full
  evaluation await the assignment tables.

### 2026-09-13 — Phase 3, slice 2 ✅ (consent + purpose decision engine — §22.5)
- **`ConsentPolicy`** (pure, no Spring/DB — same shape as `RequestTransitions`): implements §22.5 verbatim.
  `decide(actorUserId, purpose, dataCategory, directives, today) → ConsentDecision`. Steps: (1) applicable =
  status ACTIVE **and** in force today (`effective_from ≤ today ≤ effective_to`, re-checked here so a stale
  ACTIVE row past its end date is correctly not-in-force — covers the deferred time sweep) **and** scope
  applies to the actor **and** purpose+category match; (2) most-specific tier present — PROVIDER > CARE_TEAM >
  ORGANIZATION; (3) within that tier, **DENY wins**; (4) none → **deny by default**. Scope applicability:
  ORGANIZATION → always; PROVIDER → actor is the named provider (`scopeRefId == actor`); **CARE_TEAM → not
  yet evaluable** (no care-team data — documented limitation).
- **`ConsentDecision`** (record: effect, decidingScope, decidingDirectiveId, reason; `isGranted()`),
  **`ConsentDecisionDto`** (self-describing: echoes patientId/purpose/dataCategory), **`ConsentPolicyService`**
  (tenant-scoped → secure 404; actor = the calling session's user; loads ACTIVE directives + applies the
  policy — you can only ask "may **I** access this?"). Endpoint:
  `GET /api/v1/patients/{patientId}/consent-directives/decision?purpose=…&dataCategory=…`.
- **`GlobalExceptionHandler`** now maps `MethodArgumentTypeMismatchException` → **400 VALIDATION_FAILED**
  (general hardening; used by the decision endpoint's enum query params — an unknown value is a clean 400).
- **Scope note:** this is the CONSENT decision in isolation — it does **not** yet gate real resource reads or
  mask fields (that's slice 3), nor weigh role/relationship/business-need (§21.3 full pipeline, later). Purpose
  is a validated allowlist here (§21.4); once wired into reads it'll be fixed by the action, not client-chosen.
- **Verified — automated:** `./mvnw -B verify` → **88 tests pass** (+11 `ConsentPolicyTest`: deny-by-default;
  org grant/deny; provider grant/deny each overriding the opposite org tier; **two different providers (same
  role) get opposite results** — §60 at the policy level; expired & scheduled ignored; wrong-purpose not
  applicable; DENY-wins within a tier; CARE_TEAM not-yet-evaluable. +5 `ConsentDecisionApiIntegrationTest`:
  org grant visible to any actor; a provider DENY flips the same caller's decision; deny-by-default; bad
  purpose → 400; cross-tenant patient → 404).
- **Verified — live (curl, real server):** fresh patient → provider decision DENY (deny by default) → record
  ORG GRANT → GRANT via ORGANIZATION → add PROVIDER DENY naming the provider → same caller flips to DENY via
  PROVIDER; unknown purpose → 400.

### 2026-09-13 — Phase 3, slice 1 ✅ (consent directive lifecycle + versioning — the flagship begins)
- **`V9__consent_directive.sql`:** `consent_directive` — tenant key `organization_id`, `patient_id`,
  `directive_group_id` (links versions of one logical directive), `effect` GRANT/DENY, `purpose` (§22.2 ×5),
  `data_category` (§22.3 ×5), `scope_type` PROVIDER/CARE_TEAM/ORGANIZATION + `scope_ref_id` (the provider
  when PROVIDER-scoped), `effective_from/to`, `status` (§22.4 SCHEDULED/ACTIVE/REVOKED/EXPIRED/SUPERSEDED),
  domain `version` (per group) + `lock_version` (`@Version`, kept distinct). **Composite FK**
  `(patient_id, organization_id) → patient` (§32.10); CHECK constraints for every enum + the scope/ref pairing;
  **partial unique index** `WHERE status IN ('ACTIVE','SCHEDULED')` on the natural key
  `(org, patient, purpose, category, scope_type, COALESCE(scope_ref_id, <sentinel>))` → at most one *current*
  directive per logical key (COALESCE folds nullable scope so org/care-team currents also collide; also the
  race backstop). Indexes on `(org, patient)` and `directive_group_id`.
- **New `com.healthcloud.consent` package:** `ConsentDirective` (immutable/versioned; `supersede()`,
  `revoke()`, `isCurrent()`), the 5 enums, tenant-safe `ConsentDirectiveRepository`, `ConsentDirectiveDto`
  (exposes domain `version` + optimistic `expectedVersion`), `RecordConsentRequest`, `RevokeConsentRequest`,
  and **`ConsentDirectiveService`**. Endpoints nested under the patient:
  `GET /api/v1/patients/{patientId}/consent-directives` (current set, or `?includeHistory=true` for all
  versions oldest-first), `POST …/consent-directives` (record), `POST …/consent-directives/{id}/revoke`.
- **Versioned/supersede pattern reused (§31.7, §22.4):** recording a change to an existing natural key
  supersedes the current row (→ SUPERSEDED, `ended_at`) and inserts version+1 in the same group **in one
  transaction** (flush the supersede before the insert to honor the unique index — the assignment lesson);
  revocation flips the current row to REVOKED immediately, history retained. Grant is create-type (no client
  version; the natural-key upsert + unique index give safety); revoke is optimistic-locked (`expectedVersion`).
- **Authz (backend-enforced):** writes gated to CARE_COORDINATOR/ORG_ADMIN (staff recording consent on a
  patient's behalf — patient self-service deferred, needs a patient-user↔patient link); reads open to any
  same-tenant user this slice (masking is a later slice). Cross-tenant patient → secure 404. Scope mismatch /
  missing field → 400; revoking a non-current directive → 409 INVALID_STATE_TRANSITION; stale version → 409
  CONFLICT.
- **Scope note — NOT in this slice (deferred, tracked):** the policy evaluator that *reads* these directives
  (§21.3, §22.5 most-specific/DENY-wins/deny-by-default) → slice 2; field-level masking (§23); S3 documents;
  consent-lifecycle audit events (§22.6 → Phase 7); time-based SCHEDULED→ACTIVE/→EXPIRED sweeps (→ Phase 8);
  a consent UI.
- **Verified — automated:** `./mvnw -B verify` → **72 tests pass** (+9 `ConsentDirectiveApiIntegrationTest`:
  record→ACTIVE v1; re-record same key→v2 + prior SUPERSEDED, one current / two in history; revoke→REVOKED,
  leaves current set, kept in history; provider-scoped names the provider; read-only role 403 on write / 200
  on read; stale version→409 CONFLICT; non-current revoke→409 INVALID_STATE_TRANSITION; scope mismatch &
  missing field→400; cross-tenant patient→404. +2 `ConsentDirectiveRepositoryTest`: two currents for one
  natural key violate the partial unique index; superseding frees the slot).
- **Verified — live (curl, real server + CSRF):** recorded GRANT v1 (ACTIVE) → modified to DENY v2 (prior
  GRANT SUPERSEDED, current set = the single v2) → revoked (REVOKED, `ended_at` stamped, current set empty) →
  history shows `GRANT v1 SUPERSEDED` + `DENY v2 REVOKED`; reviewer write → 403 ACCESS_DENIED, reviewer read
  → 200. Flyway applied V9 on a fresh start (killed the stale pre-V9 instance first).

### 2026-09-13 — Phase 2, slice 8 ✅ (request assignment — Option A) — **Phase 2 COMPLETE**
- **`V8__request_assignment.sql`:** `request_assignment` (tenant key, `service_request_id`,
  `assignee_user_id`, `assigned_by_user_id`, `assignee_role`, `status` ACTIVE/SUPERSEDED, `assigned_at`,
  `ended_at`, `@Version`). **Composite FK** `(service_request_id, organization_id) → service_request` (§32.10);
  **partial unique index** `WHERE status='ACTIVE'` → at most one active assignment per request (also a
  concurrency backstop for racing assigns); active-assignee index for future "assigned to me".
- **`com.healthcloud.request`:** `RequestAssignment` (versioned; `supersede()`), `RequestAssignmentStatus`,
  repository, `RequestAssignmentDto`, `AssignableUserDto`, `AssignRequest` (`assigneeUserId`,
  `expectedVersion`), **`RequestAssignmentService`** (reads the `identity` repos to resolve/validate
  assignable users). Endpoints on the requests controller: `GET /{id}/assignment` (current active, or null),
  `GET /{id}/assignable-users` (coordinator/admin; same-tenant PROVIDER/CLAIMS_REVIEWER, minimum-necessary),
  `PUT /{id}/assignment` (assign/reassign).
- **Option A (chosen):** assignment is the **sole path to `ASSIGNED`**. Assigning a TRIAGED request advances
  it TRIAGED→ASSIGNED and appends a status-history row in **one transaction** (§31.6); reassigning an ASSIGNED
  request supersedes the prior row and swaps the assignee (status unchanged). A bare `PATCH /status` to
  ASSIGNED is now rejected `INVALID_STATE_TRANSITION` (409). Assigner = CARE_COORDINATOR/ORG_ADMIN (else 403);
  assignee must be a same-tenant provider/reviewer (else 400); cross-tenant request → secure 404;
  optimistic-locked on the request `expectedVersion` (stale → 409). Ineligible-assignee returns 400
  (`VALIDATION_FAILED`) without leaking whether the user exists.
- **Frontend:** client `getAssignment`/`listAssignableUsers`/`assign` + `useAssignment`/`useAssignableUsers`
  (enabled only for assigners)/`useAssign` hooks; an **Assignment card** on `RequestDetailPage` (current
  assignee + an assign/reassign selector for coordinators/admins, shown only when the status is TRIAGED/
  ASSIGNED). `transitions.ts` no longer offers ASSIGNED as a status button (Option A).
- **Verified — automated:** `./mvnw -B verify` → **61 tests** (+8 `RequestAssignmentApiIntegrationTest`:
  assign→ASSIGNED + one active row + history; reassign supersedes; non-assigner 403; ineligible assignee 400;
  can't assign before triage 409; bare status→ASSIGNED 409; stale version 409; cross-tenant 404; the state
  machine test now reaches ASSIGNED via the assign endpoint). Frontend: typecheck clean, `npm test` →
  **19 pass** (+3: shows assignee, coordinator assigns a triaged request, non-assigner sees no selector), build OK.
- **Verified — live in browser + curl:** assign a triaged request → ASSIGNED, timeline gains
  "TRIAGED → ASSIGNED · Assigned to Dana Provider"; **reassigned Dana → Riley through the UI**; the status
  actions correctly omit "Assign"; a manual PATCH to ASSIGNED returns 409. Flyway applied V8.
- **Deferred:** `provider_patient_assignment` / `care_coordinator_assignment` (patient-level relationships)
  and SLA/due-dates → Phase 3+/later. The assignee-relationship this slice records is what Phase 3's policy
  evaluator will consume.

### 2026-09-13 — Phase 2, slice 7 ✅ (request comments — the collaboration thread)
- **`V7__request_comment.sql`:** `request_comment` (tenant key `organization_id`, `service_request_id`,
  `author_user_id`, `body` ≤2000, `created_at`). **Composite FK** `(service_request_id, organization_id) →
  service_request(id, organization_id)` so a comment cannot attach to another tenant's request (§32.10);
  index `(organization_id, service_request_id)`.
- **`com.healthcloud.request`:** `RequestComment` (append-only, author + org stamped from context),
  `RequestCommentRepository` (tenant+request scoped, oldest-first), `RequestCommentDto`, `AddCommentRequest`
  (`@NotBlank @Size(max=2000)`). Two service methods (`addComment`/`getComments`) on `ServiceRequestService`.
- **Endpoints:** `POST /api/v1/requests/{id}/comments` (201; participant roles PATIENT/PROVIDER/
  CARE_COORDINATOR/ORG_ADMIN — read-only roles → 403; request must be in the caller's tenant else secure 404),
  `GET /api/v1/requests/{id}/comments` (same-tenant readers, oldest-first).
- **Frontend:** `api/client.ts` listComments/addComment; `useComments`/`useAddComment` hooks (invalidate the
  thread on add); a **Comments** card on `RequestDetailPage` (list + RHF/Zod add box ≤2000 chars, gated to
  participant roles — UI convenience; backend enforces). Surfaces `ApiClientError` message + Reference ID.
- **Verified — automated:** `./mvnw -B verify` → **53 tests pass** (+4 `RequestCommentApiIntegrationTest`:
  add+list oldest-first with two different participants; CLAIMS_REVIEWER → 403; cross-tenant add & read → 404;
  blank body → 400). Frontend: typecheck clean, `npm test` → **16 pass** (+3: renders thread, participant
  posts, read-only sees no box), build OK.
- **Verified — live in browser:** signed in as provider, opened a request, saw the comment thread (oldest
  first), **posted a comment through the UI** (CSRF handshake via the Vite proxy) → it appeared and the form
  reset. Confirmed end-to-end via curl too (201 add / list / blank→400) and that Flyway applied V7.
- **Deferred:** assignment (`request_assignment`) → slice 8; documents/consent/field-policy (Phase 3).

### 2026-09-13 — Phase 2, slice 6 ✅ (Requests UI — drive the whole workflow in the browser)
- **`src/requests/`:** `useRequests.ts` (list/one/create/change-status/history hooks with invalidation),
  `transitions.ts` (a client mirror of the §14.6 table + role rules — **UX only**; backend is the enforcer),
  `statusColor.ts`, `RequestsPage.tsx` (list + create form via RHF+Zod, native selects for patient/type/
  priority; create gated to create-roles), `RequestDetailPage.tsx` (fields + status + timeline from the
  history endpoint + transition buttons for allowed *and* role-authorized moves, sending `expectedVersion`
  and prompting for a reason on cancel/reject; surfaces `ApiClientError` incl. 409/403 + Reference ID).
- **`api/client.ts`:** listRequests/getRequest/createRequest/changeRequestStatus/getRequestHistory.
  **`App.tsx`:** `/requests` + `/requests/:id`. **`AppLayout`:** working "Requests" nav.
- **Verified — automated:** typecheck clean; `npm test` → **13 pass** (+6: RequestsPage list/create/role-gate,
  RequestDetailPage status+timeline / submit sends loaded version / patient sees no coordinator-only actions);
  build OK.
- **Verified — live in browser:** as coordinator, created a request → opened it → drove
  **DRAFT→SUBMITTED→TRIAGED→ASSIGNED→UNDER_REVIEW→APPROVED→CLOSED**, timeline grew to 7 entries, buttons
  changed per state, DRAFT offered no Cancel to the coordinator (correct §14.6), Reject revealed a required-
  reason field (Confirm disabled until filled), and CLOSED showed no further actions (terminal).
- **Deferred:** comments + assignment (slice 7); documents/consent/field-policy (Phase 3).

### 2026-09-13 — Phase 2, slice 5 ✅ (service-request state machine — the heart)
- **`PATCH /api/v1/requests/{id}/status`** `{ targetStatus, expectedVersion, reason? }` — controlled
  transitions per §14.6. In one transaction (§31.6): validate → update status (+`@Version` bump) → append a
  `request_status_history` row (`from→to`, actor, reason, correlationId). **`GET /api/v1/requests/{id}/history`**
  exposes the timeline.
- **`RequestTransitions`** encodes the exact §14.6 table (forward flow + cancellation) and the role rules:
  cancellation authority verbatim (patient: DRAFT/SUBMITTED/NEEDS_INFORMATION; coordinator/admin:
  SUBMITTED/TRIAGED/ASSIGNED/NEEDS_INFORMATION with a reason; provider: none); forward-transition roles are a
  documented synthetic MVP choice (refined once assignment exists). Terminal states (CANCELLED/CLOSED) have no
  outgoing moves.
- **New `ErrorCode.INVALID_STATE_TRANSITION` (409)** — distinct from a stale-version `CONFLICT`, so the UI can
  tell "not allowed now" from "someone else changed it". Reason mandatory for CANCELLED/REJECTED (else 400).
- **Verified:** `./mvnw -B verify` → **49 tests pass** (+7 `ServiceRequestStateMachineApiIntegrationTest`:
  full DRAFT→…→CLOSED with a 7-row history then terminal-409; illegal move → 409 INVALID_STATE_TRANSITION;
  stale version → 409 CONFLICT; provider approve → 403; patient cancels DRAFT → 200 / provider cancel → 403;
  reject needs a reason → 400/200; cross-tenant transition → 404).
- **Idempotency-Key: deferred (decided).** Optimistic `expectedVersion` already makes transitions safe against
  double-apply (a repeated move hits a stale version → 409); the canonical Idempotency-Key need is claim
  submission (Phase 4), where it'll be introduced.
- **Deferred:** assignment + comments/timeline (slice 6), Requests UI, audit events + assignee-relationship
  check on review (Phase 3 / assignment slice).

### 2026-09-13 — Phase 2, slice 4 ✅ (service request — create DRAFT + read)
- **`V6__service_request.sql`:** `service_request` (tenant key `organization_id`, `patient_id`, `type`,
  `status` default DRAFT, `priority` default NORMAL, `title`, `description`, `created_by`, `@Version`;
  CHECK constraints for the type/status/priority value sets) + `request_status_history` (append-only:
  from/to status, actor, reason, correlation_id, created_at). **Composite FK** `(patient_id,
  organization_id) → patient(id, organization_id)` so a request cannot reference another tenant's patient
  (§32.10); `UNIQUE(id, organization_id)` on the request for future child FKs; §32.11 indexes.
- **`com.healthcloud.request`:** enums (`ServiceRequestType` §14.4, `ServiceRequestStatus` §14.6,
  `ServiceRequestPriority`), `ServiceRequest` + `RequestStatusHistory` entities, tenant-safe repositories,
  `ServiceRequestService`, thin `ServiceRequestController`, DTO + validated create request.
- **Endpoints:** `POST /api/v1/requests` (201; creates a **DRAFT** for a patient in the caller's tenant),
  `GET /api/v1/requests` (optional `?patientId=`), `GET /api/v1/requests/{id}` (tenant-scoped → 404).
- **§31.6 one-transaction pattern:** create writes the request **and** its initial history row
  (`null → DRAFT`, actor + correlationId) atomically. Create gated to PATIENT/PROVIDER/CARE_COORDINATOR/
  ORG_ADMIN; patient must be in the caller's tenant (else secure 404).
- **Verified:** `./mvnw -B verify` → **42 tests pass** (+4 `ServiceRequestApiIntegrationTest`: create → DRAFT
  + one `null→DRAFT` history row; another tenant's patient → 404; reviewer → 403; blank title → 400).
- **Deferred to slice 5:** the controlled transition state machine (submit…close + cancellation, §14.6),
  optimistic-locking on transitions, Idempotency-Key. **Later:** assignment + comments/timeline, Requests UI,
  audit events + consent/field policy (Phase 3).

### 2026-09-13 — Phase 2, slice 3 ✅ (Patients UI — first visible business feature)
- **New deps (first use of the form stack):** `react-hook-form@7.88`, `zod@4.6`, `@hookform/resolvers@5.9`
  (resolvers v5 supports Zod 4). Lockfile committed for CI `npm ci`.
- **`src/patients/`:** `usePatients.ts` (TanStack Query `usePatients` list + `useCreatePatient` mutation that
  invalidates the list), `PatientsPage.tsx` — MUI table (name · MRN · DOB · status) + an **Add-patient form**
  (RHF + Zod, schema mirrors the backend Jakarta rules). The form shows **only for `CARE_COORDINATOR`/
  `ORG_ADMIN`** (role-aware UI mirroring the backend gate); other roles get the read-only list. Backend
  errors surfaced via `ApiClientError` (message + Reference ID / correlationId).
- **`api/client.ts`:** `listPatients()` + `createPatient()` (CSRF header auto-injected on POST).
  **`App.tsx`:** `/patients` route. **`AppLayout`:** working "Patients" nav for provider/coordinator/admin.
- **Verified — automated:** `npm run typecheck` clean; `npm test` → **7 pass** (+4 `PatientsPage.test.tsx`:
  list renders, form hidden for non-write roles, coordinator create calls the API, empty form → Zod
  "Required" and no submit); `npm run build` OK.
- **Verified — live in browser** (fresh `db-reset` seed): coordinator sees list + Add form, **created a
  patient** (appeared in the list, form reset), empty submit blocked by validation, **duplicate MRN → the
  409 message + Reference ID shown**; provider sees the **list but no Add form**. CSRF POST works through the
  Vite proxy end-to-end.
- **Deferred:** edit/deactivate UI (uses the existing PATCH + `expectedVersion`), pagination/search (Phase 9).

### 2026-09-13 — Phase 2, slice 2 ✅ (patient write path: create + update)
- **Endpoints:** `POST /api/v1/patients` (201 + Location) and `PATCH /api/v1/patients/{id}` (200), thin
  controller → `PatientService`. Requests: `PatientCreateRequest`/`PatientUpdateRequest` (Jakarta
  validation → 400 `VALIDATION_FAILED` with field details).
- **First backend role authorization:** writes require `CARE_COORDINATOR` or `ORG_ADMIN` via new
  `UserContextAccessor.requireAnyRole(...)` (reads stay open to any same-tenant user); disallowed → 403
  `ACCESS_DENIED`. Roles come from the backend-derived `UserContext`, never the client.
- **Tenant stamping on write:** `organizationId` is set from context on create — a client can't create in
  another tenant; cross-tenant `PATCH` → secure 404.
- **Conflicts (409):** duplicate MRN within a tenant (pre-checked, `ConflictException` + new
  `existsByOrganizationIdAndMedicalRecordNumber`); **optimistic locking** — client sends the `version` it
  last saw as `expectedVersion`, mismatch → 409 (nothing overwritten). `PatientDto` now exposes `version`;
  successful update flushes so the response carries the incremented version.
- **Verified:** `./mvnw -B verify` → **35 tests pass** (+6 `PatientWriteApiIntegrationTest`: create-as-coordinator
  → 201 & listed; provider → 403; duplicate MRN → 409; invalid → 400; stale version → 409; cross-tenant
  update → 404). Writes exercise the real CSRF cookie→header handshake for the first time on a business write.
- **Deferred on purpose:** Idempotency-Key (reserved for the retriable commands §31 names — create request /
  submit claim / start adjudication; a patient create doesn't need it); Patients UI (slice 3); audit/outbox
  on writes (Ph 3/7/8); consent/purpose + field masking (Phase 3).
- **Hardening (same day):** the service pre-checks (expected version, unique MRN) handle the common cases,
  but under a *true* write race two callers can both pass the pre-check and collide at the DB. Mapped the
  DB backstops — `ObjectOptimisticLockingFailureException` and `DataIntegrityViolationException` — to 409
  `CONFLICT` in `GlobalExceptionHandler` (generic message, no SQL/constraint leak), so that edge returns
  the right shape instead of a 500. Tests: `GlobalExceptionHandlerTest` (mapping + no-leak) and a repo test
  proving the `UNIQUE(organization_id, mrn)` constraint actually throws. `./mvnw -B verify` → **38 tests pass**.

### 2026-09-13 — Phase 2, slice 1 ✅ (patient profile — first tenant-owned business resource)
- **`db/migration/V5__patient.sql`:** `patient` table — tenant key `organization_id`, synthetic MRN,
  `full_name`, `date_of_birth`, status, `@Version`. Constraints: `UNIQUE(organization_id, mrn)` and
  `UNIQUE(id, organization_id)` (so future rows like `service_request` can use a **composite FK**
  including org id — §32.10, a bug can't cross tenants); index `(organization_id, status)` (§32.11).
- **`com.healthcloud.patient`:** `Patient` (holds `organizationId` as a plain UUID tenant key),
  `PatientRepository` (tenant-safe by design — every finder takes `organizationId`, no bare
  `findById` for business code), `PatientService` (reads via `UserContextAccessor.requireOrganizationId()`;
  cross-tenant → `NotFoundException` = **secure 404, not 403**), thin `PatientController`
  (`GET /api/v1/patients`, `GET /api/v1/patients/{id}`), `PatientDto`.
- **Seed:** `DevDataSeeder` now adds 3 synthetic patients per org (MRN prefixes `NC-`/`GV-`).
- **Fulfilled the Phase-1 deferral:** `PatientApiIntegrationTest` proves a NorthCare caller requesting a
  **real Green Valley patient id → 404** (code `NOT_FOUND`, no data leak), own-tenant read → 200, each
  tenant lists only its own patients, and unauth → 401. `PatientRepositoryTest` proves the
  `(id, organizationId)` lookup denies cross-tenant reads.
- **Verified:** `./mvnw -B verify` → **29 tests pass** (+1 repo, +3 API) and the jar packages.
- **Deferred on purpose (per §34.5):** patient access here is **tenant-scoped only**. Object-relationship
  checks (provider↔patient assignment) come with assignments; consent/purpose + **field-level masking**
  are Phase 3; audit + outbox events on access arrive with the §31.6 one-transaction write path (Ph 3/7/8);
  patient create/update (POST/PATCH) and the frontend Patients screen are the next slice.

### 2026-09-13 — Phase 1, slice 7 ✅ (CI pipeline + cross-tenant isolation proof) — **Phase 1 done**
- **First GitHub Actions CI** (`.github/workflows/ci.yml`), on push + PR to `main`, two independent jobs:
  **backend** (Temurin JDK 25 + Maven cache → `./mvnw -B verify`; runner's Docker powers the
  Testcontainers/real-Postgres tests) and **frontend** (Node 24 + npm cache → `npm ci` → `typecheck`
  → `test` → `build`). `concurrency` cancels superseded runs on the same ref.
- **Cross-tenant isolation acceptance proof** (§60), two layers, honestly scoped for Phase 1:
  - **HTTP** `TenantIsolationIntegrationTest` (RANDOM_PORT): each session sees only its own org via
    `/me` (NorthCare user never sees Green Valley and vice-versa); a spoof attempt with
    `?organizationId=…` + `X-Organization-Id` header is **ignored** — the tenant is derived from the
    session, so a browser cannot pick a different tenant.
  - **Tenant-key** `TenantIsolationRepositoryTest`: the `(organizationId, id)` lookup pattern every
    Phase-2 endpoint will use returns nothing for another tenant's row, and scoped listings never leak.
- **Verified:** `./mvnw -B verify` → **25 tests pass** (+2 HTTP, +1 repo) and the app jar packages;
  frontend `npm ci` + `typecheck` + `test` (3 pass) + `build` all green locally with the exact CI commands.
  The GitHub Actions run itself is verified on the first push (watched, not assumed).
- **Deferred on purpose (recorded):** the full "fetch another tenant's *business* record → secure 404"
  test needs a tenant-owned resource endpoint, which arrives with Phase 2's service requests. At Phase 1
  the only tenant-owned surface is the caller's own identity (`/me`), which is what the proof asserts.

### 2026-09-12 — Phase 1, slice 6 ✅ (React frontend shell — first visible UI)
- Scaffolded `frontend/`: Vite 8 + React 19 + TS 6, React Router 7, TanStack Query 5, MUI 9, Vitest + RTL.
- **Same-origin dev setup:** Vite proxies `/api` + `/actuator` → `:8080`, so the HttpOnly `SESSION` and
  readable `XSRF-TOKEN` cookies are first-party — no CORS, no tokens in JS (matches the BFF model).
- `api/client.ts`: typed `fetch` wrapper, `ApiClientError` (carries `code`/`correlationId`), auto-injects
  `X-XSRF-TOKEN` on state-changing calls. `useCurrentUser()` queries `/me` (401/403 not retried).
- Screens: `LoginPage` (dev-login dropdown of the 10 seeded users), `AppLayout` (top bar: email · org ·
  role chips · logout; role-aware nav placeholders), `HomePage` (identity card), `ProtectedRoute`
  (spinner → redirect to /login on 401 → else render), plus `ErrorScreen` (shows message + correlationId)
  and `DeniedPage`/`NotFoundPage`.
- **Verified:** `npm run typecheck` clean; `npm test` → **3 Vitest tests pass** (org/roles render, 401→login
  redirect, ErrorScreen shows correlationId). **Live in-browser:** unauth `/`→`/login`; sign in as
  `provider@northcare.example.org` → shell shows *NorthCare Health / PROVIDER / Dana Provider*; **logout POST
  returned 200** (CSRF header handshake works) → back to /login.
- Added `.claude/launch.json` (frontend dev server). Gotcha: MUI 9 `Stack` `alignItems` as a direct prop
  can fail typecheck with mixed children — put it in `sx` instead.

### 2026-09-12 — Phase 1, slice 5 ✅ (backend-derived request context + global error model)
- **`com.healthcloud.context`:** `UserContext` (immutable snapshot: user, org/tenant, roles),
  `UserContextFilter` (resolves the session principal → context, added after `AuthorizationFilter`,
  cleared per request), `UserContextAccessor` (`current()`, `requireUser()` → 401,
  `requireOrganizationId()` → 403). This is the trusted, backend-only source of caller/tenant identity.
- **`com.healthcloud.error`:** `ApiError` `{code, message, correlationId, details}`, `ErrorCode` enum,
  `ApiException`(+`NotFoundException`, `TenantContextRequiredException`), `GlobalExceptionHandler`
  (@RestControllerAdvice: ApiException, validation, missing-param, AccessDenied, catch-all 500 with a
  generic message — no internals leaked). Security-chain 401/403 now emit the same JSON via
  `RestAuthenticationEntryPoint`/`RestAccessDeniedHandler` (replaced the empty-body 401 entry point).
- **`CorrelationIdFilter`** (highest precedence, before security): reuses a *safe* inbound
  `X-Correlation-Id` or generates one, exposes it in the log MDC (`%X{correlationId}`) and echoes it
  on the response; hostile header values are rejected (anti log/response-injection).
- Refactored `/me` to build its DTO from `UserContext` (proves the context populates end-to-end; the
  response contract is unchanged).
- **Verified:** `./mvnw test` → **22 tests pass** (+4 `UserContextAccessorTest`, +4
  `ErrorContractIntegrationTest`; all 14 prior tests still green). Real-server tests confirm: 401 →
  `{code:UNAUTHENTICATED,...}` with an `X-Correlation-Id` header; an inbound id is echoed in header +
  body; an unsafe id is replaced; a missing required param → 400 `VALIDATION_FAILED` with field details.
- **Boot 4.1 gotcha handled:** Jackson 3 → `tools.jackson.databind.ObjectMapper` (annotations stay
  `com.fasterxml.jackson.annotation`). Recorded in CLAUDE.md.
- Not in this slice (later, on purpose): Idempotency-Key + optimistic-lock conflict responses (arrive
  with Phase 2 write endpoints); the React error UI (frontend shell slice).

### 2026-09-12 — Phase 1, slice 4 ✅ (authentication foundation: login + sessions + /me)
- Added `spring-boot-starter-security` + `spring-boot-starter-session-jdbc`; `V4__spring_session.sql`.
- `SecurityConfig`: session-based, 401 entry point (no redirect), CSRF via readable cookie +
  `CsrfCookieFilter`, logout at `/api/v1/logout`; `/actuator/health` + `/dev-login` public, rest authenticated.
- `DevLoginController` (`@Profile local`): email-only session login stand-in (NO password/MFA — ADR-018).
- `CurrentUserController` `/api/v1/me` + `CurrentUserService` → user → active membership → org → roles.
- **Verified:** `./mvnw test` → 14 tests pass (4 MockMvc logic + 1 real-server session lifecycle).
  Live: dev-login sets HttpOnly SESSION cookie, /me returns correct org/roles, session row in
  `spring_session`, /me without cookie → 401.
- ADR-018 records the dev-login stand-in and its honest limitations (Cognito+MFA later).
- Boot 4.1 gotchas handled: test annotations moved packages (`AutoConfigureMockMvc` →
  `...webmvc.test.autoconfigure`); Spring Session needs the **starter** module, not the raw library;
  session-cookie flow must be tested with a real server (RANDOM_PORT + JDK HttpClient), not MockMvc.

### 2026-09-12 — Phase 1, slice 3 ✅ (facilities + NorthCare/Green Valley demo seed)
- Flyway `V3__facilities.sql`: `facility`, `facility_membership` (+ entities/repositories in
  `com.healthcloud.organization`).
- `DevDataSeeder` (`@Profile("local")`, idempotent): seeds NorthCare Health + Green Valley Clinic,
  each with 1 facility and 5 users (patient/provider/coordinator/reviewer/admin) with memberships,
  roles, and facility links for provider/coordinator.
- `scripts/db-reset.sh`: wipe DB volume + fresh Postgres → deterministic reseed on next local run.
- **Verified:** `./mvnw test` → 9 tests pass (added 4 seeder tests). Ran app with `local` profile
  against dev DB and confirmed via psql: 2 orgs, 5 members each, correct roles/facilities.
- Gotcha handled: a stale slice-1 app instance was still on :8080; ensure port is free before
  launching, and wait for the seeder log line rather than a possibly-stale health response.

### 2026-09-12 — Phase 1, slice 2 ✅ (identity & organization data model)
- Flyway `V2__identity_and_organization.sql`: `organization`, `app_user`, `role` (7 roles seeded),
  `organization_membership` (tenant key), `user_role`. UUID PKs, `@Version` columns, unique email
  (case-insensitive), one-ACTIVE-membership-per-user partial unique index, tenant FKs.
- JPA entities + Spring Data repositories in `com.healthcloud.organization` and `com.healthcloud.identity`.
- Testing pattern established: **Testcontainers** (real PostgreSQL 17) via shared
  `TestcontainersConfiguration` + `@ServiceConnection`; tests never use the dev DB (CI-friendly).
- **Verified:** `./mvnw test` → 5 tests pass. Proves roles seeded, org/user/membership persist,
  org-scoped query returns only that tenant's rows, duplicate email rejected, second active membership rejected.
- Note: Testcontainers is **2.0.5** in Boot 4.1 → artifacts are `testcontainers-junit-jupiter` /
  `testcontainers-postgresql` (renamed with prefix in TC 2.0).

### 2026-09-12 — Phase 1, slice 1 ✅ (bootable app + Postgres + Flyway)
- Generated Spring Boot **4.1.0** / Java **25** project into `backend/` (Web, Actuator, Data JPA,
  PostgreSQL, Flyway, Validation) via Spring Initializr; Maven wrapper (`mvnw`).
- `application.yml`: DB connection, Flyway enabled, actuator health with DB details; `ddl-auto: validate`.
- `docker-compose.yml`: real `postgres:17` service (volume + healthcheck). `.env.example` added.
- First migration `V1__baseline.sql` (creates `platform_metadata`).
- **Verified:** `docker compose up -d postgres` (PostgreSQL 17.11 healthy) → `./mvnw spring-boot:run`
  → `/actuator/health` = UP with `db: UP` → Flyway V1 applied (success) → `platform_metadata` row present.
- Decision: local dev runs Postgres in Docker + app via `mvnw`; backend containerized later (Phase 10).

### 2026-09-12 — Phase 0 started
- Created clean repo root `~/Desktop/healthcloud` (lowercase, no space).
- Renamed `documents/` → `docs/`; source-of-truth PDF now at `docs/source-of-truth/`.
- Built full monorepo skeleton (backend/frontend/worker/infrastructure/api/docs/synthetic-data/scripts/.github).
- Wrote `CLAUDE.md` (rulebook), `docs/PLAN.md` (roadmap), this `docs/PROGRESS.md`.
- Drafted first ADRs (001 modular monolith, 002 shared-DB multi-tenancy, 004 Cognito+BFF) + ADR template/index.
- Added `README.md`, `.gitignore`, `docker-compose.yml` placeholder, OpenAPI + event-catalog stubs.
- `git init`, first commit, created **private** GitHub repo `Nikhil-Oggu/healthcloud`, pushed `main`. ✅
- Phase 0 complete.

## Environment status (2026-09-12) — ALL READY ✅
- ✅ Git, GitHub CLI (logged in as `Nikhil-Oggu`), VS Code, git identity.
- ✅ Java `25.0.4` (Homebrew `openjdk@25`), `javac` 25 — JAVA_HOME + PATH set in `~/.zshrc`.
- ✅ Node `24.21.0` (Homebrew `node@24`) + npm `11.19` — on PATH via `~/.zshrc`.
- ✅ Docker Desktop: daemon running (v29.7.2, aarch64), `hello-world` container ran; Compose v5.5.1.
- Maven: not global — using the per-project Maven wrapper (`mvnw`) instead, per baseline.
- Note: `~/.zshrc` created with a `HealthCloud dev env` block (openjdk@25 + node@24 on PATH); Docker Desktop appended its CLI-completions block.

## Definition of "slice done"
Functionality works + negative/security cases pass + tests written + docs updated + committed + this file updated.
