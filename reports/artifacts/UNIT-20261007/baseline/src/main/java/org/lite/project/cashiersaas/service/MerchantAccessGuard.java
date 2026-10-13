package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.MerchantAccessRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MerchantAccessGuard {
    private final MerchantAccessRepository accessRepository;

    public MerchantAccessContext requireOutlet(TenantPrincipal principal, UUID outletId) {
        principal.requireOutlet(outletId);
        MerchantAccessRepository.OutletAccess access = accessRepository.findOutlet(
                        principal.getUserId(), principal.getTenantId(), outletId, principal.getRoles(), Instant.now())
                .orElseThrow(ResourceNotFoundException::new);
        TenantMembershipEntity membership = access.getMembership();
        return new MerchantAccessContext(membership.getTenant(), access.getOutlet(), membership);
    }

    public TenantMembershipEntity requireMember(TenantPrincipal principal) {
        return accessRepository.findMember(principal.getUserId(), principal.getTenantId(),
                        principal.getRoles(), Instant.now())
                .orElseThrow(ResourceNotFoundException::new);
    }

    public MerchantAccessContext requireOwner(TenantPrincipal principal, UUID outletId) {
        MerchantAccessContext context = requireOutlet(principal, outletId);
        if (context.getMembership().getRole() != Role.OWNERMERCHANT) {
            throw new ResourceNotFoundException();
        }
        return context;
    }
}
