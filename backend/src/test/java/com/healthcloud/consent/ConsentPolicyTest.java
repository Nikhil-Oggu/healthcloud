package com.healthcloud.consent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure consent + purpose decision engine (source-of-truth §22.5). No Spring, no DB —
 * just the algorithm: applicable directives → most-specific tier → DENY-wins → deny-by-default, with the
 * effective-date window re-checked at decision time.
 */
class ConsentPolicyTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();
    private static final UUID PROVIDER_A = UUID.randomUUID();
    private static final UUID PROVIDER_B = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    private static final ConsentPurpose PURPOSE = ConsentPurpose.CARE_COORDINATION;
    private static final ConsentDataCategory CATEGORY = ConsentDataCategory.CLINICAL_CONTEXT;

    /** Build a directive with the common defaults (this purpose/category, ACTIVE, started yesterday, open-ended). */
    private ConsentDirective directive(ConsentEffect effect, ConsentScopeType scope, UUID scopeRef) {
        return directive(effect, scope, scopeRef, TODAY.minusDays(1), null, PURPOSE, CATEGORY, ConsentStatus.ACTIVE);
    }

    private ConsentDirective directive(ConsentEffect effect, ConsentScopeType scope, UUID scopeRef,
                                       LocalDate from, LocalDate to, ConsentPurpose purpose,
                                       ConsentDataCategory category, ConsentStatus status) {
        return new ConsentDirective(ORG, PATIENT, UUID.randomUUID(), effect, purpose, category, scope,
                scopeRef, from, to, status, 1, CREATOR);
    }

    @Test
    void no_directives_is_deny_by_default() {
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, List.of(), TODAY, false);
        assertFalse(d.isGranted());
        assertEquals(ConsentEffect.DENY, d.effect());
        assertEquals(null, d.decidingScope(), "a deny-by-default names no directive");
    }

    @Test
    void an_organization_grant_grants() {
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY,
                List.of(directive(ConsentEffect.GRANT, ConsentScopeType.ORGANIZATION, null)), TODAY, false);
        assertTrue(d.isGranted());
        assertEquals(ConsentScopeType.ORGANIZATION, d.decidingScope());
    }

    @Test
    void an_organization_deny_denies() {
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY,
                List.of(directive(ConsentEffect.DENY, ConsentScopeType.ORGANIZATION, null)), TODAY, false);
        assertFalse(d.isGranted());
        assertEquals(ConsentScopeType.ORGANIZATION, d.decidingScope());
    }

    @Test
    void a_provider_grant_overrides_an_organization_deny() {
        List<ConsentDirective> directives = List.of(
                directive(ConsentEffect.DENY, ConsentScopeType.ORGANIZATION, null),
                directive(ConsentEffect.GRANT, ConsentScopeType.PROVIDER, PROVIDER_A));
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, directives, TODAY, false);
        assertTrue(d.isGranted(), "the more specific provider tier wins");
        assertEquals(ConsentScopeType.PROVIDER, d.decidingScope());
    }

    @Test
    void a_provider_deny_overrides_an_organization_grant() {
        List<ConsentDirective> directives = List.of(
                directive(ConsentEffect.GRANT, ConsentScopeType.ORGANIZATION, null),
                directive(ConsentEffect.DENY, ConsentScopeType.PROVIDER, PROVIDER_A));
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, directives, TODAY, false);
        assertFalse(d.isGranted(), "the more specific provider tier wins");
        assertEquals(ConsentScopeType.PROVIDER, d.decidingScope());
    }

    @Test
    void same_role_different_result_by_provider_scope() {
        // One directive set; two different providers (same role) get OPPOSITE results (§60 acceptance).
        List<ConsentDirective> directives = List.of(
                directive(ConsentEffect.GRANT, ConsentScopeType.ORGANIZATION, null),
                directive(ConsentEffect.DENY, ConsentScopeType.PROVIDER, PROVIDER_A));

        assertFalse(ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, directives, TODAY, false).isGranted(),
                "provider A is specifically denied");
        assertTrue(ConsentPolicy.decide(PROVIDER_B, PURPOSE, CATEGORY, directives, TODAY, false).isGranted(),
                "provider B is not named, so the org-wide grant applies");
    }

    @Test
    void an_expired_directive_is_ignored() {
        ConsentDirective expired = directive(ConsentEffect.GRANT, ConsentScopeType.ORGANIZATION, null,
                TODAY.minusDays(10), TODAY.minusDays(1), PURPOSE, CATEGORY, ConsentStatus.ACTIVE);
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, List.of(expired), TODAY, false);
        assertFalse(d.isGranted(), "a directive whose window has passed is not in force → deny by default");
        assertEquals(null, d.decidingScope());
    }

    @Test
    void a_scheduled_future_directive_is_ignored() {
        ConsentDirective future = directive(ConsentEffect.GRANT, ConsentScopeType.ORGANIZATION, null,
                TODAY.plusDays(1), null, PURPOSE, CATEGORY, ConsentStatus.ACTIVE);
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, List.of(future), TODAY, false);
        assertFalse(d.isGranted(), "a not-yet-effective directive does not apply today");
    }

    @Test
    void a_directive_for_another_purpose_does_not_apply() {
        ConsentDirective other = directive(ConsentEffect.GRANT, ConsentScopeType.ORGANIZATION, null,
                TODAY.minusDays(1), null, ConsentPurpose.CLAIM_PROCESSING, CATEGORY, ConsentStatus.ACTIVE);
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, List.of(other), TODAY, false);
        assertFalse(d.isGranted(), "purpose must match to be applicable");
    }

    @Test
    void deny_wins_within_the_same_tier() {
        // Robustness: even if the DB somehow held two org-wide currents, DENY wins the tie (§22.5 step 3).
        List<ConsentDirective> directives = List.of(
                directive(ConsentEffect.GRANT, ConsentScopeType.ORGANIZATION, null),
                directive(ConsentEffect.DENY, ConsentScopeType.ORGANIZATION, null));
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, directives, TODAY, false);
        assertFalse(d.isGranted());
        assertEquals(ConsentScopeType.ORGANIZATION, d.decidingScope());
    }

    @Test
    void a_care_team_grant_applies_only_to_a_care_team_member() {
        List<ConsentDirective> directives =
                List.of(directive(ConsentEffect.GRANT, ConsentScopeType.CARE_TEAM, null));

        ConsentDecision member = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, directives, TODAY, true);
        assertTrue(member.isGranted(), "a care-team member is granted by a CARE_TEAM directive");
        assertEquals(ConsentScopeType.CARE_TEAM, member.decidingScope());

        ConsentDecision nonMember = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, directives, TODAY, false);
        assertFalse(nonMember.isGranted(), "a non-member sees no applicable directive → deny by default");
        assertEquals(null, nonMember.decidingScope());
    }

    @Test
    void a_care_team_directive_overrides_the_organization_tier() {
        // CARE_TEAM is more specific than ORGANIZATION: for a team member, a CARE_TEAM DENY beats an ORG GRANT.
        List<ConsentDirective> directives = List.of(
                directive(ConsentEffect.GRANT, ConsentScopeType.ORGANIZATION, null),
                directive(ConsentEffect.DENY, ConsentScopeType.CARE_TEAM, null));
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, directives, TODAY, true);
        assertFalse(d.isGranted(), "the more specific care-team tier wins over the org tier");
        assertEquals(ConsentScopeType.CARE_TEAM, d.decidingScope());
    }

    @Test
    void a_provider_directive_overrides_the_care_team_tier() {
        // PROVIDER is more specific than CARE_TEAM: a PROVIDER GRANT beats a CARE_TEAM DENY for that provider.
        List<ConsentDirective> directives = List.of(
                directive(ConsentEffect.DENY, ConsentScopeType.CARE_TEAM, null),
                directive(ConsentEffect.GRANT, ConsentScopeType.PROVIDER, PROVIDER_A));
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, directives, TODAY, true);
        assertTrue(d.isGranted(), "the most specific provider tier wins over care-team");
        assertEquals(ConsentScopeType.PROVIDER, d.decidingScope());
    }

    @Test
    void deny_wins_within_the_care_team_tier() {
        List<ConsentDirective> directives = List.of(
                directive(ConsentEffect.GRANT, ConsentScopeType.CARE_TEAM, null),
                directive(ConsentEffect.DENY, ConsentScopeType.CARE_TEAM, null));
        ConsentDecision d = ConsentPolicy.decide(PROVIDER_A, PURPOSE, CATEGORY, directives, TODAY, true);
        assertFalse(d.isGranted());
        assertEquals(ConsentScopeType.CARE_TEAM, d.decidingScope());
    }
}
