package com.healthcloud.request;

/** The kind of care-coordination request (source-of-truth §14.4). Stored as a string. */
public enum ServiceRequestType {
    CLAIM_SUPPORT,
    REFERRAL_REQUEST,
    DOCUMENT_REVIEW,
    APPOINTMENT_HELP,
    BENEFIT_CLARIFICATION
}
