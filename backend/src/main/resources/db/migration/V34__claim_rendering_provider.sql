-- V34: Rendering provider on a claim (source-of-truth §Phase 6, provider network). SYNTHETIC data only.
-- Records the PROVIDER who rendered the service on a claim — the claim-side data the provider-network engine
-- rule needs. Nullable: existing claims and claims created without one simply have no rendering provider
-- (the engine imposes no out-of-network penalty when it cannot determine the provider). Header-level (one
-- rendering provider per claim) for the MVP; per-line rendering providers are a later refinement.
--
-- Plain FK to app_user(id): app_user is NOT tenant-keyed, so (as with the assignment / network tables) the
-- service validates the provider is an active same-tenant PROVIDER; the FK only guarantees the user exists.

ALTER TABLE claim
    ADD COLUMN rendering_provider_id UUID REFERENCES app_user (id);
