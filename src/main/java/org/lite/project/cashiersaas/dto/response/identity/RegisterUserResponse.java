package org.lite.project.cashiersaas.dto.response.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegisterUserResponse {

    private String accessToken;
    private String tokenType;
    private Instant expiresAt;

    public static RegisterUserResponse fromAccountRegistration(String accessToken, Instant expiresAt) {
        RegisterUserResponse response = new RegisterUserResponse();
        response.setAccessToken(accessToken);
        response.setTokenType("Bearer");
        response.setExpiresAt(expiresAt);
        return response;
    }
}
