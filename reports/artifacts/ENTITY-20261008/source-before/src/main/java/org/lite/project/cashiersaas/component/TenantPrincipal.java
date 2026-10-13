package org.lite.project.cashiersaas.component;

import lombok.Getter;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;

import java.util.Set;
import java.util.UUID;

@Getter
public class TenantPrincipal {

    private final UUID userId;
    private final UUID tenantId;
    private final UUID outletId;
    private final UUID membershipId;
    private final UUID sessionId;
    private final Set<Role> roles;

    public TenantPrincipal(UUID userId, UUID tenantId, UUID outletId, UUID membershipId,
                           UUID sessionId, Set<Role> roles) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.outletId = outletId;
        this.membershipId = membershipId;
        this.sessionId = sessionId;
        this.roles = roles;
    }

    public TenantPrincipal(UUID userId, UUID tenantId, Set<Role> roles) {
        this(userId, tenantId, null, null, null, roles);
    }

    public void requireOutlet(UUID requestedOutletId) {
        if (outletId == null || !outletId.equals(requestedOutletId)) {
            throw new ResourceNotFoundException();
        }
    }
}
