-- V9: Consent directive — the flagship Phase 3 entity (source-of-truth §22, §32.5). SYNTHETIC data only.
-- A consent directive is the patient's recorded decision to GRANT or DENY access, scoped to a purpose,
-- a data category, and a scope (a specific provider / the care team / the whole organization).
--
-- Immutable + versioned (§22.4): an activated directive is never edited in place. "Changing" a directive
-- supersedes the current row (status → SUPERSEDED, ended_at stamped) and inserts a new version in the same
-- directive_group_id — so history is retained. Revocation flips the current row to REVOKED immediately but
-- never deletes prior versions. This mirrors the versioned/supersede pattern already used by
-- request_assignment (§31.7).
--
-- This slice persists the consent lifecycle only. The authorization POLICY EVALUATOR that consumes these
-- rows (§21.3 access decision, §22.5 conflict/specificity resolution) and field-level masking (§23) arrive
-- in later Phase 3 slices.

CREATE TABLE consent_directive (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    patient_id          UUID        NOT NULL,
    directive_group_id  UUID        NOT NULL,                                -- links the versions of one logical directive
    effect              VARCHAR(10) NOT NULL,
    purpose             VARCHAR(30) NOT NULL,
    data_category       VARCHAR(30) NOT NULL,
    scope_type          VARCHAR(20) NOT NULL,
    scope_ref_id        UUID,                                               -- the provider's user id when scope_type = PROVIDER; else null
    effective_from      DATE        NOT NULL,
    effective_to        DATE,                                               -- open-ended when null
    status              VARCHAR(20) NOT NULL,
    version             INTEGER     NOT NULL,                               -- the domain version number within the group (1-based)
    created_by          UUID        NOT NULL,                              -- app_user who recorded it
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,                                        -- set when superseded / revoked
    lock_version        BIGINT      NOT NULL DEFAULT 0,                     -- @Version optimistic-lock column (distinct from the domain version)
    CONSTRAINT consent_directive_effect_chk   CHECK (effect IN ('GRANT','DENY')),
    CONSTRAINT consent_directive_purpose_chk  CHECK (purpose IN
        ('CARE_COORDINATION','CLAIM_PROCESSING','DOCUMENT_REVIEW','APPOINTMENT_SUPPORT','BENEFIT_SUPPORT')),
    CONSTRAINT consent_directive_category_chk CHECK (data_category IN
        ('DEMOGRAPHICS_CONTACT','CARE_COORDINATION','CLINICAL_CONTEXT','CLAIMS_BENEFITS','DOCUMENTS')),
    CONSTRAINT consent_directive_scope_chk    CHECK (scope_type IN ('PROVIDER','CARE_TEAM','ORGANIZATION')),
    CONSTRAINT consent_directive_status_chk   CHECK (status IN
        ('SCHEDULED','ACTIVE','REVOKED','EXPIRED','SUPERSEDED')),
    -- A PROVIDER-scoped directive must name the provider; the other scopes must not.
    CONSTRAINT consent_directive_scope_ref_chk CHECK (
        (scope_type = 'PROVIDER' AND scope_ref_id IS NOT NULL)
        OR (scope_type <> 'PROVIDER' AND scope_ref_id IS NULL)),
    -- Composite FK including organization_id: a directive cannot attach to another tenant's patient (§32.10).
    CONSTRAINT fk_consent_directive_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id)
);

-- At most one CURRENT (ACTIVE or SCHEDULED) directive per logical natural key. COALESCE folds the nullable
-- scope_ref_id to a fixed sentinel so two organization/care-team-scoped currents also collide (Postgres treats
-- NULLs as distinct in a plain unique index). This is the enforced "one current per (patient, purpose,
-- category, scope)" invariant and the concurrency backstop for racing grants.
CREATE UNIQUE INDEX ux_consent_directive_current_natural ON consent_directive (
    organization_id, patient_id, purpose, data_category, scope_type,
    COALESCE(scope_ref_id, '00000000-0000-0000-0000-000000000000'))
    WHERE status IN ('ACTIVE','SCHEDULED');

-- Fast lookup of a patient's current directives (what the evaluator will scan) and full version history.
CREATE INDEX ix_consent_directive_org_patient ON consent_directive (organization_id, patient_id);
CREATE INDEX ix_consent_directive_group       ON consent_directive (directive_group_id);
