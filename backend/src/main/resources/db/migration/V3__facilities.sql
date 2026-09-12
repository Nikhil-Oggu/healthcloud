-- V3: Facilities (lightweight first-class entities within an organization).
-- SYNTHETIC data only. Facilities belong to an organization; staff are linked to facilities
-- via facility_membership (which references an organization_membership).

CREATE TABLE facility (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL REFERENCES organization (id),
    name              VARCHAR(200) NOT NULL,
    facility_type     VARCHAR(50)  NOT NULL,
    synthetic_address VARCHAR(300),
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT facility_status_chk CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX ix_facility_org ON facility (organization_id);
CREATE UNIQUE INDEX ux_facility_org_name ON facility (organization_id, lower(name));

CREATE TABLE facility_membership (
    id                         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    facility_id                UUID        NOT NULL REFERENCES facility (id) ON DELETE CASCADE,
    organization_membership_id UUID        NOT NULL REFERENCES organization_membership (id) ON DELETE CASCADE,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_facility_membership UNIQUE (facility_id, organization_membership_id)
);
CREATE INDEX ix_facility_membership_facility   ON facility_membership (facility_id);
CREATE INDEX ix_facility_membership_membership ON facility_membership (organization_membership_id);
