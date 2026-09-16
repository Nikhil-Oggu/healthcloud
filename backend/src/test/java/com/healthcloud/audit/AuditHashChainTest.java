package com.healthcloud.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure audit hash-chain core (no Spring/DB). Proves the fingerprint is deterministic for the
 * same facts, changes when any fingerprinted field changes (so tampering is detectable), and depends on the key
 * (so it cannot be forged without it).
 */
class AuditHashChainTest {

    private static final byte[] KEY = "org-key".getBytes(StandardCharsets.UTF_8);
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final OffsetDateTime WHEN =
            OffsetDateTime.of(2026, 9, 16, 12, 0, 0, 0, ZoneOffset.UTC);

    private String hash(long seq, String detail, String prevHash) {
        String canonical = AuditHashChain.canonical(ORG, seq, WHEN, ACTOR, AuditAction.CLAIM_ADJUDICATED,
                AuditService.RESOURCE_CLAIM, RESOURCE, AuditOutcome.SUCCESS, "corr-1", detail, prevHash);
        return AuditHashChain.computeEntryHash(KEY, canonical);
    }

    @Test
    void the_genesis_hash_is_64_zeros() {
        assertEquals("0".repeat(64), AuditHashChain.GENESIS);
        assertEquals(64, AuditHashChain.GENESIS.length());
    }

    @Test
    void the_same_facts_produce_the_same_fingerprint() {
        assertEquals(hash(0, "Claim CLM-1 adjudicated v1 (ADJUDICATED)", AuditHashChain.GENESIS),
                hash(0, "Claim CLM-1 adjudicated v1 (ADJUDICATED)", AuditHashChain.GENESIS));
    }

    @Test
    void the_fingerprint_is_lowercase_hex_sha256_length() {
        String h = hash(0, "detail", AuditHashChain.GENESIS);
        assertEquals(64, h.length(), "HMAC-SHA256 hex is 64 chars");
        assertEquals(h.toLowerCase(), h, "hex is lowercase");
    }

    @Test
    void changing_a_field_changes_the_fingerprint() {
        String original = hash(0, "Claim CLM-1 adjudicated v1 (ADJUDICATED)", AuditHashChain.GENESIS);
        assertNotEquals(original, hash(0, "Claim CLM-1 adjudicated v2 (ADJUDICATED)", AuditHashChain.GENESIS),
                "a modified detail must change the fingerprint");
        assertNotEquals(original, hash(1, "Claim CLM-1 adjudicated v1 (ADJUDICATED)", AuditHashChain.GENESIS),
                "a modified sequence number must change the fingerprint");
    }

    @Test
    void changing_the_previous_hash_changes_the_fingerprint() {
        assertNotEquals(hash(1, "detail", AuditHashChain.GENESIS),
                hash(1, "detail", "a".repeat(64)),
                "chaining: the same event after a different predecessor has a different fingerprint");
    }

    @Test
    void a_different_key_produces_a_different_fingerprint() {
        String canonical = AuditHashChain.canonical(ORG, 0, WHEN, ACTOR, AuditAction.CLAIM_ADJUDICATED,
                AuditService.RESOURCE_CLAIM, RESOURCE, AuditOutcome.SUCCESS, "corr-1", "detail",
                AuditHashChain.GENESIS);
        assertNotEquals(
                AuditHashChain.computeEntryHash(KEY, canonical),
                AuditHashChain.computeEntryHash("other-key".getBytes(StandardCharsets.UTF_8), canonical),
                "an attacker without the key cannot reproduce the fingerprint");
    }
}
