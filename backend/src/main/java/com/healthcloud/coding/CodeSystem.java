package com.healthcloud.coding;

/**
 * The standard code systems the platform recognizes (source-of-truth §Phase 4 — "ICD-10/HCPCS code model").
 * ICD-10-CM classifies <b>diagnoses</b> (used by clinical summaries); HCPCS and CPT classify <b>procedures</b>
 * and services (used by claim lines). These are public national vocabularies, the same for every tenant.
 */
public enum CodeSystem {

    /** ICD-10-CM — diagnosis codes (e.g. {@code E11.9} "Type 2 diabetes mellitus without complications"). */
    ICD10CM("ICD-10-CM", "Diagnosis"),

    /** HCPCS Level II — procedures, supplies and services (e.g. {@code J1815} insulin injection). */
    HCPCS("HCPCS", "Procedure"),

    /** CPT — procedures and services (e.g. {@code 99213} an office/outpatient visit). */
    CPT("CPT", "Procedure");

    private final String label;
    private final String category;

    CodeSystem(String label, String category) {
        this.label = label;
        this.category = category;
    }

    /** Human-readable name of the system, for display. */
    public String label() {
        return label;
    }

    /** What the system classifies: "Diagnosis" or "Procedure". */
    public String category() {
        return category;
    }
}
