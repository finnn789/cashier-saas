package org.lite.project.cashiersaas.component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class AccountPrincipal {

    private final UUID userId;
    private final String email;
    private final UUID sessionId;

    public AccountPrincipal(UUID userId, String email) {
        this.userId = userId;
        this.email = email;
        this.sessionId = null;
    }
}
