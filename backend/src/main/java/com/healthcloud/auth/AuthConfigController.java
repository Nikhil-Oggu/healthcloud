package com.healthcloud.auth;

import org.springframework.beans.factory.ObjectProvider;
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
 * <p>Exposes only a boolean capability flag — no secrets, no client id, nothing tenant-specific — so it is
 * safe to permit unauthenticated (see {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigController {

    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    public AuthConfigController(ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.clientRegistrations = clientRegistrations;
    }

    @GetMapping("/config")
    public AuthConfigDto config() {
        return new AuthConfigDto(cognitoConfigured());
    }

    private boolean cognitoConfigured() {
        ClientRegistrationRepository repo = clientRegistrations.getIfAvailable();
        // A repository exists only when an OIDC client is configured; confirm the specific `cognito` registration.
        return repo != null && repo.findByRegistrationId("cognito") != null;
    }

    /** The public sign-in capabilities of this environment. */
    public record AuthConfigDto(boolean cognitoEnabled) {}
}
