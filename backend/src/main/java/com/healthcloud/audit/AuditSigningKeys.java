package com.healthcloud.audit;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Derives the per-organization HMAC key for the audit chain (source-of-truth §Phase 7). The key is
 * {@code HMAC-SHA256(masterSecret, organizationId)} — so every org has a distinct key, and the {@code masterSecret}
 * lives in <b>configuration</b> ({@code healthcloud.audit.hmac-secret}), never in the database it protects. That
 * separation is the whole point: an attacker who can edit {@code audit_event} rows still cannot forge a valid
 * fingerprint without the secret.
 *
 * <p><b>Honest limitation:</b> the master secret is a config value with a dev default (env-overridable, like
 * {@code healthcloud.documents.dir}). A real deployment holds it in a KMS/HSM and never in plaintext config — that
 * is Phase 10 (cloud) work; here it is a local-first stand-in, on synthetic data only.
 */
@Component
public class AuditSigningKeys {

    private final byte[] masterSecret;

    public AuditSigningKeys(@Value("${healthcloud.audit.hmac-secret}") String masterSecret) {
        this.masterSecret = masterSecret.getBytes(StandardCharsets.UTF_8);
    }

    /** The signing key for one organization's audit chain. */
    public byte[] orgKey(UUID organizationId) {
        return AuditHashChain.hmacSha256(masterSecret, organizationId.toString().getBytes(StandardCharsets.UTF_8));
    }
}
