package com.healthcloud.request;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Request comments, read scoped to the tenant + request (oldest first). */
public interface RequestCommentRepository extends JpaRepository<RequestComment, UUID> {

    List<RequestComment> findByOrganizationIdAndServiceRequestIdOrderByCreatedAtAsc(
            UUID organizationId, UUID serviceRequestId);
}
