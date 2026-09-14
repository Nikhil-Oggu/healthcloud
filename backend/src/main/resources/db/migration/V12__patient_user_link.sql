-- V12: Link a patient profile to the login (app_user) that IS that patient — the prerequisite for patient
-- self-service access (source-of-truth §12.1, §21). SYNTHETIC data only. A PATIENT-role user may reach only
-- the patient profile linked to them (the object/relationship gate, §21 layer 6, applied to the patient's own
-- record); the enforcement lives in PatientAccessGuard. The column is nullable: most patient profiles have no
-- portal login, and staff logins are never patient profiles.

ALTER TABLE patient
    ADD COLUMN app_user_id UUID REFERENCES app_user (id);

-- A login maps to at most one patient profile (globally — an app_user belongs to one org via membership).
-- Partial so the many profiles without a login are unconstrained.
CREATE UNIQUE INDEX ux_patient_app_user ON patient (app_user_id)
    WHERE app_user_id IS NOT NULL;
