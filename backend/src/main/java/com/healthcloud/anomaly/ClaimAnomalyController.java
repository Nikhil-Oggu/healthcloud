package com.healthcloud.anomaly;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The claim anomaly API, nested under the claim it concerns (source-of-truth §Phase 6). Thin controller (§31.5):
 * the role gate, the coverage of surrounding facts, the pure detection and the one-transaction replace live in
 * {@link ClaimAnomalyService}. A scan is a reviewer command (CLAIMS_REVIEWER/ORG_ADMIN); the read is open to any
 * same-tenant caller who can reach the claim. Every route is authenticated, tenant-scoped and patient-gated via
 * the claim (an unreachable claim is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/claims/{claimId}")
public class ClaimAnomalyController {

    private final ClaimAnomalyService service;

    public ClaimAnomalyController(ClaimAnomalyService service) {
        this.service = service;
    }

    /** Run the anomaly detector on a claim (CLAIMS_REVIEWER/ORG_ADMIN) → the current signals (idempotent). */
    @PostMapping("/anomaly-scan")
    public List<ClaimAnomalySignalDto> scan(@PathVariable UUID claimId) {
        return service.scan(claimId);
    }

    /** The claim's current anomaly signals (oldest first). */
    @GetMapping("/anomalies")
    public List<ClaimAnomalySignalDto> list(@PathVariable UUID claimId) {
        return service.list(claimId);
    }
}
