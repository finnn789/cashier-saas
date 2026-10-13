package org.lite.project.cashiersaas.component;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import org.lite.project.cashiersaas.enums.Role;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public final class MerchantTokenContext {
    private final UUID userId;
    private final UUID tenantId;
    private final UUID outletId;
    private final UUID membershipId;
    private final UUID sessionId;
    private final String email;
    @Singular
    private final List<Role> roles;
    private final Instant expiresAt;
}
