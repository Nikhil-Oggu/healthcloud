package com.healthcloud.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * The tip of an organization's audit chain (source-of-truth §Phase 7): the most recent event's fingerprint
 * ({@code lastHash}) and the sequence number to assign next ({@code nextSequence}). One row per org. It is taken
 * under a {@code PESSIMISTIC_WRITE} lock while an event is appended (see {@link AuditChainHeadRepository}), so
 * concurrent audit writes for one org serialize and the chain cannot fork — the same row-lock pattern the
 * adjudication engine uses for {@code benefit_accumulator} (§31). Verification also cross-checks the head to
 * detect truncation (deletion of the most recent rows).
 */
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    @Id
    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid", updatable = false)
    private UUID organizationId;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    @Column(name = "next_sequence", nullable = false)
    private long nextSequence;

    protected AuditChainHead() {
        // for JPA
    }

    /** Advance the tip after appending an event: its fingerprint becomes the new head and the counter increments. */
    public void advance(String entryHash) {
        this.lastHash = entryHash;
        this.nextSequence += 1;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getLastHash() {
        return lastHash;
    }

    public long getNextSequence() {
        return nextSequence;
    }
}
