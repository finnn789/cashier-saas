package org.lite.project.cashiersaas.dto.response.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.Role;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponse {

    private String accessToken;
    private String tokenType;
    private Instant expiresAt;
    private Role role;
    private UUID tenantId;
    private UUID outletId;
    private String nextStep;
    @JsonIgnore
    private String refreshToken;
}
