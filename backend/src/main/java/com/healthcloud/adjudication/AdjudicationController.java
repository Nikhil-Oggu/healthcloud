package com.healthcloud.adjudication;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The adjudication API, nested under the claim it concerns (source-of-truth §Phase 5). Thin controller (§31.5):
 * the role gate, the ACCEPTED-state check, the coverage lookup, the math and the one-transaction persistence live
 * in {@link AdjudicationService}. Adjudication is a dedicated engine command (not a bare status change): the
 * {@code POST} advances an ACCEPTED claim to ADJUDICATED and records the explainable outcome. Every route is
 * authenticated, tenant-scoped and patient-gated via the claim (an unreachable claim is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/claims/{claimId}")
public class AdjudicationController {

    private final AdjudicationService service;

    public AdjudicationController(AdjudicationService service) {
        this.service = service;
    }

    /** Run the adjudication engine on an ACCEPTED claim (CLAIMS_REVIEWER/ORG_ADMIN) → ADJUDICATED + the result. */
    @PostMapping("/adjudicate")
    public AdjudicationDto adjudicate(@PathVariable UUID claimId) {
        return service.adjudicate(claimId);
    }

    /** The claim's adjudication result — the plan that applied and how every amount was computed (§60). */
    @GetMapping("/adjudication")
    public AdjudicationDto getAdjudication(@PathVariable UUID claimId) {
        return service.getByClaim(claimId);
    }
}
