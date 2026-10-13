package org.lite.project.cashiersaas.dto.response.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.shared.AuthenticationConstants;

import java.time.Instant;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegisterUserResponse {

    private String accessToken;
    private String tokenType;
    private Instant expiresAt;
    @JsonIgnore
    private String refreshToken;

    public static RegisterUserResponse fromAccountRegistration(String accessToken, Instant expiresAt) {
        RegisterUserResponse response = new RegisterUserResponse();
        response.setAccessToken(accessToken);
        response.setTokenType(AuthenticationConstants.BEARER_SCHEME);
        response.setExpiresAt(expiresAt);
        return response;
    }
}
