package com.healthcloud.consent;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The consent + purpose decision engine (source-of-truth §22.5) — a pure, unit-testable policy with no
 * Spring or database dependencies (the same shape as {@code RequestTransitions} for the state machine).
 * Given the actor, the purpose, the data category and the patient's directives, it decides GRANT or DENY:
 *
 * <ol>
 *   <li>find the <b>applicable</b> directives — status ACTIVE, in force today ({@code effective_from ≤ today
 *       ≤ effective_to}), scope applies to this actor, and purpose + category match;</li>
 *   <li>take the <b>most specific</b> tier present: PROVIDER &gt; CARE_TEAM &gt; ORGANIZATION;</li>
 *   <li>within that tier, if directives conflict, <b>DENY wins</b>;</li>
 *   <li>if no applicable directive exists, <b>deny by default</b>.</li>
 * </ol>
 *
 * <p>Re-checking the date window here (not just {@code status == ACTIVE}) makes this the honest source of
 * "in force right now", covering the SCHEDULED→ACTIVE / ACTIVE→EXPIRED time sweeps deferred to a scheduler.
 *
 * <p>Scope applicability: ORGANIZATION applies to every same-tenant actor; PROVIDER applies iff the actor
 * <i>is</i> the named provider; CARE_TEAM applies iff the actor is on the patient's care team — a fact the
 * caller supplies as {@code actorOnCareTeam} (computed from the provider/coordinator assignment tables),
 * keeping this policy free of any database dependency.
 */
public final class ConsentPolicy {

    /** Most specific first — the order tiers are considered (§22.5 step 2). */
    private static final List<ConsentScopeType> SPECIFICITY_ORDER =
            List.of(ConsentScopeType.PROVIDER, ConsentScopeType.CARE_TEAM, ConsentScopeType.ORGANIZATION);

    private ConsentPolicy() {
    }

    /**
     * Decide the effective consent for {@code actorUserId} accessing {@code dataCategory} for
     * {@code purpose}, given a patient's {@code directives} evaluated as of {@code today}.
     * {@code actorOnCareTeam} says whether the actor is a member of the patient's care team (used only to
     * decide whether CARE_TEAM-scoped directives apply).
     */
    public static ConsentDecision decide(UUID actorUserId, ConsentPurpose purpose,
                                         ConsentDataCategory dataCategory,
                                         List<ConsentDirective> directives, LocalDate today,
                                         boolean actorOnCareTeam) {
        List<ConsentDirective> applicable = directives.stream()
                .filter(d -> d.getStatus() == ConsentStatus.ACTIVE)
                .filter(d -> d.getPurpose() == purpose && d.getDataCategory() == dataCategory)
                .filter(d -> inForce(d, today))
                .filter(d -> scopeApplies(d, actorUserId, actorOnCareTeam))
                .toList();

        for (ConsentScopeType tier : SPECIFICITY_ORDER) {
            List<ConsentDirective> tierDirectives = applicable.stream()
                    .filter(d -> d.getScopeType() == tier)
                    .toList();
            if (tierDirectives.isEmpty()) {
                continue; // no directive at this specificity — fall through to the next tier
            }
            // DENY wins within the most specific tier present (§22.5 step 3).
            return tierDirectives.stream()
                    .filter(d -> d.getEffect() == ConsentEffect.DENY)
                    .findFirst()
                    .map(d -> ConsentDecision.deny(tier, d.getId()))
                    .orElseGet(() -> ConsentDecision.grant(tier, tierDirectives.get(0).getId()));
        }
        return ConsentDecision.denyByDefault();
    }

    /** Whether {@code today} falls within the directive's effective window (open-ended when no end date). */
    private static boolean inForce(ConsentDirective d, LocalDate today) {
        boolean started = !today.isBefore(d.getEffectiveFrom());
        boolean notEnded = d.getEffectiveTo() == null || !today.isAfter(d.getEffectiveTo());
        return started && notEnded;
    }

    /** Whether a directive's scope applies to this actor (see the class javadoc). */
    private static boolean scopeApplies(ConsentDirective d, UUID actorUserId, boolean actorOnCareTeam) {
        return switch (d.getScopeType()) {
            case ORGANIZATION -> true;
            case PROVIDER -> actorUserId.equals(d.getScopeRefId());
            case CARE_TEAM -> actorOnCareTeam;
        };
    }
}
