package com.healthcloud.coverage;

import com.healthcloud.context.UserContext;
import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.ApiException;
import com.healthcloud.error.ConflictException;
import com.healthcloud.error.ErrorCode;
import com.healthcloud.error.NotFoundException;
import com.healthcloud.identity.MembershipStatus;
import com.healthcloud.identity.OrganizationMembership;
import com.healthcloud.identity.OrganizationMembershipRepository;
import com.healthcloud.identity.UserRole;
import com.healthcloud.identity.UserRoleRepository;
import com.healthcloud.relationship.AssignmentCandidateDto;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan network-provider reads and administration, always scoped to the caller's tenant. A plan's network is the
 * set of PROVIDERs that participate in it — plan config (not PHI), so reads are open to any authenticated
 * same-tenant user (no relationship gate); add/remove require an ORG_ADMIN. A plan (or entry) in another tenant
 * is reported as "not found" (secure 404), never "forbidden". Mirrors {@link PlanPriorAuthRequirementService},
 * but the participant is a provider ({@code app_user}) validated as an active same-tenant PROVIDER (like
 * {@code ProviderPatientAssignmentService}) rather than a global catalog code.
 *
 * <p>Scope note (this slice): this only RECORDS a plan's network. The adjudication engine consumes it in a later
 * slice (a covered line rendered by an out-of-network provider becomes OUT_OF_NETWORK), and the claim gains a
 * rendering provider in between — exactly as {@code plan_exclusion} / {@code plan_prior_auth_requirement} were
 * introduced then wired.
 */
@Service
@Transactional(readOnly = true)
public class PlanNetworkProviderService {

    /** Roles allowed to administer a plan's network (reads are open to any same-tenant user). */
    private static final String[] WRITE_ROLES = {"ORG_ADMIN"};

    /** The role a network participant must hold. */
    private static final String PROVIDER_ROLE = "PROVIDER";

    private final PlanNetworkProviderRepository network;
    private final CoveragePlanRepository plans;
    private final OrganizationMembershipRepository memberships;
    private final UserRoleRepository userRoles;
    private final UserContextAccessor userContext;

    public PlanNetworkProviderService(PlanNetworkProviderRepository network, CoveragePlanRepository plans,
                                      OrganizationMembershipRepository memberships, UserRoleRepository userRoles,
                                      UserContextAccessor userContext) {
        this.network = network;
        this.plans = plans;
        this.memberships = memberships;
        this.userRoles = userRoles;
        this.userContext = userContext;
    }

    /** A plan's network providers in the caller's tenant, sorted by name (plan not in tenant → secure 404). */
    public List<PlanNetworkProviderDto> list(UUID planId) {
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);
        return network
                .findByOrganizationIdAndCoveragePlanIdOrderByCreatedAtAsc(organizationId, planId)
                .stream()
                .map(e -> PlanNetworkProviderDto.from(e, nameOf(organizationId, e.getProviderUserId())))
                .sorted((a, b) -> a.providerName().compareToIgnoreCase(b.providerName()))
                .toList();
    }

    /**
     * Same-tenant PROVIDERs who can be added to this plan's network — active members holding the PROVIDER role,
     * minus anyone already in the network. ORG_ADMIN only (mirrors the write gate); the plan must be in the
     * caller's tenant (else secure 404). Minimum-necessary fields, sorted by name — just enough to drive a picker.
     */
    public List<AssignmentCandidateDto> listCandidates(UUID planId) {
        userContext.requireAnyRole(WRITE_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);

        Set<UUID> alreadyInNetwork = network
                .findByOrganizationIdAndCoveragePlanIdOrderByCreatedAtAsc(organizationId, planId)
                .stream()
                .map(PlanNetworkProvider::getProviderUserId)
                .collect(Collectors.toSet());

        return memberships.findByOrganization_Id(organizationId).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .filter(this::isProvider)
                .filter(m -> !alreadyInNetwork.contains(m.getAppUser().getId()))
                .map(m -> new AssignmentCandidateDto(m.getAppUser().getId(), m.getAppUser().getFullName()))
                .sorted((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()))
                .toList();
    }

    /**
     * Add a provider to a plan's network (ORG_ADMIN). The provider must be an active same-tenant PROVIDER (else
     * 400, no existence leak); a provider already in the network is a 409.
     */
    @Transactional
    public PlanNetworkProviderDto add(UUID planId, AddNetworkProviderRequest request) {
        userContext.requireAnyRole(WRITE_ROLES);
        UserContext caller = userContext.requireUser();
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);

        OrganizationMembership provider = requireSameTenantProvider(organizationId, request.providerUserId());
        if (network.existsByOrganizationIdAndCoveragePlanIdAndProviderUserId(
                organizationId, planId, request.providerUserId())) {
            throw new ConflictException("That provider is already in this plan's network.");
        }

        PlanNetworkProvider saved = network.save(new PlanNetworkProvider(
                organizationId, planId, request.providerUserId(), caller.userId()));
        return PlanNetworkProviderDto.from(saved, provider.getAppUser().getFullName());
    }

    /** Remove a provider from a plan's network (ORG_ADMIN); a missing/cross-tenant/other-plan entry is a 404. */
    @Transactional
    public void remove(UUID planId, UUID networkProviderId) {
        userContext.requireAnyRole(WRITE_ROLES);
        UUID organizationId = userContext.requireOrganizationId();
        requirePlan(planId, organizationId);

        PlanNetworkProvider entry = network.findByIdAndOrganizationId(networkProviderId, organizationId)
                .filter(e -> e.getCoveragePlanId().equals(planId))
                .orElseThrow(NotFoundException::new);
        network.delete(entry);
    }

    private void requirePlan(UUID planId, UUID organizationId) {
        plans.findByIdAndOrganizationId(planId, organizationId).orElseThrow(NotFoundException::new);
    }

    /** The target must be an active same-tenant PROVIDER, else 400 (no existence leak of users). */
    private OrganizationMembership requireSameTenantProvider(UUID organizationId, UUID providerUserId) {
        OrganizationMembership membership = memberships
                .findByOrganization_IdAndAppUser_Id(organizationId, providerUserId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "The selected user cannot be added to this plan's network."));
        if (!isProvider(membership)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The selected user cannot be added to this plan's network.");
        }
        return membership;
    }

    /** Whether the membership's user holds the PROVIDER role. */
    private boolean isProvider(OrganizationMembership membership) {
        return userRoles.findByMembership_Id(membership.getId()).stream()
                .map(UserRole::getRole)
                .anyMatch(r -> PROVIDER_ROLE.equals(r.getCode()));
    }

    /** Resolve a same-tenant user's display name (best-effort; empty string if the membership is gone). */
    private String nameOf(UUID organizationId, UUID userId) {
        return memberships.findByOrganization_IdAndAppUser_Id(organizationId, userId)
                .map(m -> m.getAppUser().getFullName())
                .orElse("");
    }
}
