package org.lite.project.cashiersaas.component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class AccountPrincipal {

    private final UUID userId;
    private final String email;
}
