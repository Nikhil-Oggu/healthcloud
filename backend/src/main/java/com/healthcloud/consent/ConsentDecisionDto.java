package com.healthcloud.consent;

import java.util.UUID;

/** Self-describing consent decision for a (patient, purpose, data category) as seen by the calling actor. */
public record ConsentDecisionDto(
        UUID patientId,
        ConsentPurpose purpose,
        ConsentDataCategory dataCategory,
        ConsentEffect effect,
        ConsentScopeType decidingScope,
        UUID decidingDirectiveId,
        String reason) {

    public static ConsentDecisionDto from(UUID patientId, ConsentPurpose purpose,
                                          ConsentDataCategory dataCategory, ConsentDecision decision) {
        return new ConsentDecisionDto(patientId, purpose, dataCategory, decision.effect(),
                decision.decidingScope(), decision.decidingDirectiveId(), decision.reason());
    }
}
