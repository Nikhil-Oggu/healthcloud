package com.healthcloud.consent;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Consent directives, tenant-safe by design: every finder is scoped by {@code organizationId}, so a caller
 * can only reach directives in their own organization. There is deliberately no bare {@code findById} for
 * business code.
 */
public interface ConsentDirectiveRepository extends JpaRepository<ConsentDirective, UUID> {

    /** Load a directive only if it belongs to the tenant; otherwise empty (→ secure 404). */
    Optional<ConsentDirective> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** A patient's full directive history within the tenant, oldest first (all versions, all statuses). */
    List<ConsentDirective> findByOrganizationIdAndPatientIdOrderByCreatedAtAsc(UUID organizationId, UUID patientId);

    /**
     * A patient's directives within the tenant in the given statuses, oldest first. Used with
     * {@code [ACTIVE, SCHEDULED]} to fetch the "current" set (the natural key is matched in Java so a
     * nullable scope reference is compared correctly).
     */
    List<ConsentDirective> findByOrganizationIdAndPatientIdAndStatusInOrderByCreatedAtAsc(
            UUID organizationId, UUID patientId, Collection<ConsentStatus> statuses);
}
