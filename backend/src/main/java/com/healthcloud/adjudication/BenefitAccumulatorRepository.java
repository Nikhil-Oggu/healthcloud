package com.healthcloud.adjudication;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The benefit accumulator, tenant-owned. Like every tenant-owned repository (§32.10) the finders are scoped by
 * {@code organizationId}. The adjudication engine uses two operations together, inside its transaction, to
 * read-and-update an accumulator safely (§31 "row locks for financial accumulators"):
 *
 * <ol>
 *   <li>{@link #insertIfAbsent} — a native {@code INSERT ... ON CONFLICT DO NOTHING} that guarantees the row
 *       exists (defaults leave the amounts at 0), avoiding a create race between concurrent adjudications;</li>
 *   <li>{@link #lockByKey} — a {@code PESSIMISTIC_WRITE} ({@code SELECT ... FOR UPDATE}) read that locks the row
 *       for the rest of the transaction, so a second adjudication for the same patient/plan/year waits.</li>
 * </ol>
 */
public interface BenefitAccumulatorRepository extends JpaRepository<BenefitAccumulator, UUID> {

    /** Ensure the accumulator row exists (idempotent); returns rows inserted (1 the first time, 0 thereafter). */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO benefit_accumulator (organization_id, patient_id, coverage_plan_id, benefit_year)
            VALUES (:organizationId, :patientId, :coveragePlanId, :benefitYear)
            ON CONFLICT (organization_id, patient_id, coverage_plan_id, benefit_year) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("organizationId") UUID organizationId,
                       @Param("patientId") UUID patientId,
                       @Param("coveragePlanId") UUID coveragePlanId,
                       @Param("benefitYear") int benefitYear);

    /** Row-locked read (SELECT ... FOR UPDATE) of the accumulator for a patient/plan/year within the tenant. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a FROM BenefitAccumulator a
            WHERE a.organizationId = :organizationId
              AND a.patientId = :patientId
              AND a.coveragePlanId = :coveragePlanId
              AND a.benefitYear = :benefitYear
            """)
    Optional<BenefitAccumulator> lockByKey(@Param("organizationId") UUID organizationId,
                                           @Param("patientId") UUID patientId,
                                           @Param("coveragePlanId") UUID coveragePlanId,
                                           @Param("benefitYear") int benefitYear);

    /** Unlocked read of the accumulator (for reads/tests that do not update it). */
    Optional<BenefitAccumulator> findByOrganizationIdAndPatientIdAndCoveragePlanIdAndBenefitYear(
            UUID organizationId, UUID patientId, UUID coveragePlanId, int benefitYear);
}
