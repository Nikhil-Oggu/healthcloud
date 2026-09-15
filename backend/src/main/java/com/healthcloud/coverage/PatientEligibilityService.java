package com.healthcloud.coverage;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.patient.Patient;
import com.healthcloud.patient.PatientAccessGuard;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient eligibility reads and enrollment, always about a patient in the caller's tenant. Every operation
 * passes the layered authorization pipeline (§21): tenant → function/role → object/relationship
 * ({@link PatientAccessGuard}). The organization id comes from the loaded patient (backend-derived), never the
 * client, so a caller can only touch eligibility for patients they can already reach — an unreachable patient
 * is a secure 404.
 *
 * <p>Coverage periods for a patient are kept <b>non-overlapping</b> (enforced here → 409), so "coverage on a
 * date" resolves to at most one row — the deterministic input the Phase-5 adjudication engine needs. Eligibility
 * is claims/benefits data, not clinical context, so it is relationship-gated but not consent field-masked.
 */
@Service
@Transactional(readOnly = true)
public class PatientEligibilityService {

    /** Roles allowed to enroll a patient (enrollment is administrative). */
    private static final String[] ENROLL_ROLES = {"CARE_COORDINATOR", "ORG_ADMIN"};

    private final PatientEligibilityRepository eligibility;
    private final CoveragePlanRepository plans;
    private final PatientAccessGuard accessGuard;
    private final UserContextAccessor userContext;

    public PatientEligibilityService(PatientEligibilityRepository eligibility, CoveragePlanRepository plans,
                                     PatientAccessGuard accessGuard, UserContextAccessor userContext) {
        this.eligibility = eligibility;
        this.plans = plans;
        this.accessGuard = accessGuard;
        this.userContext = userContext;
    }

    /**
     * A patient's eligibility records (newest first), or — with {@code asOf} — only the period covering that
     * date. The patient object/relationship gate runs first (an unreachable patient is a secure 404).
     */
    public List<PatientEligibilityDto> list(UUID patientId, Optional<LocalDate> asOf) {
        Patient patient = accessGuard.requireAccessibleInTenant(patientId);
        UUID organizationId = patient.getOrganizationId();
        List<PatientEligibility> rows = asOf
                .map(date -> eligibility.findCovering(organizationId, patientId, date))
                .orElseGet(() -> eligibility.findByOrganizationIdAndPatientIdOrderByEffectiveFromDesc(
                        organizationId, patientId));
        Map<UUID, String> planNames = planNames(organizationId);
        return rows.stream().map(e -> PatientEligibilityDto.from(e, planNames.get(e.getCoveragePlanId()))).toList();
    }

    /** One eligibility record for the patient, or a secure 404. */
    public PatientEligibilityDto getById(UUID patientId, UUID eligibilityId) {
        Patient patient = accessGuard.requireAccessibleInTenant(patientId);
        PatientEligibility row = eligibility.findByIdAndOrganizationId(eligibilityId, patient.getOrganizationId())
                .filter(e -> e.getPatientId().equals(patientId))
                .orElseThrow(NotFoundException::new);
        return PatientEligibilityDto.from(row, planName(patient.getOrganizationId(), row.getCoveragePlanId()));
    }

    /**
     * Enroll a patient in a coverage plan for an effective-dated period. Requires an enroll role (403) AND —
     * for a PROVIDER — an active assignment (the guard, else secure 404). The plan must be in the tenant (else
     * 400); the period must be valid and must not overlap an existing one for the patient (else 409).
     */
    @Transactional
    public PatientEligibilityDto enroll(UUID patientId, EnrollEligibilityRequest request) {
        userContext.requireAnyRole(ENROLL_ROLES);
        Patient patient = accessGuard.requireAccessibleInTenant(patientId);
        UUID organizationId = patient.getOrganizationId();
        UUID enroller = userContext.requireUser().userId();

        CoveragePlan plan = plans.findByIdAndOrganizationId(request.coveragePlanId(), organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown coverage plan."));

        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The coverage end date cannot be before the start date.");
        }
        if (overlapsExisting(organizationId, patientId, request.effectiveFrom(), request.effectiveTo())) {
            throw new ConflictException("This coverage period overlaps an existing one for the patient.");
        }

        PatientEligibility saved = eligibility.save(new PatientEligibility(
                organizationId, patientId, plan.getId(), request.memberId(),
                request.effectiveFrom(), request.effectiveTo(), enroller));
        return PatientEligibilityDto.from(saved, plan.getName());
    }

    /** Whether [from, to] (to null = open-ended) overlaps any existing period for the patient. */
    private boolean overlapsExisting(UUID organizationId, UUID patientId, LocalDate from, LocalDate to) {
        return eligibility.findByOrganizationIdAndPatientIdOrderByEffectiveFromDesc(organizationId, patientId)
                .stream()
                .anyMatch(existing -> overlaps(existing.getEffectiveFrom(), existing.getEffectiveTo(), from, to));
    }

    /** Two date ranges overlap when each starts on or before the other ends (a null end is +infinity). */
    private static boolean overlaps(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        boolean aStartsBeforeBEnds = bTo == null || !aFrom.isAfter(bTo);
        boolean bStartsBeforeAEnds = aTo == null || !bFrom.isAfter(aTo);
        return aStartsBeforeBEnds && bStartsBeforeAEnds;
    }

    /** Map of plan id → name for the tenant (one query, avoids per-row lookups in list reads). */
    private Map<UUID, String> planNames(UUID organizationId) {
        return plans.findByOrganizationIdOrderByPlanCodeAsc(organizationId).stream()
                .collect(Collectors.toMap(CoveragePlan::getId, CoveragePlan::getName));
    }

    /** The name of one plan in the tenant (null if it no longer exists — reads never fail on a dangling name). */
    private String planName(UUID organizationId, UUID planId) {
        return plans.findByIdAndOrganizationId(planId, organizationId)
                .map(CoveragePlan::getName)
                .orElse(null);
    }
}
