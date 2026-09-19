package com.healthcloud.auth;

import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.identity.AppUserStatus;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Loads the Cognito OIDC user (Phase 10 slice 12) and enforces that it maps to a provisioned, ACTIVE
 * {@link com.healthcloud.identity.AppUser} — matched by email. A Cognito login whose email has no active
 * app account is rejected <em>at login time</em>, so no orphan session is ever created.
 *
 * <p>Authentication is all this does. Roles, the active organization, and the tenant context are still
 * derived from the database by email (see {@code UserContextFilter}/{@code CurrentUserService}), never
 * from Cognito claims — the backend stays the single source of authorization truth (rule 4). Linking the
 * Cognito {@code sub} onto {@code AppUser.cognitoSub} is a later refinement; email is the join key today,
 * matching the dev-login path.
 */
@Component
public class CognitoOidcUserService extends OidcUserService {

    private final AppUserRepository appUsers;

    public CognitoOidcUserService(AppUserRepository appUsers) {
        this.appUsers = appUsers;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(userRequest);
        requireProvisioned(user);
        return user;
    }

    /**
     * Package-private so it is unit-testable without a live IdP: throws
     * {@link OAuth2AuthenticationException} unless an ACTIVE app user exists for the token's email.
     */
    void requireProvisioned(OidcUser user) {
        String email = user.getEmail();
        boolean provisioned = email != null
                && appUsers.findByEmailIgnoreCase(email)
                        .filter(u -> u.getStatus() == AppUserStatus.ACTIVE)
                        .isPresent();
        if (!provisioned) {
            // Generic message + no email echo — don't confirm which identities exist.
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied", "No active HealthCloud account for this identity.", null));
        }
    }
}
