package com.healthcloud.request;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only status history, read scoped to the tenant + request. */
public interface RequestStatusHistoryRepository extends JpaRepository<RequestStatusHistory, UUID> {

    List<RequestStatusHistory> findByOrganizationIdAndServiceRequestIdOrderByCreatedAtAsc(
            UUID organizationId, UUID serviceRequestId);
}
