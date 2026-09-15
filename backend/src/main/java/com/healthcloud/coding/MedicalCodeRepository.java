package com.healthcloud.coding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The medical code catalog. Reference data, so — unlike the tenant-owned repositories — these finders are
 * deliberately NOT scoped by {@code organizationId}: codes are global. Search returns only {@code active}
 * codes, ordered for stable listing, and is bounded by a {@link Pageable} the service supplies.
 */
public interface MedicalCodeRepository extends JpaRepository<MedicalCode, UUID> {

    /** Exact lookup of one active code within a system (case-insensitive on the code). */
    Optional<MedicalCode> findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(CodeSystem codeSystem, String code);

    /**
     * Search active codes by an optional system and an optional free-text term. A blank/null system searches
     * every system; a blank/null term returns the leading codes. The term matches a code prefix or any
     * substring of the description (both case-insensitive). Bounded by {@code pageable}.
     */
    @Query("""
            SELECT c FROM MedicalCode c
            WHERE c.active = true
              AND (:system IS NULL OR c.codeSystem = :system)
              AND (:term IS NULL
                   OR LOWER(c.code) LIKE LOWER(CONCAT(:term, '%'))
                   OR LOWER(c.description) LIKE LOWER(CONCAT('%', :term, '%')))
            ORDER BY c.codeSystem, c.code
            """)
    List<MedicalCode> search(@Param("system") CodeSystem system, @Param("term") String term, Pageable pageable);
}
