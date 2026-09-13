package com.healthcloud.consent;

import java.util.UUID;

/**
 * The outcome of evaluating a patient's consent directives for one (actor, purpose, data category) at a
 * point in time (source-of-truth §22.5). {@code decidingScope}/{@code decidingDirectiveId} identify which
 * directive carried the decision (null when it is a deny-by-default). This is the CONSENT decision only —
 * not the full authorization decision, which also weighs role, relationship and business need (§21.3).
 */
public record ConsentDecision(
        ConsentEffect effect,
        ConsentScopeType decidingScope,
        UUID decidingDirectiveId,
        String reason) {

    public boolean isGranted() {
        return effect == ConsentEffect.GRANT;
    }

    static ConsentDecision grant(ConsentScopeType scope, UUID directiveId) {
        return new ConsentDecision(ConsentEffect.GRANT, scope, directiveId,
                "granted by a " + scope + "-scoped directive");
    }

    static ConsentDecision deny(ConsentScopeType scope, UUID directiveId) {
        return new ConsentDecision(ConsentEffect.DENY, scope, directiveId,
                "denied by a " + scope + "-scoped directive");
    }

    static ConsentDecision denyByDefault() {
        return new ConsentDecision(ConsentEffect.DENY, null, null,
                "no applicable consent grant — deny by default");
    }
}
