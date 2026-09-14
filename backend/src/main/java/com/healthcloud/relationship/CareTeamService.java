package com.healthcloud.relationship;

import com.healthcloud.patient.PatientAccessGuard;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "is this user on the patient's care team?" (source-of-truth §14.3, §22) — the input the consent
 * engine needs to evaluate a CARE_TEAM-scoped directive. The care team is the union of the providers and
 * coordinators <b>actively assigned</b> to the patient (ACTIVE and in force today); PENDING or past-window
 * assignments do not count.
 *
 * <p>The provider half reuses {@link PatientAccessGuard#isActivelyAssigned} (the same in-force check the
 * access gate uses); the coordinator half reads {@code care_coordinator_assignment} directly. Depends only
 * on the guard and a repository, so there is no bean cycle with the consent services that call it.
 */
@Service
@Transactional(readOnly = true)
public class CareTeamService {

    private final PatientAccessGuard providerAccess;
    private final CareCoordinatorAssignmentRepository coordinatorAssignments;

    public CareTeamService(PatientAccessGuard providerAccess,
                           CareCoordinatorAssignmentRepository coordinatorAssignments) {
        this.providerAccess = providerAccess;
        this.coordinatorAssignments = coordinatorAssignments;
    }

    /**
     * Whether {@code userId} is on {@code patientId}'s care team within the tenant: an in-force ACTIVE
     * provider assignment OR an in-force ACTIVE coordinator assignment to that patient.
     */
    public boolean isOnCareTeam(UUID organizationId, UUID userId, UUID patientId) {
        return providerAccess.isActivelyAssigned(organizationId, userId, patientId)
                || isActiveCoordinator(organizationId, userId, patientId);
    }

    /** Whether the user has an in-force ACTIVE coordinator assignment to the patient. */
    private boolean isActiveCoordinator(UUID organizationId, UUID userId, UUID patientId) {
        LocalDate today = LocalDate.now();
        return coordinatorAssignments
                .findByOrganizationIdAndCoordinatorUserIdAndStatus(
                        organizationId, userId, CareCoordinatorAssignmentStatus.ACTIVE)
                .stream()
                .filter(a -> a.getPatientId().equals(patientId))
                .anyMatch(a -> inForce(a, today));
    }

    /** Whether {@code today} falls within the assignment's effective window (open-ended when no end date). */
    private static boolean inForce(CareCoordinatorAssignment a, LocalDate today) {
        boolean started = !today.isBefore(a.getEffectiveFrom());
        boolean notEnded = a.getEffectiveTo() == null || !today.isAfter(a.getEffectiveTo());
        return started && notEnded;
    }
}
