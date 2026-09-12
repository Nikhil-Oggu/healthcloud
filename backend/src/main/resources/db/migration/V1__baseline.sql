-- V1 baseline migration.
-- Purpose: prove Flyway runs automatically on application startup and that the app can
-- write to PostgreSQL. Real domain tables (organization, app_user, role, ...) arrive in
-- the next Phase 1 slices. All data in this project is SYNTHETIC only.

CREATE TABLE platform_metadata (
    key        VARCHAR(100) PRIMARY KEY,
    value      VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO platform_metadata (key, value)
VALUES ('schema_baseline', 'phase-1-slice-1');
