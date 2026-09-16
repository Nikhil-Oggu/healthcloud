-- V38: Break-glass emergency access (Phase 7 slice 4). SYNTHETIC data only.
-- The HIPAA "break the glass" pattern: a PROVIDER who is NOT assigned to a patient can, in an emergency,
-- self-grant TIME-BOXED access by recording a justification (reason). A live grant makes that one patient (and
-- everything gated by them) reachable through PatientAccessGuard — overriding ONLY the object/relationship layer
-- (§21 layer 6), never tenant isolation. Every grant is written to the tamper-evident audit trail as a
-- BREAK_GLASS_INVOKED event; the free-text reason lives here on the grant (for after-the-fact review), never in
-- the PHI-free audit detail.
--
-- Tenant-owned via organization_id. A grant is IMMUTABLE — it is created and simply expires at expires_at (early
-- admin revocation is a later refinement) — so there is no version column. The composite FK to patient includes
-- organization_id (§32.10) so a grant cannot reference another tenant's patient.

CREATE TABLE break_glass_grant (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organization (id),  -- tenant key
    app_user_id     UUID         NOT NULL REFERENCES app_user (id),      -- the provider who broke the glass
    patient_id      UUID         NOT NULL,                               -- the patient reached
    reason          VARCHAR(500) NOT NULL,                               -- the emergency justification
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,                               -- the grant is inert after this instant
    -- Composite FK including organization_id: a grant cannot reference another tenant's patient (§32.10).
    CONSTRAINT fk_break_glass_patient FOREIGN KEY (patient_id, organization_id)
        REFERENCES patient (id, organization_id)
);

-- The active-grant lookup: an org's grants for one provider, still in force (expires_at > now).
CREATE INDEX ix_break_glass_org_user_expires ON break_glass_grant (organization_id, app_user_id, expires_at);
