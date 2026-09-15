package com.healthcloud.claim;

import com.healthcloud.coding.CodeSystem;
import com.healthcloud.coding.MedicalCode;
import com.healthcloud.coding.MedicalCodeRepository;
import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientAccessGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim reads and creation, always scoped to the caller's tenant (org derived from the loaded patient, never
 * the client). Every operation passes the layered authorization pipeline (§21): tenant → function/role →
 * object/relationship ({@link PatientAccessGuard}). A claim carries only coded, claim-relevant data (no
 * clinical narrative), so there is no consent field masking here — a claims reviewer can work claims without
 * unrestricted medical context (§60), which lives (consent-masked) in the clinical summary instead.
 *
 * <p>Creation follows the one-transaction aggregate pattern (§31.6): the header and all its lines are written
 * atomically, with the header total computed from the lines. Each line's procedure must be a real active
 * PROCEDURE code (CPT/HCPCS) in the global catalog — validated here so an unknown or non-procedure code is a
 * clean 400. Controlled status transitions + status history arrive in the submission-workflow slice.
 */
@Service
@Transactional(readOnly = true)
public class ClaimService {

    /** Roles allowed to create a claim. A PROVIDER must also be actively assigned (the guard). */
    private static final String[] CREATE_ROLES = {"PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    /** The catalog systems that classify procedures (a claim line bills a procedure, not a diagnosis). */
    private static final List<CodeSystem> PROCEDURE_SYSTEMS = List.of(CodeSystem.CPT, CodeSystem.HCPCS);

    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final ClaimRepository claims;
    private final ClaimLineRepository claimLines;
    private final MedicalCodeRepository medicalCodes;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public ClaimService(ClaimRepository claims, ClaimLineRepository claimLines,
                        MedicalCodeRepository medicalCodes, PatientAccessGuard accessGuard,
                        UserContextAccessor userContext) {
        this.claims = claims;
        this.claimLines = claimLines;
        this.medicalCodes = medicalCodes;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * Create a DRAFT claim (header + lines) for a patient the caller can reach. Requires a create role (403
     * otherwise) AND — for a PROVIDER — an active assignment (the guard, else secure 404). Each procedure code
     * is validated against the catalog (unknown/non-procedure → 400); the header total is the sum of the line
     * charges. The whole aggregate is written in one transaction.
     */
    @Transactional
    public ClaimDto create(CreateClaimRequest request) {
        userContext.requireAnyRole(CREATE_ROLES);
        UserContext caller = userContext.requireUser();
        Patient patient = accessGuard.requireAccessibleInTenant(request.patientId());
        UUID organizationId = patient.getOrganizationId();

        // Resolve + validate every procedure code first, so a bad line fails the whole create cleanly (400).
        List<MedicalCode> resolved = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (CreateClaimLineRequest line : request.lines()) {
            resolved.add(resolveProcedureCode(line.procedureCode()));
            total = total.add(line.chargeAmount());
        }

        Claim saved = claims.save(new Claim(
                organizationId,
                request.patientId(),
                allocateClaimNumber(organizationId),
                request.serviceDate(),
                total,
                caller.userId()));

        List<ClaimLine> lines = new ArrayList<>();
        int lineNumber = 1;
        for (int i = 0; i < request.lines().size(); i++) {
            CreateClaimLineRequest line = request.lines().get(i);
            MedicalCode code = resolved.get(i);
            int units = line.units() != null ? line.units() : 1;
            lines.add(claimLines.save(new ClaimLine(
                    organizationId, saved.getId(), lineNumber++,
                    code.getCodeSystem(), code.getCode(), units, line.chargeAmount())));
        }

        return ClaimDto.from(saved, lines);
    }

    /** One claim (header + lines) in the caller's tenant, gated by its patient (§21 layer 6), or a secure 404. */
    public ClaimDto getById(UUID claimId) {
        Claim claim = requireAccessibleClaim(claimId);
        return ClaimDto.from(claim, claimLines
                .findByOrganizationIdAndClaimIdOrderByLineNumberAsc(claim.getOrganizationId(), claimId));
    }

    /**
     * Claims in the caller's tenant (header-only), optionally filtered to one patient and/or a status. A claim
     * is gated by its patient (§21 layer 6): a provider sees only claims for patients they are actively assigned
     * to; broad roles (coordinator/admin/reviewer) see the tenant's claims — the reviewer's work queue.
     */
    public List<ClaimSummaryDto> list(Optional<UUID> patientId, Optional<ClaimStatus> status) {
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();

        List<Claim> found;
        if (patientId.isPresent()) {
            // Reuse the patient gate: an inaccessible patient (another tenant, or unassigned provider) → 404.
            accessGuard.requireAccessibleInTenant(patientId.get());
            found = claims.findByOrganizationIdAndPatientIdOrderByCreatedAtDesc(organizationId, patientId.get());
        } else {
            Optional<Set<UUID>> accessibleIds = accessGuard.accessiblePatientIdsIfGated(caller, organizationId);
            if (accessibleIds.isPresent()) {
                Set<UUID> visible = accessibleIds.get();
                found = visible.isEmpty()
                        ? List.of()
                        : claims.findByOrganizationIdAndPatientIdInOrderByCreatedAtDesc(organizationId, visible);
            } else {
                found = claims.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
            }
        }
        return found.stream()
                .filter(c -> status.isEmpty() || c.getStatus() == status.get())
                .map(ClaimSummaryDto::from)
                .toList();
    }

    /**
     * Load a claim in the caller's tenant and confirm the caller may reach its patient (§21 layer 6), else a
     * secure 404. The single choke point for every read that names one claim.
     */
    private Claim requireAccessibleClaim(UUID claimId) {
        UUID organizationId = userContext.requireOrganizationId();
        Claim claim = claims.findByIdAndOrganizationId(claimId, organizationId)
                .orElseThrow(NotFoundException::new);
        accessGuard.requireAccessibleInTenant(claim.getPatientId());
        return claim;
    }

    /** Resolve a caller-supplied code to an active CPT/HCPCS catalog entry, or a clean 400. */
    private MedicalCode resolveProcedureCode(String code) {
        for (CodeSystem system : PROCEDURE_SYSTEMS) {
            Optional<MedicalCode> match =
                    medicalCodes.findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(system, code);
            if (match.isPresent()) {
                return match.get();
            }
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown procedure code (CPT/HCPCS): " + code);
    }

    /** Allocate a claim number unique within the tenant (the unique index is the backstop). */
    private String allocateClaimNumber(UUID organizationId) {
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String candidate = "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            if (!claims.existsByOrganizationIdAndClaimNumber(organizationId, candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("Could not allocate a unique claim number; please retry.");
    }
}
