package com.healthcloud.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.identity.AppUserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

/**
 * Unit-tests the provisioning gate of {@link CognitoOidcUserService} without a live IdP: a Cognito login
 * is accepted only for an ACTIVE app user (matched by email), and rejected otherwise.
 */
class CognitoOidcUserServiceTest {

    private static OidcUser oidcUserWithEmail(String email) {
        OidcIdToken idToken = new OidcIdToken(
                "token-value", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", "cognito-subject-123", "email", email));
        return new DefaultOidcUser(List.of(new OidcUserAuthority(idToken)), idToken, "email");
    }

    @Test
    void acceptsAnActiveAppUser() {
        AppUserRepository appUsers = mock(AppUserRepository.class);
        AppUser dana = new AppUser("provider@northcare.example.org", "Dana Provider");
        dana.setStatus(AppUserStatus.ACTIVE);
        when(appUsers.findByEmailIgnoreCase("provider@northcare.example.org")).thenReturn(Optional.of(dana));

        CognitoOidcUserService service = new CognitoOidcUserService(appUsers);
        assertThatCode(() -> service.requireProvisioned(oidcUserWithEmail("provider@northcare.example.org")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnEmailWithNoAppUser() {
        AppUserRepository appUsers = mock(AppUserRepository.class);
        when(appUsers.findByEmailIgnoreCase("stranger@elsewhere.example.org")).thenReturn(Optional.empty());

        CognitoOidcUserService service = new CognitoOidcUserService(appUsers);
        assertThatThrownBy(() -> service.requireProvisioned(oidcUserWithEmail("stranger@elsewhere.example.org")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void rejectsANonActiveAppUser() {
        AppUserRepository appUsers = mock(AppUserRepository.class);
        AppUser invited = new AppUser("invited@northcare.example.org", "Ivy Invited");
        invited.setStatus(AppUserStatus.INVITED);
        when(appUsers.findByEmailIgnoreCase("invited@northcare.example.org")).thenReturn(Optional.of(invited));

        CognitoOidcUserService service = new CognitoOidcUserService(appUsers);
        assertThatThrownBy(() -> service.requireProvisioned(oidcUserWithEmail("invited@northcare.example.org")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }
}
