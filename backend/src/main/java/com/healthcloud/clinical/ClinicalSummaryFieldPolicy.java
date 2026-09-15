package com.healthcloud.clinical;

import com.healthcloud.consent.ConsentDataCategory;
import com.healthcloud.consent.DataClassification;
import java.util.List;

/**
 * The field-visibility map for a clinical summary (source-of-truth §23.1–23.3), mirroring
 * {@link com.healthcloud.patient.PatientFieldPolicy}. Each consent-controlled field names its JSON field, the
 * consent data category that governs it, and its sensitivity classification; the read purpose is fixed by the
 * action (§21.4) — see {@link ClinicalSummaryService}.
 *
 * <p>Only the free-text {@code narrative} is consent-controlled here: it is the unrestricted clinical detail
 * (CLINICAL_CONTEXT). The structured diagnosis <b>code</b> is deliberately NOT listed — it stays role-visible
 * so a caller can see the coded, claim-relevant diagnosis without the full medical narrative (§60).
 */
public enum ClinicalSummaryFieldPolicy {

    NARRATIVE("narrative", ConsentDataCategory.CLINICAL_CONTEXT, DataClassification.SENSITIVE_HEALTH_DATA);

    private final String jsonField;
    private final ConsentDataCategory dataCategory;
    private final DataClassification classification;

    ClinicalSummaryFieldPolicy(String jsonField, ConsentDataCategory dataCategory,
                               DataClassification classification) {
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

    /** All consent-controlled clinical-summary fields. */
    public static List<ClinicalSummaryFieldPolicy> consentControlled() {
        return List.of(values());
    }
}
