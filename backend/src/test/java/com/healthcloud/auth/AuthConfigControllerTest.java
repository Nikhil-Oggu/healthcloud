package com.healthcloud.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Plain unit tests for the public sign-in-capability endpoint — no Spring context needed. Covers the
 * "is Cognito configured" flag and the RP-initiated logout URL it builds for the SPA.
 */
class AuthConfigControllerTest {

    private static final String DOMAIN = "healthcloud-dev-1.auth.us-east-1.amazoncognito.com";
    private static final String REDIRECT = "http://localhost:5173/";

    private ClientRegistration cognitoRegistration() {
        return ClientRegistration.withRegistrationId("cognito")
                .clientId("test-client-id")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/cognito")
                .authorizationUri("https://example/authorize")
                .tokenUri("https://example/token")
                .build();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ClientRegistrationRepository> provider(ClientRegistrationRepository repo) {
        ObjectProvider<ClientRegistrationRepository> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(repo);
        return p;
    }

    @Test
    void reportsDisabledAndNoLogoutUrlWhenNoClientConfigured() {
        var controller = new AuthConfigController(provider(null), DOMAIN, REDIRECT);

        var dto = controller.config();

        assertThat(dto.cognitoEnabled()).isFalse();
        assertThat(dto.cognitoLogoutUrl()).isNull();
    }

    @Test
    void buildsLogoutUrlWhenCognitoConfigured() {
        ClientRegistrationRepository repo = mock(ClientRegistrationRepository.class);
        when(repo.findByRegistrationId("cognito")).thenReturn(cognitoRegistration());

        var controller = new AuthConfigController(provider(repo), DOMAIN, REDIRECT);
        var dto = controller.config();

        assertThat(dto.cognitoEnabled()).isTrue();
        // Cognito's non-standard logout endpoint: /logout?client_id=..&logout_uri=<url-encoded redirect>
        assertThat(dto.cognitoLogoutUrl())
                .isEqualTo("https://" + DOMAIN + "/logout?client_id=test-client-id"
                        + "&logout_uri=http%3A%2F%2Flocalhost%3A5173%2F");
    }

    @Test
    void noLogoutUrlWhenHostedUiDomainMissing() {
        ClientRegistrationRepository repo = mock(ClientRegistrationRepository.class);
        when(repo.findByRegistrationId("cognito")).thenReturn(cognitoRegistration());

        var controller = new AuthConfigController(provider(repo), "", REDIRECT);
        var dto = controller.config();

        assertThat(dto.cognitoEnabled()).isTrue();
        assertThat(dto.cognitoLogoutUrl()).isNull();
    }
}
