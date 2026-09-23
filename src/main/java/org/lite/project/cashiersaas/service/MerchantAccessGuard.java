package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MerchantAccessGuard {
    private final TenantMembershipRepository membershipRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OutletRepository outletRepository;
    private final MembershipOutletAccessRepository accessRepository;

    public MerchantAccessContext requireOutlet(TenantPrincipal principal, UUID outletId) {
        TenantMembershipEntity membership = requireMember(principal);
        var outlet = outletRepository.findActive(principal.getTenantId(), outletId)
                .orElseThrow(ResourceNotFoundException::new);
        accessRepository.findAccess(principal.getTenantId(), membership.getId(), outletId)
                .orElseThrow(ResourceNotFoundException::new);
        return new MerchantAccessContext(membership.getTenant(), outlet, membership);
    }

    public TenantMembershipEntity requireMember(TenantPrincipal principal) {
        TenantMembershipEntity membership = membershipRepository.findActive(principal.getUserId(), principal.getTenantId())
                .filter(value -> value.getUser().isActive())
                .filter(value -> value.getTenant().isActive())
                .filter(value -> principal.getRoles().contains(value.getRole()))
                .orElseThrow(ResourceNotFoundException::new);
        boolean subscribed = subscriptionRepository.findActiveForTenant(principal.getTenantId())
                .map(value -> "FREETIER".equals(value.getPlanCode()) && value.isActiveAt(Instant.now()))
                .orElse(false);
        if (!subscribed) {
            throw new ResourceNotFoundException();
        }
        return membership;
    }

    public MerchantAccessContext requireOwner(TenantPrincipal principal, UUID outletId) {
        MerchantAccessContext context = requireOutlet(principal, outletId);
        if (context.getMembership().getRole() != Role.OWNERMERCHANT) {
            throw new ResourceNotFoundException();
        }
        return context;
    }
}
