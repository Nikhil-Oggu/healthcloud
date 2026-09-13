package com.healthcloud.consent;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request to record a consent directive for a patient. If a current (ACTIVE/SCHEDULED) directive already
 * exists for the same natural key (purpose + category + scope), it is superseded and this becomes the next
 * version — never an in-place edit (§22.4). {@code scopeRefId} is required only for PROVIDER scope (the
 * provider's user id) and must be absent otherwise. {@code effectiveFrom} defaults to today when omitted;
 * a future date makes the directive SCHEDULED. The tenant and author are stamped on the backend.
 */
public record RecordConsentRequest(
        @NotNull ConsentEffect effect,
        @NotNull ConsentPurpose purpose,
        @NotNull ConsentDataCategory dataCategory,
        @NotNull ConsentScopeType scopeType,
        UUID scopeRefId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
