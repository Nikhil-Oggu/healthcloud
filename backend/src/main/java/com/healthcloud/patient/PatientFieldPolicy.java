package com.healthcloud.patient;

import com.healthcloud.consent.ConsentDataCategory;
import com.healthcloud.consent.DataClassification;
import java.util.List;

/**
 * The field-visibility map for the patient resource (source-of-truth §23.1–23.3). Each consent-controlled
 * field names the JSON field, its sensitivity classification, and the consent data category that governs it;
 * the read purpose is fixed by the action (§21.4) — see {@link PatientService}. Fields not listed here are
 * role-visible (returned to any authorized same-tenant caller) — e.g. id/name/MRN/status, which basic care
 * coordination needs.
 *
 * <p>This slice gates only {@code dateOfBirth}; the map is the extension point for more fields (and other
 * resources follow the same shape).
 */
public enum PatientFieldPolicy {

    DATE_OF_BIRTH("dateOfBirth", ConsentDataCategory.DEMOGRAPHICS_CONTACT, DataClassification.CONFIDENTIAL);

    private final String jsonField;
    private final ConsentDataCategory dataCategory;
    private final DataClassification classification;

    PatientFieldPolicy(String jsonField, ConsentDataCategory dataCategory, DataClassification classification) {
        this.jsonField = jsonField;
        this.dataCategory = dataCategory;
        this.classification = classification;
    }

    public String jsonField() {
        return jsonField;
    }

    public ConsentDataCategory dataCategory() {
        return dataCategory;
    }

    public DataClassification classification() {
        return classification;
    }

    /** All consent-controlled patient fields. */
    public static List<PatientFieldPolicy> consentControlled() {
        return List.of(values());
    }
}
