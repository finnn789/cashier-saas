package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.MerchantAccessRepository;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MerchantAccessGuardTest {
    @Mock private MerchantAccessRepository accessRepository;
    @InjectMocks private MerchantAccessGuard guard;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantPrincipal cashier = new TenantPrincipal(userId, tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.CASHIER));

    @Test
    void accessibleOutletReturnsContextFromOneRepositoryCall() {
        TenantEntity tenant = new TenantEntity();
        TenantMembershipEntity membership = TenantMembershipEntity.grantCashier(null, tenant);
        OutletEntity outlet = new OutletEntity();
        when(accessRepository.findOutlet(eq(userId), eq(tenantId), eq(outletId),
                eq(Set.of(Role.CASHIER)), any(Instant.class)))
                .thenReturn(Optional.of(new MerchantAccessRepository.OutletAccess(membership, outlet)));

        MerchantAccessContext context = guard.requireOutlet(cashier, outletId);

        assertEquals(tenant, context.getTenant());
        assertEquals(outlet, context.getOutlet());
        assertEquals(membership, context.getMembership());
        verify(accessRepository).findOutlet(eq(userId), eq(tenantId), eq(outletId),
                eq(Set.of(Role.CASHIER)), any(Instant.class));
        verifyNoMoreInteractions(accessRepository);
    }

    @Test
    void principalCannotSelectAnotherOutlet() {
        assertThrows(ResourceNotFoundException.class, () -> guard.requireOutlet(cashier, UUID.randomUUID()));
        verifyNoInteractions(accessRepository);
    }

    @Test
    void inaccessibleOutletIsHidden() {
        when(accessRepository.findOutlet(eq(userId), eq(tenantId), eq(outletId),
                eq(Set.of(Role.CASHIER)), any(Instant.class))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> guard.requireOutlet(cashier, outletId));
        verify(accessRepository).findOutlet(eq(userId), eq(tenantId), eq(outletId),
                eq(Set.of(Role.CASHIER)), any(Instant.class));
        verifyNoMoreInteractions(accessRepository);
    }

    @Test
    void memberLookupUsesOneRepositoryCall() {
        TenantMembershipEntity membership = TenantMembershipEntity.grantCashier(null, new TenantEntity());
        when(accessRepository.findMember(eq(userId), eq(tenantId), eq(Set.of(Role.CASHIER)),
                any(Instant.class))).thenReturn(Optional.of(membership));

        assertEquals(membership, guard.requireMember(cashier));
        verify(accessRepository).findMember(eq(userId), eq(tenantId), eq(Set.of(Role.CASHIER)),
                any(Instant.class));
        verifyNoMoreInteractions(accessRepository);
    }

    @Test
    void cashierCannotUseOwnerOperationEvenWithOutletAccess() {
        TenantMembershipEntity membership = TenantMembershipEntity.grantCashier(null, new TenantEntity());
        when(accessRepository.findOutlet(eq(userId), eq(tenantId), eq(outletId),
                eq(Set.of(Role.CASHIER)), any(Instant.class)))
                .thenReturn(Optional.of(new MerchantAccessRepository.OutletAccess(membership, new OutletEntity())));

        assertThrows(ResourceNotFoundException.class, () -> guard.requireOwner(cashier, outletId));
        verify(accessRepository).findOutlet(eq(userId), eq(tenantId), eq(outletId),
                eq(Set.of(Role.CASHIER)), any(Instant.class));
        verifyNoMoreInteractions(accessRepository);
    }
}
