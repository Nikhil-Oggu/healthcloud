-- V2: Core identity & organization data model (multi-tenant backbone).
-- SYNTHETIC data only. Tenant = organization; a user's tenancy is established by
-- organization_membership. app_user is a global identity (platform admins are cross-org).

-- ---------------------------------------------------------------------------
-- organization : a tenant (a fictional healthcare organization)
-- ---------------------------------------------------------------------------
CREATE TABLE organization (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT organization_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED'))
);

-- ---------------------------------------------------------------------------
-- app_user : a person's global identity (login identity attached later via cognito_sub)
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(320) NOT NULL,
    full_name   VARCHAR(200) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'INVITED',
    cognito_sub VARCHAR(100),                       -- populated when auth is added (later slice)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT app_user_status_chk CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DISABLED'))
);
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));
CREATE UNIQUE INDEX ux_app_user_cognito_sub ON app_user (cognito_sub) WHERE cognito_sub IS NOT NULL;

-- ---------------------------------------------------------------------------
-- role : fixed role catalog (reference data)
-- ---------------------------------------------------------------------------
CREATE TABLE role (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(40)  NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL
);

INSERT INTO role (code, display_name) VALUES
    ('PATIENT',           'Patient'),
    ('PROVIDER',          'Provider'),
    ('CARE_COORDINATOR',  'Care Coordinator'),
    ('CLAIMS_REVIEWER',   'Claims Reviewer'),
    ('ORG_ADMIN',         'Organization Administrator'),
    ('PLATFORM_ADMIN',    'Platform Administrator'),
    ('AUDITOR',           'Auditor');

-- ---------------------------------------------------------------------------
-- organization_membership : links a user to an organization (the tenant key)
-- ---------------------------------------------------------------------------
CREATE TABLE organization_membership (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization (id),
    app_user_id     UUID        NOT NULL REFERENCES app_user (id),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT org_membership_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT ux_org_membership UNIQUE (organization_id, app_user_id)
);
CREATE INDEX ix_org_membership_org  ON organization_membership (organization_id);
CREATE INDEX ix_org_membership_user ON organization_membership (app_user_id);
-- Enforce: one ACTIVE membership per user (initial-version rule).
CREATE UNIQUE INDEX ux_one_active_membership_per_user
    ON organization_membership (app_user_id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- user_role : which roles a membership holds (roles are organization-scoped via membership)
-- ---------------------------------------------------------------------------
CREATE TABLE user_role (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_membership_id UUID NOT NULL REFERENCES organization_membership (id) ON DELETE CASCADE,
    role_id                   UUID NOT NULL REFERENCES role (id),
    CONSTRAINT ux_user_role UNIQUE (organization_membership_id, role_id)
);
CREATE INDEX ix_user_role_membership ON user_role (organization_membership_id);
