package com.healthcloud.audit;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The per-org audit chain tip. Two operations are used together, inside the appending transaction, to serialize
 * chain writes for one org (§31 "row locks") — the same shape as {@code BenefitAccumulatorRepository}:
 *
 * <ol>
 *   <li>{@link #insertIfAbsent} — a native {@code INSERT ... ON CONFLICT DO NOTHING} that guarantees the head row
 *       exists (starting at GENESIS, sequence 0), avoiding a create race for an org's first-ever event;</li>
 *   <li>{@link #lockByOrganizationId} — a {@code PESSIMISTIC_WRITE} ({@code SELECT ... FOR UPDATE}) read that locks
 *       the tip for the rest of the transaction, so a concurrent audit write for the same org waits.</li>
 * </ol>
 */
public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, UUID> {

    /** Ensure the org's chain head exists (idempotent); returns rows inserted (1 the first time, 0 thereafter). */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO audit_chain_head (organization_id, last_hash, next_sequence)
            VALUES (:organizationId, :genesis, 0)
            ON CONFLICT (organization_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("organizationId") UUID organizationId, @Param("genesis") String genesis);

    /** Row-locked read (SELECT ... FOR UPDATE) of the org's chain tip. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM AuditChainHead h WHERE h.organizationId = :organizationId")
    Optional<AuditChainHead> lockByOrganizationId(@Param("organizationId") UUID organizationId);
}
