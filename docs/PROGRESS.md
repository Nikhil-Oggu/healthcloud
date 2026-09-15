# PROGRESS.md — HealthCloud running log

> The **diary** of the project. Updated at the end of every session (by the `/wrap` command once it
> exists, or manually). Read this + `CLAUDE.md` + `docs/PLAN.md` at the start of every session.

## Current position
- **Phase:** 0 ✅ · Environment ✅ · Phase 1 COMPLETE ✅ · Phase 2 COMPLETE ✅ (slices 1–8) ·
  **Phase 3 IN PROGRESS 🚧 (slice 1 ✅ consent lifecycle · 2 ✅ decision engine §22.5 · 3 ✅ field masking §23 ·
  4 ✅ provider↔patient record §14.3 · 5 ✅ object/relationship gate on patient reads §21 layer 6 ·
  6 ✅ gate applied to the patient-nested endpoints — single `PatientAccessGuard` choke point ·
  7 ✅ care_coordinator_assignment record §14.3 — the other half of the care team ·
  8 ✅ CARE_TEAM consent scope wired — the §22.5 engine is now complete across all three tiers ·
  9 ✅ consent management UI — patient detail page + record/revoke directives, the flagship is now visible ·
  10 ✅ care-team assignment management UI — assign/revoke providers + coordinators on the patient detail page,
  backed by new candidate-picker endpoints ·
  11 ✅ object/relationship gate extended to service requests — a request is gated by its patient, so a provider
  reaches only requests about assigned patients ·
  12 ✅ patient self-service access — patient-user↔patient link (`patient.app_user_id`); a PATIENT sees only their
  own record + requests + consent ·
  13 ✅ patient self-service consent — a PATIENT records/revokes directives on their OWN record; the §22.5 engine
  is now driven from the patient's own hand ·
  14 ✅ secure documents part 1 — patient-scoped document metadata + a storage abstraction (local-FS stand-in for
  private S3) + gated upload/download; access inherits the `PatientAccessGuard` gate ·
  15 ✅ document malware scan + quarantine — a fake scanner (EICAR) flags uploads QUARANTINED and the download
  gate withholds anything not CLEAN ·
  16 ✅ documents UI — a Documents card on the patient detail page: upload, list with scan-status chips, and
  download of CLEAN files; the §19 loop is now visible end-to-end in the browser)**
- **Repo:** https://github.com/Nikhil-Oggu/healthcloud (private, branch `main`)
- **Next up:** **Phase 3 is essentially complete** — the remaining candidates are optional polish before the MVP
  clinical/claims work (Phase 4): **extend field-masking** to more resources; **consent-lifecycle audit** (§22.6,
  naturally lands with the Phase 7 audit chain); or a **decision "explain" view**. **Strong moment to run
  `/security-review`** on the now-broad authorization stack (tenant + role + relationship on patients, requests &
  documents + PATIENT-self + patient-self consent writes + field masking + malware quarantine) before moving to
  Phase 4. Plan each slice before building. (Deferred: CLAIMS_REVIEWER business-need scoping — premature until
  claims exist, Phase 4; provider/coordinator assignment PENDING→ACTIVE/→EXPIRED time sweeps — scheduler,
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
