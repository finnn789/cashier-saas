package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.enums.Permission;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.exception.runtime.OutletOverAllowanceException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.MerchantAccessRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MerchantAccessGuard {
    private final MerchantAccessRepository accessRepository;
    private final PlanEntitlementService planEntitlementService;

    public MerchantAccessContext requireOutlet(TenantPrincipal principal, UUID outletId) {
        return resolve(principal, outletId, true);
    }

    public MerchantAccessContext require(TenantPrincipal principal, UUID outletId, Permission permission) {
        MerchantAccessContext context = resolve(principal, outletId, true);
        if (!permission.allows(context.getMembership().getRole())) {
            throw new ResourceNotFoundException();
        }
        return context;
    }

    public TenantMembershipEntity requireMember(TenantPrincipal principal) {
        return accessRepository.findMember(principal.getUserId(), principal.getTenantId(),
                        principal.getRoles(), Instant.now())
                .orElseThrow(ResourceNotFoundException::new);
    }

    public MerchantAccessContext requireOwner(TenantPrincipal principal, UUID outletId) {
        return requireOwner(principal, outletId, true);
    }

    /** Owner outlet management and the consolidated report: an over-allowance token outlet must not lock the owner out. */
    public MerchantAccessContext requireOwnerIgnoringAllowance(TenantPrincipal principal, UUID outletId) {
        return requireOwner(principal, outletId, false);
    }

    private MerchantAccessContext requireOwner(TenantPrincipal principal, UUID outletId, boolean enforceAllowance) {
        MerchantAccessContext context = resolve(principal, outletId, enforceAllowance);
        if (context.getMembership().getRole() != Role.OWNERMERCHANT) {
            throw new ResourceNotFoundException();
        }
        return context;
    }

    private MerchantAccessContext resolve(TenantPrincipal principal, UUID outletId, boolean enforceAllowance) {
        principal.requireOutlet(outletId);
        Instant now = Instant.now();
        MerchantAccessRepository.OutletAccess access = accessRepository.findOutlet(
                        principal.getUserId(), principal.getTenantId(), outletId, principal.getRoles(), now)
                .orElseThrow(ResourceNotFoundException::new);
        if (enforceAllowance && !access.isUsable()) {
            throw new OutletOverAllowanceException(planEntitlementService.usableOutletIds(principal.getTenantId(), now));
        }
        TenantMembershipEntity membership = access.getMembership();
        return new MerchantAccessContext(membership.getTenant(), access.getOutlet(), membership);
    }
}
