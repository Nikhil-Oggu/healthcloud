package com.healthcloud.auth;

import tools.jackson.databind.ObjectMapper;
import com.healthcloud.context.UserContextFilter;
import com.healthcloud.error.RestAccessDeniedHandler;
import com.healthcloud.error.RestAuthenticationEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Session-based security for the API/BFF:
 * - unauthenticated protected requests get 401 (no login-page redirect);
 * - CSRF protection via a readable cookie token (the future SPA sends it back as a header);
 * - {@code /dev-login} is public and CSRF-exempt because it bootstraps the session (local only);
 * - the real Cognito OIDC login (BFF) is enabled ONLY when a client registration is configured
 *   (Phase 10 slice 12): its {@code /oauth2/**} + {@code /login/oauth2/**} endpoints are then public,
 *   and a login is accepted only for a provisioned, ACTIVE app user (see {@link CognitoOidcUserService});
 * - {@code /actuator/health} and {@code /actuator/info} are public; everything else requires auth.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ObjectMapper objectMapper,
                                            CurrentUserService currentUserService,
                                            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                            CognitoOidcUserService oidcUserService) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/dev-login").permitAll()
                        // The OIDC authorization request + Cognito callback must be reachable pre-auth.
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/v1/dev-login"))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                // After authorization succeeds, resolve the backend-derived caller/tenant context.
                .addFilterAfter(new UserContextFilter(currentUserService), AuthorizationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new RestAccessDeniedHandler(objectMapper)))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.OK.value()))
                        .invalidateHttpSession(true)
                        .deleteCookies("SESSION"))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));

        // Enable the Cognito OIDC login only when a ClientRegistration is configured (the `cognito`
        // profile / COGNITO_* env). Without it (offline/local dev, CI) the app boots on dev-login alone,
        // so builds and tests are unaffected. On success the OIDC user is mapped to an ACTIVE app user;
        // the email becomes the session principal (user-name-attribute: email) so UserContextFilter's
        // resolveByEmail keeps working unchanged.
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                    .defaultSuccessUrl("/", true));
        }

        return http.build();
    }
}
