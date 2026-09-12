package com.healthcloud.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.healthcloud.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the current-user authorization logic (Phase 1 slice 4): unauthenticated access is denied,
 * /me returns the right org/roles, identity is tenant-aware, and dev-login rejects unknown users.
 * The full session-cookie lifecycle is covered by {@link AuthenticationSessionIntegrationTest}
 * (which needs a real server). Runs under the {@code local} profile so the demo users exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class AuthenticationFlowTest {

    @Autowired MockMvc mockMvc;

    @Test
    void me_requires_authentication() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns_org_and_roles_for_authenticated_user() throws Exception {
        mockMvc.perform(get("/api/v1/me").with(user("provider@northcare.example.org")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("provider@northcare.example.org"))
                .andExpect(jsonPath("$.organizationName").value("NorthCare Health"))
                .andExpect(jsonPath("$.roles[0]").value("PROVIDER"));
    }

    @Test
    void identity_is_tenant_aware() throws Exception {
        mockMvc.perform(get("/api/v1/me").with(user("provider@greenvalley.example.org")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName").value("Green Valley Clinic"));
    }

    @Test
    void dev_login_with_unknown_email_is_rejected() throws Exception {
        mockMvc.perform(post("/api/v1/dev-login").param("email", "nobody@example.org"))
                .andExpect(status().isUnauthorized());
    }
}
