-- V30: Claim anomaly signal — a Phase-6 (advanced claims) detection record. SYNTHETIC data only.
-- An advisory signal that a claim looks suspicious (fraud/waste/abuse), produced by a deterministic, explainable
-- detector when a reviewer scans a claim. Tenant-owned and about a claim, so access inherits the patient
-- object/relationship gate (§21 layer 6, via PatientAccessGuard) through the claim — a resource nested under the
-- claim it concerns, exactly like adjudication.
--
-- Signals carry only claims-domain data (a coded type, a severity, and a short human-readable detail naming
-- other claim numbers / procedure codes) — NO clinical narrative and NO patient identifiers — so they are not
-- consent field-masked. Rows are immutable; a rescan REPLACES a claim's signals (delete + insert in one tx), so
-- scanning is idempotent — there is no version column.

CREATE TABLE claim_anomaly_signal (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID        NOT NULL REFERENCES organization (id),   -- tenant key
    claim_id              UUID        NOT NULL,                                -- the claim scanned
    signal_type           VARCHAR(40) NOT NULL,                                -- DUPLICATE_CLAIM / HIGH_TOTAL_CHARGE
    severity              VARCHAR(10) NOT NULL,                                -- LOW / MEDIUM / HIGH
    detail                VARCHAR(500) NOT NULL,                               -- human-readable, no PHI
    detected_by           UUID        NOT NULL,                                -- reviewer who ran the scan
    detected_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT claim_anomaly_severity_chk CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    -- Composite FK including organization_id: a signal cannot reference another tenant's claim (§32.10).
    CONSTRAINT fk_claim_anomaly_claim FOREIGN KEY (claim_id, organization_id)
        REFERENCES claim (id, organization_id)
);

CREATE INDEX ix_claim_anomaly_org_claim ON claim_anomaly_signal (organization_id, claim_id);
