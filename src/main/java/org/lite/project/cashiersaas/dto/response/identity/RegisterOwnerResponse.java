package org.lite.project.cashiersaas.dto.response.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegisterOwnerResponse {

    private String accessToken;
    private String tokenType;
    private Instant expiresAt;
    private UUID tenantId;
    private String role;

    public static RegisterOwnerResponse fromBusinessProfile(
            String accessToken,
            Instant expiresAt,
            UUID tenantId,
            String role
    ) {
        RegisterOwnerResponse response = new RegisterOwnerResponse();
        response.setAccessToken(accessToken);
        response.setTokenType("Bearer");
        response.setExpiresAt(expiresAt);
        response.setTenantId(tenantId);
        response.setRole(role);
        return response;
    }
}
