package com.healthcloud.auth;

import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.identity.MembershipStatus;
import com.healthcloud.identity.OrganizationMembership;
import com.healthcloud.identity.OrganizationMembershipRepository;
import com.healthcloud.identity.UserRole;
import com.healthcloud.identity.UserRoleRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the authenticated principal (identified by email) into a {@link CurrentUserDto}:
 * the user plus their active organization membership and roles. This is the seed of the
 * backend-derived user/tenant context that Slice 5 will formalize and enforce.
 */
@Service
public class CurrentUserService {

    private final AppUserRepository appUsers;
    private final OrganizationMembershipRepository memberships;
    private final UserRoleRepository userRoles;

    public CurrentUserService(AppUserRepository appUsers,
                              OrganizationMembershipRepository memberships,
                              UserRoleRepository userRoles) {
        this.appUsers = appUsers;
        this.memberships = memberships;
        this.userRoles = userRoles;
    }

    @Transactional(readOnly = true)
    public Optional<CurrentUserDto> resolveByEmail(String email) {
        return appUsers.findByEmailIgnoreCase(email).map(this::toDto);
    }

    private CurrentUserDto toDto(AppUser user) {
        Optional<OrganizationMembership> membership =
                memberships.findByAppUser_IdAndStatus(user.getId(), MembershipStatus.ACTIVE);

        if (membership.isEmpty()) {
            // e.g. a cross-organization user with no active membership
            return new CurrentUserDto(user.getId(), user.getEmail(), user.getFullName(),
                    null, null, List.of());
        }

        OrganizationMembership m = membership.get();
        List<String> roles = userRoles.findByMembership_Id(m.getId()).stream()
                .map(UserRole::getRole)
                .map(r -> r.getCode())
                .sorted()
                .toList();

        return new CurrentUserDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                m.getOrganization().getId(),
                m.getOrganization().getName(),
                roles);
    }
}
