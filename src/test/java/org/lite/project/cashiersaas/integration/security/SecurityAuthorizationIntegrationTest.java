package org.lite.project.cashiersaas.integration.security;

import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.lite.project.cashiersaas.component.JwtTokenService;
import org.lite.project.cashiersaas.component.MerchantTokenContext;
import org.lite.project.cashiersaas.configuration.JwtProperties;
import org.lite.project.cashiersaas.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class SecurityAuthorizationIntegrationTest extends AbstractPostgresIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtProperties jwtProperties;

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
    void removedPublicAndRealtimeRoutesAreProtectedLikeAnyOtherApiPath() throws Exception {
        mockMvc.perform(get("/api/v1/public/fnb/menu/x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/fnb/realtime"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cashierTokenCannotAccessPlatformEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/platform/probe")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(Role.CASHIER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerMerchantTokenPassesMerchantSecurityAndReachesMissingEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/merchant/probe")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(Role.OWNERMERCHANT)))
                .andExpect(status().isNotFound());
    }

    private String bearerTokenFor(Role role) {
        String token = jwtTokenService.generateAccessToken(MerchantTokenContext.builder()
                .userId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .outletId(UUID.randomUUID())
                .membershipId(UUID.randomUUID())
                .sessionId(UUID.randomUUID())
                .email("security-test@example.com")
                .roles(List.of(role))
                .expiresAt(Instant.now().plus(jwtProperties.getAccessTokenTtl()))
                .build());
        return "Bearer " + token;
    }
}
