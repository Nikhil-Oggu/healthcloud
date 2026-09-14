package com.healthcloud.relationship;

import java.util.UUID;

/**
 * A candidate for a care-team assignment — a same-tenant user holding the required role (PROVIDER or
 * CARE_COORDINATOR) who is not already currently assigned to the patient. Minimum-necessary fields only
 * (§21): just enough to render a picker in the assignment UI.
 */
public record AssignmentCandidateDto(UUID userId, String fullName) {
}
