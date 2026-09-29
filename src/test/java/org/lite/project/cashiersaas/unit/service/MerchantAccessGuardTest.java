package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.billing.BusinessTypeEntity;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MerchantAccessGuardTest {
    @Mock private TenantMembershipRepository membershipRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private OutletRepository outletRepository;
    @Mock private MembershipOutletAccessRepository accessRepository;
    @InjectMocks private MerchantAccessGuard guard;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantPrincipal cashier = new TenantPrincipal(userId, tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.CASHIER));

    @Test
    void cashierWithActiveSubscriptionAndOutletGrantCanAccessOutlet() {
        TenantEntity tenant = activeTenant();
        TenantMembershipEntity membership = cashierMembership(tenant);
        OutletEntity outlet = outlet();
        when(membershipRepository.findActive(userId, tenantId)).thenReturn(Optional.of(membership));
        when(subscriptionRepository.findActiveForTenant(tenantId))
                .thenReturn(Optional.of(SubscriptionEntity.activateFreeTier(tenant)));
        when(outletRepository.findActive(tenantId, outletId)).thenReturn(Optional.of(outlet));
        when(accessRepository.findAccess(tenantId, membership.getId(), outletId))
                .thenReturn(Optional.of(new MembershipOutletAccessEntity()));

        MerchantAccessContext context = guard.requireOutlet(cashier, outletId);

        assertEquals(tenant, context.getTenant());
        assertEquals(outlet, context.getOutlet());
        assertEquals(membership, context.getMembership());
    }

    @Test
    void jwtRoleWithoutMatchingMembershipDoesNotGrantAccess() {
        TenantMembershipEntity ownerMembership = TenantMembershipEntity.grantOwner(activeUser(), activeTenant());
        when(membershipRepository.findActive(userId, tenantId)).thenReturn(Optional.of(ownerMembership));

        assertThrows(ResourceNotFoundException.class, () -> guard.requireOutlet(cashier, outletId));
        verify(subscriptionRepository, never()).findActiveForTenant(tenantId);
        verify(outletRepository, never()).findActive(tenantId, outletId);
    }

    @Test
    void missingSubscriptionDeniesBeforeOutletLookup() {
        when(membershipRepository.findActive(userId, tenantId))
                .thenReturn(Optional.of(cashierMembership(activeTenant())));
        when(subscriptionRepository.findActiveForTenant(tenantId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> guard.requireOutlet(cashier, outletId));
        verify(outletRepository, never()).findActive(tenantId, outletId);
    }

    @Test
    void disabledLaundryTenantCannotUseExistingOutletGrant() {
        TenantEntity tenant = activeTenant();
        ReflectionTestUtils.setField(tenant.getBusinessType(), "code", "LAUNDRY");
        when(membershipRepository.findActive(userId, tenantId))
                .thenReturn(Optional.of(cashierMembership(tenant)));

        assertThrows(ResourceNotFoundException.class, () -> guard.requireOutlet(cashier, outletId));
        verify(subscriptionRepository, never()).findActiveForTenant(tenantId);
        verify(outletRepository, never()).findActive(tenantId, outletId);
    }

    @Test
    void outletWithoutAccessGrantIsHidden() {
        TenantEntity tenant = activeTenant();
        TenantMembershipEntity membership = cashierMembership(tenant);
        when(membershipRepository.findActive(userId, tenantId)).thenReturn(Optional.of(membership));
        when(subscriptionRepository.findActiveForTenant(tenantId))
                .thenReturn(Optional.of(SubscriptionEntity.activateFreeTier(tenant)));
        when(outletRepository.findActive(tenantId, outletId)).thenReturn(Optional.of(outlet()));
        when(accessRepository.findAccess(tenantId, membership.getId(), outletId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> guard.requireOutlet(cashier, outletId));
        verify(accessRepository).findAccess(tenantId, membership.getId(), outletId);
    }

    @Test
    void cashierCannotUseOwnerOperationEvenWhenOutletIsAccessible() {
        TenantEntity tenant = activeTenant();
        TenantMembershipEntity membership = cashierMembership(tenant);
        when(membershipRepository.findActive(userId, tenantId)).thenReturn(Optional.of(membership));
        when(subscriptionRepository.findActiveForTenant(tenantId))
                .thenReturn(Optional.of(SubscriptionEntity.activateFreeTier(tenant)));
        when(outletRepository.findActive(tenantId, outletId)).thenReturn(Optional.of(outlet()));
        when(accessRepository.findAccess(tenantId, membership.getId(), outletId))
                .thenReturn(Optional.of(new MembershipOutletAccessEntity()));

        assertThrows(ResourceNotFoundException.class, () -> guard.requireOwner(cashier, outletId));
    }

    private TenantEntity activeTenant() {
        TenantEntity tenant = new TenantEntity();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        BusinessTypeEntity fnb = new BusinessTypeEntity();
        ReflectionTestUtils.setField(fnb, "code", "FNB");
        ReflectionTestUtils.setField(tenant, "businessType", fnb);
        return tenant;
    }

    private StaffUserEntity activeUser() {
        return StaffUserEntity.createCashier("cashier@example.com", "hash", "Kasir");
    }

    private TenantMembershipEntity cashierMembership(TenantEntity tenant) {
        TenantMembershipEntity membership = TenantMembershipEntity.grantCashier(activeUser(), tenant);
        ReflectionTestUtils.setField(membership, "id", UUID.randomUUID());
        return membership;
    }

    private OutletEntity outlet() {
        OutletEntity outlet = new OutletEntity();
        ReflectionTestUtils.setField(outlet, "id", outletId);
        return outlet;
    }
}
