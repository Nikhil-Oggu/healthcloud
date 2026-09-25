# HealthCloud — Entity-Relationship Diagram

> Generated from the real schema (43 Flyway migrations → **50 application tables**, plus Spring Session's two
> tables and Flyway's history table). Rendered as [Mermaid](https://mermaid.js.org/) `erDiagram` blocks so they
> render on GitHub and stay version-controlled as text.
>
> To stay readable, the model is split by domain and each entity shows **key columns** (primary key, tenant key,
> foreign keys, and a few business columns), not every column. Timestamps/`version` columns are mostly omitted.

## The multi-tenancy pattern (read this first)

Every tenant-owned table carries an **`organization_id`** tenant key, and child tables **foreign-key *with* the
org** — i.e. `(child.parent_id, child.organization_id)` references `parent(id, organization_id)`, not just
`parent(id)`. This makes tenant isolation *structural*: a child row can never point at a parent in a different
tenant. In the diagrams below the relationship lines show the logical parent→child link; the composite
"`+ organization_id`" half of each foreign key is implied by this pattern rather than drawn on every edge.

Reference data that is **not** tenant-owned: `medical_code` (the global ICD-10-CM / CPT / HCPCS catalog) and
`role` are shared across all organizations and have no `organization_id`.

- [1. Tenancy & identity](#1-tenancy--identity)
- [2. Care coordination](#2-care-coordination)
- [3. Consent & clinical](#3-consent--clinical)
- [4. Coverage plans & configuration](#4-coverage-plans--configuration)
- [5. Claims & adjudication](#5-claims--adjudication)
- [6. Advanced claims](#6-advanced-claims)
- [7. Security, audit & events](#7-security-audit--events)

---

## 1. Tenancy & identity

An `organization` is the tenant. Users join an org through an `organization_membership`, which carries their
`user_role`s and optional `facility_membership`s.

```mermaid
erDiagram
    ORGANIZATION ||--o{ FACILITY : "has"
    ORGANIZATION ||--o{ ORGANIZATION_MEMBERSHIP : "has members"
    APP_USER ||--o{ ORGANIZATION_MEMBERSHIP : "belongs via"
    ORGANIZATION_MEMBERSHIP ||--o{ USER_ROLE : "granted"
    ROLE ||--o{ USER_ROLE : "assigned as"
    ORGANIZATION_MEMBERSHIP ||--o{ FACILITY_MEMBERSHIP : "scoped to"
    FACILITY ||--o{ FACILITY_MEMBERSHIP : "includes"

    ORGANIZATION {
        uuid id PK
        string name
        string status
    }
    APP_USER {
        uuid id PK
        string email
        string full_name
        string status
        string cognito_sub
    }
    ORGANIZATION_MEMBERSHIP {
        uuid id PK
        uuid organization_id FK
        uuid app_user_id FK
        string status
    }
    ROLE {
        uuid id PK
        string code
        string display_name
    }
    USER_ROLE {
        uuid id PK
        uuid organization_membership_id FK
        uuid role_id FK
    }
    FACILITY {
        uuid id PK
        uuid organization_id FK
        string name
        string facility_type
    }
    FACILITY_MEMBERSHIP {
        uuid id PK
        uuid facility_id FK
        uuid organization_membership_id FK
    }
```

> Roles/org are resolved fresh from these tables by email on every request — a Cognito login establishes
> identity only, never authority.

---

## 2. Care coordination

A `patient` may optionally link to an `app_user` login (self-service). Provider and coordinator **care-team
assignments** are what the relationship access gate checks. Service requests carry a status history, comments,
and an assignment.

```mermaid
erDiagram
    PATIENT ||--o{ PROVIDER_PATIENT_ASSIGNMENT : "assigned providers"
    PATIENT ||--o{ CARE_COORDINATOR_ASSIGNMENT : "assigned coordinators"
    PATIENT ||--o{ SERVICE_REQUEST : "subject of"
    PATIENT ||--o{ PATIENT_DOCUMENT : "has documents"
    APP_USER |o--o{ PATIENT : "may log in as"
    APP_USER ||--o{ PROVIDER_PATIENT_ASSIGNMENT : "is provider"
    APP_USER ||--o{ CARE_COORDINATOR_ASSIGNMENT : "is coordinator"
    SERVICE_REQUEST ||--o{ REQUEST_STATUS_HISTORY : "timeline"
    SERVICE_REQUEST ||--o{ REQUEST_COMMENT : "comments"
    SERVICE_REQUEST ||--o{ REQUEST_ASSIGNMENT : "assignment"

    PATIENT {
        uuid id PK
        uuid organization_id FK
        uuid app_user_id FK "nullable"
        string medical_record_number
        string full_name
        date date_of_birth "consent-masked"
        string status
    }
    PROVIDER_PATIENT_ASSIGNMENT {
        uuid id PK
        uuid organization_id FK
        uuid patient_id FK
        uuid provider_user_id FK
        string status
        date effective_from
        date effective_to
    }
    CARE_COORDINATOR_ASSIGNMENT {
        uuid id PK
        uuid organization_id FK
        uuid patient_id FK
        uuid coordinator_user_id FK
        string status
    }
    SERVICE_REQUEST {
        uuid id PK
        uuid organization_id FK
        uuid patient_id FK
        string type
        string status
        string priority
        string title
    }
    REQUEST_STATUS_HISTORY {
        uuid id PK
        uuid service_request_id FK
        string from_status
        string to_status
        uuid actor_user_id
    }
    REQUEST_COMMENT {
        uuid id PK
        uuid service_request_id FK
        uuid author_user_id
        string body
    }
    REQUEST_ASSIGNMENT {
        uuid id PK
        uuid service_request_id FK
        uuid assignee_user_id
        string assignee_role
        string status
    }
    PATIENT_DOCUMENT {
        uuid id PK
        uuid organization_id FK
        uuid patient_id FK
        string file_name
        string storage_key
        string scan_status
    }
```

---

## 3. Consent & clinical

Consent directives are versioned and evaluated deny-by-default. Clinical summaries point at a diagnosis in the
global `medical_code` catalog; their free-text `narrative` is the consent-masked field.

```mermaid
erDiagram
    PATIENT ||--o{ CONSENT_DIRECTIVE : "governs access to"
    PATIENT ||--o{ CLINICAL_SUMMARY : "documented by"
    MEDICAL_CODE ||--o{ CLINICAL_SUMMARY : "diagnosis"

    PATIENT {
        uuid id PK
        uuid organization_id FK
    }
    CONSENT_DIRECTIVE {
        uuid id PK
        uuid organization_id FK
        uuid patient_id FK
        uuid directive_group_id
        string effect "GRANT / DENY"
        string purpose
        string data_category
        string scope_type
        date effective_from
        date effective_to
        string status
        int version
    }
    CLINICAL_SUMMARY {
        uuid id PK
        uuid organization_id FK
        uuid patient_id FK
        string summary_type
        date encounter_date
        string diagnosis_code_system FK
        string diagnosis_code FK
        string narrative "consent-masked"
    }
    MEDICAL_CODE {
        uuid id PK
        string code_system "PK part"
        string code "PK part"
        string description
        bool active
    }
```

> `medical_code` is global reference data (no `organization_id`); it is keyed by `(code_system, code)`, which is
> the composite target the clinical/claim/plan tables foreign-key to.

---

## 4. Coverage plans & configuration

A `coverage_plan` holds the benefit parameters the adjudication engine applies, plus four kinds of per-plan
configuration. A `patient_eligibility` row enrolls a patient in a plan for an effective-dated period.

```mermaid
erDiagram
    COVERAGE_PLAN ||--o{ PATIENT_ELIGIBILITY : "enrolls"
    PATIENT ||--o{ PATIENT_ELIGIBILITY : "enrolled in"
    COVERAGE_PLAN ||--o{ PLAN_EXCLUSION : "excludes"
    COVERAGE_PLAN ||--o{ PLAN_FEE_SCHEDULE : "prices"
    COVERAGE_PLAN ||--o{ PLAN_PRIOR_AUTH_REQUIREMENT : "requires auth for"
    COVERAGE_PLAN ||--o{ PLAN_NETWORK_PROVIDER : "network"
    MEDICAL_CODE ||--o{ PLAN_EXCLUSION : "procedure"
    MEDICAL_CODE ||--o{ PLAN_FEE_SCHEDULE : "procedure"
    MEDICAL_CODE ||--o{ PLAN_PRIOR_AUTH_REQUIREMENT : "procedure"
    APP_USER ||--o{ PLAN_NETWORK_PROVIDER : "in-network provider"

    COVERAGE_PLAN {
        uuid id PK
        uuid organization_id FK
        string plan_code
        string name
        string plan_type "HMO/PPO/EPO/HDHP"
        numeric deductible_amount
        numeric coinsurance_rate
        numeric copay_amount
        numeric out_of_pocket_max
        bool active
    }
    PATIENT_ELIGIBILITY {
        uuid id PK
        uuid organization_id FK
        uuid patient_id FK
        uuid coverage_plan_id FK
        string member_id
        date effective_from
        date effective_to
    }
    PLAN_EXCLUSION {
        uuid id PK
        uuid coverage_plan_id FK
        string code_system FK
        string code FK
    }
    PLAN_FEE_SCHEDULE {
        uuid id PK
        uuid coverage_plan_id FK
        string code FK
        numeric allowed_amount
    }
    PLAN_PRIOR_AUTH_REQUIREMENT {
        uuid id PK
        uuid coverage_plan_id FK
        string code FK
    }
    PLAN_NETWORK_PROVIDER {
        uuid id PK
        uuid coverage_plan_id FK
        uuid provider_user_id FK
    }
```

---

## 5. Claims & adjudication

A `claim` owns coded `claim_line`s and a status history. Adjudicating it writes an immutable, versioned
`adjudication` (header + per-line breakdown). A `benefit_accumulator` carries the deductible/out-of-pocket met
across claims per `(patient, plan, year)`.

```mermaid
erDiagram
    PATIENT ||--o{ CLAIM : "for"
    APP_USER |o--o{ CLAIM : "rendering provider"
    CLAIM ||--o{ CLAIM_LINE : "line items"
    CLAIM ||--o{ CLAIM_STATUS_HISTORY : "timeline"
    MEDICAL_CODE ||--o{ CLAIM_LINE : "procedure"
    CLAIM ||--o{ ADJUDICATION : "adjudicated as"
    ADJUDICATION ||--o{ ADJUDICATION_LINE : "breakdown"
    COVERAGE_PLAN ||--o{ ADJUDICATION : "under plan"
    PATIENT ||--o{ BENEFIT_ACCUMULATOR : "accumulates"
    COVERAGE_PLAN ||--o{ BENEFIT_ACCUMULATOR : "per plan/year"

    CLAIM {
        uuid id PK
        uuid organization_id FK
        uuid patient_id FK
        uuid rendering_provider_id FK "nullable"
        string claim_number
        string status
        date service_date
        numeric total_charge_amount
    }
    CLAIM_LINE {
        uuid id PK
        uuid claim_id FK
        int line_number
        string procedure_code_system FK
        string procedure_code FK
        int units
        numeric charge_amount
    }
    CLAIM_STATUS_HISTORY {
        uuid id PK
        uuid claim_id FK
        string from_status
        string to_status
        string reason
    }
    ADJUDICATION {
        uuid id PK
        uuid organization_id FK
        uuid claim_id FK
        int adjudication_version
        string outcome
        uuid coverage_plan_id FK
        numeric total_allowed_amount
        numeric total_plan_paid_amount
        numeric total_member_responsibility
    }
    ADJUDICATION_LINE {
        uuid id PK
        uuid adjudication_id FK
        int line_number
        string outcome
        numeric allowed_amount
        numeric copay_amount
        numeric deductible_applied_amount
        numeric coinsurance_amount
        numeric plan_paid_amount
        numeric member_responsibility
    }
    BENEFIT_ACCUMULATOR {
        uuid id PK
        uuid patient_id FK
        uuid coverage_plan_id FK
        int benefit_year
        numeric deductible_met
        numeric out_of_pocket_met
    }
```

---

## 6. Advanced claims

Prior authorizations, referrals, appeals, and manual reviews each follow the same aggregate + status-history
shape. Anomaly signals are advisory findings on a claim; reprocessing batches re-adjudicate a plan's claims.

```mermaid
erDiagram
    PATIENT ||--o{ PRIOR_AUTHORIZATION : "requested for"
    COVERAGE_PLAN ||--o{ PRIOR_AUTHORIZATION : "under plan"
    MEDICAL_CODE ||--o{ PRIOR_AUTHORIZATION : "procedure"
    PRIOR_AUTHORIZATION ||--o{ PRIOR_AUTHORIZATION_STATUS_HISTORY : "timeline"
    PATIENT ||--o{ REFERRAL : "for"
    MEDICAL_CODE ||--o{ REFERRAL : "reason (diagnosis)"
    REFERRAL ||--o{ REFERRAL_STATUS_HISTORY : "timeline"
    CLAIM ||--o{ APPEAL : "disputed by"
    PATIENT ||--o{ APPEAL : "on behalf of"
    APPEAL ||--o{ APPEAL_STATUS_HISTORY : "timeline"
    CLAIM ||--o{ CLAIM_REVIEW : "reviewed by"
    PATIENT ||--o{ CLAIM_REVIEW : "for"
    CLAIM_REVIEW ||--o{ CLAIM_REVIEW_STATUS_HISTORY : "timeline"
    CLAIM ||--o{ CLAIM_ANOMALY_SIGNAL : "flagged by"
    COVERAGE_PLAN ||--o{ REPROCESSING_BATCH : "scope"
    REPROCESSING_BATCH ||--o{ REPROCESSING_ITEM : "per claim"
    CLAIM ||--o{ REPROCESSING_ITEM : "reprocessed"

    PRIOR_AUTHORIZATION {
        uuid id PK
        uuid patient_id FK
        uuid coverage_plan_id FK
        string procedure_code FK
        string auth_number
        string status
    }
    REFERRAL {
        uuid id PK
        uuid patient_id FK
        string reason_code FK
        string referral_number
        string specialty
        string status
    }
    APPEAL {
        uuid id PK
        uuid claim_id FK
        uuid patient_id FK
        string appeal_number
        string status
        string reason
    }
    CLAIM_REVIEW {
        uuid id PK
        uuid claim_id FK
        uuid patient_id FK
        string review_number
        string status
        string resolution
    }
    CLAIM_ANOMALY_SIGNAL {
        uuid id PK
        uuid claim_id FK
        string signal_type
        string severity
        string detail
    }
    REPROCESSING_BATCH {
        uuid id PK
        uuid coverage_plan_id FK
        string batch_number
        string status
    }
    REPROCESSING_ITEM {
        uuid id PK
        uuid reprocessing_batch_id FK
        uuid claim_id FK
        string outcome
    }
```

> Each status-history table (`*_status_history`) is append-only and shares the shape
> `{ from_status, to_status, actor_user_id, reason, correlation_id, created_at }` — omitted above for brevity.

---

## 7. Security, audit & events

The audit trail is append-only and tamper-evident (a per-org HMAC hash chain, with `audit_chain_head` holding
the chain tip). Break-glass grants time-boxed emergency access. The outbox/notification/dead-letter tables are
the event-driven backbone.

```mermaid
erDiagram
    ORGANIZATION ||--o{ AUDIT_EVENT : "records"
    APP_USER ||--o{ AUDIT_EVENT : "actor"
    ORGANIZATION ||--o{ AUDIT_CHAIN_HEAD : "chain tip"
    PATIENT ||--o{ BREAK_GLASS_GRANT : "emergency access to"
    APP_USER ||--o{ BREAK_GLASS_GRANT : "granted to / revoked by"
    ORGANIZATION ||--o{ OUTBOX_EVENT : "emits"
    ORGANIZATION ||--o{ DEAD_LETTER_EVENT : "failed messages"
    APP_USER |o--o{ DEAD_LETTER_EVENT : "replayed by"
    ORGANIZATION ||--o{ CLAIM_ADJUDICATION_NOTIFICATION : "feed"

    AUDIT_EVENT {
        uuid id PK
        uuid organization_id FK
        uuid actor_user_id FK
        string action
        string resource_type
        uuid resource_id
        string outcome
        int sequence_no
        string prev_hash
        string entry_hash
    }
    AUDIT_CHAIN_HEAD {
        uuid organization_id FK
        int next_sequence
        string last_hash
    }
    BREAK_GLASS_GRANT {
        uuid id PK
        uuid organization_id FK
        uuid patient_id FK
        uuid app_user_id FK
        string reason
        timestamptz expires_at
        timestamptz revoked_at
        uuid revoked_by FK
    }
    OUTBOX_EVENT {
        uuid id PK
        uuid organization_id FK
        string aggregate_type
        uuid aggregate_id
        string event_type
        timestamptz published_at "null = pending"
    }
    DEAD_LETTER_EVENT {
        uuid id PK
        uuid organization_id FK
        string dlt_topic
        uuid event_id
        string exception_type
        timestamptz replayed_at
        uuid replayed_by FK
    }
    CLAIM_ADJUDICATION_NOTIFICATION {
        uuid id PK
        uuid organization_id FK
        uuid event_id
    }
```

> Audit events are **permanent** — never purged (that would break the hash chain). Data retention purges only
> long-expired operational rows (e.g. `break_glass_grant`), and the purge is itself audited.

---

See also the **[architecture diagrams](../architecture/architecture.md)** for the runtime/deployment view, and
the main **[README](../../README.md)** for the project overview.
