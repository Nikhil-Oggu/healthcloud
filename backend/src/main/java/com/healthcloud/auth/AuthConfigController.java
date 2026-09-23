package com.healthcloud.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tiny, public "what sign-in methods are available" endpoint so the login page can render correctly
 * <b>before</b> anyone is authenticated. It reports whether the Cognito OIDC flow is actually configured
 * in this environment (the {@code cognito} profile wires a {@link ClientRegistrationRepository} with a
 * {@code cognito} registration; local dev without that profile has none).
 *
 * <p>Without this, the SPA would always show "Sign in with Cognito" and a click would hit
 * {@code /oauth2/authorization/cognito}, which returns a 500 when no client is configured — a confusing
 * "internal error" for a local run. The frontend uses this to disable the button and explain instead.
 *
 * <p>It also surfaces the <b>RP-initiated logout URL</b> so the SPA can fully sign the user out of the
 * Cognito hosted UI (not just the app session) on logout — otherwise Cognito's own cookie silently
 * re-authenticates the same user on the next "Sign in". Cognito's logout endpoint is non-standard
 * ({@code https://<hosted-ui-domain>/logout?client_id=..&logout_uri=..}), so it is built here from
 * configuration + the registered client id.
 *
 * <p>Exposes only a capability flag and a logout URL (a public hosted-UI URL + the non-secret client id +
 * a pre-registered redirect) — no client secret, nothing tenant-specific — so it is safe to permit
 * unauthenticated (see {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigController {

    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final String hostedUiDomain;
    private final String logoutRedirectUri;

    public AuthConfigController(
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            @Value("${healthcloud.cognito.hosted-ui-domain:}") String hostedUiDomain,
            @Value("${healthcloud.cognito.logout-redirect-uri:}") String logoutRedirectUri) {
        this.clientRegistrations = clientRegistrations;
        this.hostedUiDomain = hostedUiDomain;
        this.logoutRedirectUri = logoutRedirectUri;
    }

    @GetMapping("/config")
    public AuthConfigDto config() {
        ClientRegistration cognito = cognitoRegistration();
        return new AuthConfigDto(cognito != null, buildLogoutUrl(cognito));
    }

    private ClientRegistration cognitoRegistration() {
        ClientRegistrationRepository repo = clientRegistrations.getIfAvailable();
        // A repository exists only when an OIDC client is configured; confirm the specific `cognito` registration.
        return repo == null ? null : repo.findByRegistrationId("cognito");
    }

    /**
     * Build Cognito's non-standard hosted-UI logout URL, or {@code null} when Cognito isn't configured or the
     * hosted-UI domain / redirect aren't set (e.g. local dev without the {@code cognito} profile).
     */
    private String buildLogoutUrl(ClientRegistration cognito) {
        if (cognito == null || hostedUiDomain.isBlank() || logoutRedirectUri.isBlank()) {
            return null;
        }
        return "https://" + hostedUiDomain + "/logout?client_id=" + cognito.getClientId()
                + "&logout_uri=" + URLEncoder.encode(logoutRedirectUri, StandardCharsets.UTF_8);
    }

    /** The public sign-in capabilities of this environment. {@code cognitoLogoutUrl} is null when Cognito is off. */
    public record AuthConfigDto(boolean cognitoEnabled, String cognitoLogoutUrl) {}
}
