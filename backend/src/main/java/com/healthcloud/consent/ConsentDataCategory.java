package com.healthcloud.consent;

/**
 * The patient-consent-controlled data categories (source-of-truth §22.3). Note that SECURITY_METADATA,
 * AUDIT_METADATA and PLATFORM_OPERATIONAL_DATA are deliberately NOT here: they are required for platform
 * integrity/governance and are never controlled by patient consent.
 */
public enum ConsentDataCategory {
    DEMOGRAPHICS_CONTACT,
    CARE_COORDINATION,
    CLINICAL_CONTEXT,
    CLAIMS_BENEFITS,
    DOCUMENTS
}
