package com.healthcloud.request;

/**
 * The lifecycle states of a service request (source-of-truth §14.6). This slice only creates DRAFT
 * requests; the controlled transitions between these states are implemented in the next slice.
 */
public enum ServiceRequestStatus {
    DRAFT,
    SUBMITTED,
    TRIAGED,
    ASSIGNED,
    UNDER_REVIEW,
    NEEDS_INFORMATION,
    APPROVED,
    REJECTED,
    CANCELLED,
    CLOSED
}
