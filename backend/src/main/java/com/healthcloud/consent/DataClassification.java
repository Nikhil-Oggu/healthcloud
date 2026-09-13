package com.healthcloud.consent;

/**
 * Field sensitivity classification labels (source-of-truth §23.1). Each field of a resource is mapped to a
 * classification (and, when patient-consent-controlled, a {@link ConsentDataCategory}); the backend uses
 * the mapping to build field-safe DTOs (§23.3). Labels are additive — never renumber or repurpose one.
 */
public enum DataClassification {
    INTERNAL,
    CONFIDENTIAL,
    SENSITIVE_HEALTH_DATA,
    AUDIT_ONLY,
    SECURITY_SECRET
}
