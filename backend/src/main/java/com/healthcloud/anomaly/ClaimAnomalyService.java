package com.healthcloud.anomaly;

import com.healthcloud.claim.Claim;
import com.healthcloud.claim.ClaimLine;
import com.healthcloud.claim.ClaimLineRepository;
import com.healthcloud.claim.ClaimRepository;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.PatientAccessGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim anomaly detection (source-of-truth §Phase 6, advanced claims). A reviewer scans a claim; this service
 * gathers the surrounding facts, applies the pure {@link ClaimAnomalyDetector}, and records the resulting
 * advisory signals. Detection is <b>additive</b> — it never changes the claim's status or the adjudication math.
 *
 * <p>Every operation passes the layered authorization pipeline (§21): tenant (org from context) → function/role
 * (scan is CLAIMS_REVIEWER/ORG_ADMIN, the reviewer's action) → object/relationship ({@link PatientAccessGuard},
 * via the claim's patient — an unreachable claim is a secure 404). A scan replaces the claim's signals in one
 * transaction (delete + insert), so it is idempotent: re-scanning an unchanged claim yields the same set.
 */
@Service
@Transactional(readOnly = true)
public class ClaimAnomalyService {

    /** Roles allowed to run a scan — the reviewer's action (mirrors accept/reject and adjudicate). */
    private static final String[] SCAN_ROLES = {"CLAIMS_REVIEWER", "ORG_ADMIN"};

    private final ClaimRepository claims;
    private final ClaimLineRepository claimLines;
    private final ClaimAnomalySignalRepository signals;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;
    private final BigDecimal highTotalChargeThreshold;

    public ClaimAnomalyService(ClaimRepository claims, ClaimLineRepository claimLines,
                               ClaimAnomalySignalRepository signals, PatientAccessGuard accessGuard,
                               UserContextAccessor userContext,
                               @Value("${healthcloud.anomaly.high-total-charge-threshold}") BigDecimal threshold) {
        this.claims = claims;
        this.claimLines = claimLines;
        this.signals = signals;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
        this.highTotalChargeThreshold = threshold;
    }

    /**
     * Run the detector on a claim the caller can reach and replace its signals with the result (one transaction).
     * Requires a scan role (403 otherwise) AND — for a PROVIDER, though providers cannot scan — reachability via
     * the guard. Returns the current signals, oldest first.
     */
    @Transactional
    public List<ClaimAnomalySignalDto> scan(UUID claimId) {
        userContext.requireAnyRole(SCAN_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        Claim claim = requireAccessibleClaim(claimId);

        ClaimAnomalyDetector.Subject subject = new ClaimAnomalyDetector.Subject(
                claim.getClaimNumber(), claim.getServiceDate(), claim.getTotalChargeAmount(),
                procedureCodes(organizationId, claim.getId()));

        // Sibling claims for the same patient — the duplicate-detection context (exclude this claim itself).
        List<ClaimAnomalyDetector.OtherClaim> otherClaims = claims
                .findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(organizationId, claim.getPatientId())
                .stream()
                .filter(other -> !other.getId().equals(claim.getId()))
                .map(other -> new ClaimAnomalyDetector.OtherClaim(
                        other.getClaimNumber(), other.getServiceDate(),
                        procedureCodes(organizationId, other.getId())))
                .toList();

        List<ClaimAnomalyDetector.DetectedSignal> detected = ClaimAnomalyDetector.detect(
                subject, new ClaimAnomalyDetector.Context(otherClaims, highTotalChargeThreshold));

        // Replace: a rescan supersedes the claim's prior signals so detection is idempotent.
        signals.deleteByOrganizationIdAndClaimId(organizationId, claim.getId());
        signals.saveAll(detected.stream()
                .map(s -> new ClaimAnomalySignal(organizationId, claim.getId(),
                        s.type(), s.severity(), s.detail(), caller.userId()))
                .toList());

        return list(claim.getId());
    }

    /** The claim's current anomaly signals (oldest first), tenant + relationship gated (secure 404). */
    public List<ClaimAnomalySignalDto> list(UUID claimId) {
        Claim claim = requireAccessibleClaim(claimId);
        return signals
                .findByOrganizationIdAndClaimIdOrderByDetectedAtAsc(claim.getOrganizationId(), claim.getId())
                .stream()
                .map(ClaimAnomalySignalDto::from)
                .toList();
    }

    /** The raw procedure code strings on a claim's lines (used for duplicate matching + readable detail). */
    private Set<String> procedureCodes(UUID organizationId, UUID claimId) {
        return claimLines.findByOrganizationIdAndClaimIdOrderByLineNumberAsc(organizationId, claimId)
                .stream()
                .map(ClaimLine::getProcedureCode)
                .collect(Collectors.toSet());
    }

    /**
     * Load a claim in the caller's tenant and confirm the caller may reach its patient (§21 layer 6), else a
     * secure 404. The single choke point for both scan and read.
     */
    private Claim requireAccessibleClaim(UUID claimId) {
        UUID organizationId = userContext.requireOrganizationId();
        Claim claim = claims.findByIdAndOrganizationId(claimId, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(claim.getPatientId());
        return claim;
    }
}
