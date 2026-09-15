package com.healthcloud.coverage;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Client-facing view of a patient's eligibility. Carries the coverage plan id plus its name (resolved at read)
 * so a client sees which plan the patient is on without a second call.
 */
public record PatientEligibilityDto(
        UUID id,
        UUID patientId,
        UUID coveragePlanId,
        String coveragePlanName,
        String memberId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        long version) {

    public static PatientEligibilityDto from(PatientEligibility e, String coveragePlanName) {
        return new PatientEligibilityDto(
                e.getId(),
                e.getPatientId(),
                e.getCoveragePlanId(),
                coveragePlanName,
                e.getMemberId(),
                e.getEffectiveFrom(),
                e.getEffectiveTo(),
                e.getVersion());
    }
}
