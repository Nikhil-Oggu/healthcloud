-- V39: Break-glass revocation (Phase 7 slice 6, access review). SYNTHETIC data only.
-- Adds early revocation to a break-glass grant so an admin, reviewing standing emergency access, can END a grant
-- before it expires. A grant is now LIVE only when expires_at > now AND revoked_at IS NULL — the access guard and
-- every "active grant" query filter on both, so a revocation cuts off access immediately. Revocation is the one
-- allowed mutation of an otherwise append-only grant; it also writes a BREAK_GLASS_REVOKED audit event.

ALTER TABLE break_glass_grant
    ADD COLUMN revoked_at TIMESTAMPTZ,                       -- when an admin ended the grant early (NULL = live)
    ADD COLUMN revoked_by UUID REFERENCES app_user (id);     -- the admin who revoked it
