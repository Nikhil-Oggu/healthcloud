package com.healthcloud.adjudication;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Adjudication lines, tenant-owned and always loaded for a specific adjudication. Scoped by
 * {@code organizationId} (§32.10); ordered by line number so a breakdown reads back deterministically.
 */
public interface AdjudicationLineRepository extends JpaRepository<AdjudicationLine, UUID> {

    /** An adjudication's lines within the tenant, in line-number order. */
    List<AdjudicationLine> findByOrganizationIdAndAdjudicationIdOrderByLineNumberAsc(
            UUID organizationId, UUID adjudicationId);
}
