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
  + outbox event, all atomic. Kafka publish happens only after commit.
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
- **DB up:** `docker compose up -d postgres`  ·  **DB reset (reseed):** `./scripts/db-reset.sh`
- **Run app (seeds demo data):** `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
- **Tests:** `cd backend && ./mvnw test` (uses Testcontainers → Docker must be running)
- **Health:** `curl localhost:8080/actuator/health` · **Login+me:**
  `curl -c j -X POST localhost:8080/api/v1/dev-login --data email=provider@northcare.example.org && curl -b j localhost:8080/api/v1/me`
- Only the **`local`** profile seeds demo data and exposes `dev-login`.
- **CI** (`.github/workflows/ci.yml`, on push + PR to `main`): a **backend** job (Temurin JDK 25 →
  `./mvnw -B verify`; the runner's Docker powers the Testcontainers tests) and a **frontend** job
  (Node 24 → `npm ci` → `typecheck` → `test` → `build`). Keep both green — don't merge red.
- **Frontend (needs the backend running):** `cd frontend && npm install` once, then `npm run dev`
  (Vite on **:5173**, proxies `/api` + `/actuator` → `:8080`, so cookies are same-origin — no CORS).
  Checks: `npm run typecheck`, `npm test` (Vitest), `npm run build`. Node runs from `openjdk@25`'s
  sibling `node@24` — use `export PATH="/opt/homebrew/opt/node@24/bin:$PATH"` in non-interactive shells.

## Current implementation (Phase 1–4 COMPLETE; Phase 5 COMPLETE — slices 1–12 done; MVP (Phase 0–5) feature-complete, engine AND UI. Phase 6 (advanced claims) IN PROGRESS — slices 1–17 done: prior authorization, wired into adjudication, + prior-auth UI (queue/detail/decisions + request form) + plan-prior-auth-requirement admin card, + referrals (backend: care-coordination aggregate + decision lifecycle; + UI: queue/detail/decisions + request form), + appeals (backend: dispute a claim's decision + resolution lifecycle; + UI: queue/detail/decisions + submit form; + overturn wired into re-adjudication), + claim anomaly signals (backend: a deterministic detector + reviewer scan; + UI: Anomalies card + Scan button on the claim detail page), + claim manual review (backend: open/resolve/cancel a review case on a claim; + UI: queue/detail/decisions + open form), + reprocessing (backend: batch re-adjudication of a coverage plan's claims after a config change; + UI: batch queue/detail + Run form), + provider network config (backend: which PROVIDERs are in a coverage plan's network — inert until wired into adjudication in a later slice) — see docs/PROGRESS.md for status)
- **Backend packages** under `com.healthcloud`: `organization` (Organization, Facility, FacilityMembership),
  `identity` (AppUser, Role, OrganizationMembership, UserRole), `auth` (SecurityConfig, DevLoginController,
  CurrentUserController/Service, CsrfCookieFilter), `context` (UserContext + UserContextAccessor/Filter),
  `error` (ApiError, ErrorCode, GlobalExceptionHandler, CorrelationId), `patient` (Patient CRUD:
  `GET/POST /api/v1/patients`, `GET/PATCH /api/v1/patients/{id}`, tenant-scoped → secure 404 cross-tenant;
  reads are relationship-gated (providers see only assigned patients; a PATIENT sees only their own profile —
  linked via the nullable `patient.app_user_id`; all via the shared `PatientAccessGuard`) and
  consent-field-masked — see the Authorization-layering + Field-masking conventions below),
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
  (`AssignmentCandidateDto{userId, fullName}`). `DevDataSeeder` assigns each provider to 2 of 3 patients and
  the coordinator to 2 of 3, and links the `patient@` login to patient Sam Sample (index 0) so a PATIENT user
  has their own profile for self-service),
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
  Not consent field-masked. **State machine (§Phase 4 submission/validation):** `PATCH /api/v1/claims/{id}/status`
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
  the network → `AssignmentCandidateDto`). **Inert config this slice** — the claim gains a rendering provider and
  the engine marks an out-of-network line `OUT_OF_NETWORK` in later slices (a plan with no network rows imposes no
  restriction; opt-in). Immutable (no `@Version`)),
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
  a covering authorization and re-adjudicating (slice 11) flips the line to COVERED. **Re-adjudication is versioned** (slice 11):
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
  slice-11 limitation carries over). Backend-only so far — the reprocessing UI is a later slice),
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
  clinical-summary/claim/referral→catalog FKs are satisfied).
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
- **Pure policy classes:** keep decision logic (state-machine transition tables, the consent evaluator, the
  anomaly detector) in a pure, unit-testable class with no Spring/DB deps — the exemplars are `RequestTransitions`
  (§14.6 moves), `ClaimTransitions`, `PriorAuthTransitions`, `ReferralTransitions`, `AppealTransitions`,
  `ClaimReviewTransitions` (the six state machines), `ConsentPolicy` (§22.5 consent+purpose), and
  `ClaimAnomalyDetector` (a **detector** — it emits a list of findings rather than gating a transition); a thin
  service loads data and applies the policy.
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
  Login is a **local dev stand-in** (email only, no password/MFA — ADR-018); Cognito+MFA come later.
- **Testing pattern:** real PostgreSQL via `TestcontainersConfiguration` (`@ServiceConnection`), imported with
  `@Import(TestcontainersConfiguration.class)`; repository/logic tests use `@SpringBootTest`; full HTTP/session
  flows use a real server (`webEnvironment = RANDOM_PORT`) + JDK `HttpClient` (not MockMvc). No mocks for data access.
  - **State-changing (POST/PATCH) tests must do the CSRF handshake:** log in, `GET /me` to obtain the readable
    `XSRF-TOKEN` cookie, then send it back as the `X-XSRF-TOKEN` header (dev-login itself is CSRF-exempt).
  - A `RANDOM_PORT` test may also `@Autowired` repositories to assert one-transaction side-effects (e.g. that a
    `request_status_history` row was written) — the test runs in the same context as the embedded server.
  - Every new tenant-owned resource gets a cross-tenant test proving another tenant's id → **secure 404**.
- **Frontend** (`frontend/`, Phase 1 slice 6+): Vite + React + TS, React Router 7, TanStack Query 5, MUI 9.
  Structure: `api/` (typed `fetch` client + `ApiClientError` + CSRF header injection), `auth/` (`useCurrentUser`
  querying `/api/v1/me`, `ProtectedRoute`, `LoginPage`), `layout/AppLayout`, `pages/`, `components/`. Auth =
  session cookie only — the SPA never holds tokens; login state = whether `/me` returns 200. Tests: Vitest +
  React Testing Library (mock the `api` object, keep the real `ApiClientError` for `instanceof`).

## Boot 4.1 notes (learned; avoid re-discovering)
- Testcontainers is **2.0.x** here → artifacts are `testcontainers-junit-jupiter` / `testcontainers-postgresql`.
- Spring Session needs the **starter** `spring-boot-starter-session-jdbc` (the raw library alone doesn't auto-configure).
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
  claim. The coverage-plan/exclusions/fee-schedule/eligibility admin UIs shipped in later slices — see
  `src/coverage/` next), and
  `src/coverage/` (Phase 5 slice 7 — the **coverage admin UI**: a plans list `coverage-plans` with a New-plan
  form (ORG_ADMIN; reads open to same-tenant staff), and a plan **detail** `coverage-plans/:id` with an
  **Exclusions card** — add via the reusable `MedicalCodePicker` / remove, ORG_ADMIN — reusing the slice-6 picker,
  and (slice 10) a **Fee schedule card** — the priced procedures (code + allowed amount), add via the picker + an
  allowed-amount field / remove, ORG_ADMIN — so the slice-9 fee schedule is manageable in the browser,
  and (Phase 6 slice 5) a **Prior-auth requirements card** — the procedures that require prior auth (a near-twin
  of the Exclusions card: code list + add via the picker / remove, ORG_ADMIN) — so the slice-2
  `plan_prior_auth_requirement` config is manageable in the browser.
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
- **Consent/field masking in the UI (Phase 3+):** the backend already withholds masked values, so the SPA only
  *displays* the state — render a "Restricted"/placeholder for a `null` consent-controlled field (named in
  `maskedFields`); never assume a field is present. This is display-only, not a security control.
- **Native `<input type="date">` in tests/automation:** set its value directly (ISO `yyyy-mm-dd`), not by typing.

## Repo layout
`backend/` `frontend/` `worker/` `infrastructure/{terraform,environments}` `api/openapi/`
`docs/{architecture,er-diagram,events,threat-model,adr,runbooks,evidence,learning,source-of-truth}/`
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
