package org.lite.project.cashiersaas.component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.enums.Role;

import java.util.Set;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class TenantPrincipal {

    private final UUID userId;
    private final UUID tenantId;
    private final Set<Role> roles;
}
