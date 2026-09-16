package com.healthcloud.identity;

import com.healthcloud.context.UserContextAccessor;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A read-only directory of the caller's tenant's active PROVIDERs, for a picker (§Phase 6 provider network — the
 * rendering-provider picker on claim create). Always scoped to the caller's tenant; a provider is an active
 * organization member holding the PROVIDER role. Gated to the claim-create roles (the audience who picks a
 * rendering provider) so a PATIENT login cannot enumerate staff — the directory is not PHI, but it is
 * minimum-necessary. Reuses the same "active same-tenant PROVIDER" resolution as
 * {@code PlanNetworkProviderService.listCandidates} / {@code ProviderPatientAssignmentService} (a future cleanup
 * can extract that shared provider lookup — noted, not done here).
 */
@Service
@Transactional(readOnly = true)
public class ProviderDirectoryService {

    /** Roles allowed to read the provider directory — the claim-create audience (a PATIENT cannot enumerate staff). */
    private static final String[] READ_ROLES = {"PROVIDER", "CARE_COORDINATOR", "ORG_ADMIN"};

    /** The role a directory entry must hold. */
    private static final String PROVIDER_ROLE = "PROVIDER";

    private final OrganizationMembershipRepository memberships;
    private final UserRoleRepository userRoles;
    private final UserContextAccessor userContext;

    public ProviderDirectoryService(OrganizationMembershipRepository memberships, UserRoleRepository userRoles,
                                    UserContextAccessor userContext) {
        this.memberships = memberships;
        this.userRoles = userRoles;
        this.userContext = userContext;
    }

    /** The caller's tenant's active PROVIDERs, sorted by name (minimum-necessary fields). */
    public List<ProviderDto> list() {
        userContext.requireAnyRole(READ_ROLES);
        var organizationId = userContext.requireOrganizationId();
        return memberships.findByOrganization_Id(organizationId).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .filter(this::isProvider)
                .map(m -> new ProviderDto(m.getAppUser().getId(), m.getAppUser().getFullName()))
                .sorted((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()))
                .toList();
    }

    /** Whether the membership's user holds the PROVIDER role. */
    private boolean isProvider(OrganizationMembership membership) {
        return userRoles.findByMembership_Id(membership.getId()).stream()
                .map(UserRole::getRole)
                .anyMatch(r -> PROVIDER_ROLE.equals(r.getCode()));
    }
}
