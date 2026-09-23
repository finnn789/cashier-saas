package org.lite.project.cashiersaas.configuration.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.lite.project.cashiersaas.component.JwtTokenService;
import org.lite.project.cashiersaas.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
class SecurityAuthorizationIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void cashierEndpointRejectsRequestWithoutBearerTokenAndDoesNotCreateSession() throws Exception {
        mockMvc.perform(get("/api/v1/cashier/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertNull(result.getRequest().getSession(false)));
    }

    @Test
    void publicEndpointIsNotBlockedBySecurityWhenNoBearerTokenIsPresent() throws Exception {
        mockMvc.perform(get("/api/v1/public/probe"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cashierTokenCannotAccessPlatformEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/platform/probe")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(Role.CASHIER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void superadminTokenPassesPlatformSecurityAndReachesMissingEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/platform/probe")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(Role.SUPERADMIN)))
                .andExpect(status().isNotFound());
    }

    private String bearerTokenFor(Role role) {
        String token = jwtTokenService.generateAccessToken(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "security-test@example.com",
                List.of(role)
        );
        return "Bearer " + token;
    }
}
