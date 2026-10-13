package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbQrResponse;
import org.lite.project.cashiersaas.entity.fnb.TableQrTokenEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbTableRepository;
import org.lite.project.cashiersaas.service.FnbOrderQrService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbOrderQrServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbTableRepository tables;
    @Mock private FnbOrderRepository orders;
    @InjectMocks private FnbOrderQrService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, null);

    @Test
    void getRequiresOwnerAndReturnsScopedQrResponse() {
        TableQrTokenEntity qr = TableQrTokenEntity.outlet(tenant, outlet, token('a'));
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(tables.outletQr(tenantId, outletId)).thenReturn(Optional.of(qr));

        FnbQrResponse response = service.get(owner, outletId);

        assertEquals("OUTLET", response.getQrType());
        assertEquals(qr.getToken(), response.getToken());
        assertEquals("/api/v1/public/fnb/menu/" + qr.getToken(), response.getMenuPath());
        verify(guard).requireOwner(owner, outletId);
        verify(tables).outletQr(tenantId, outletId);
        verifyNoMoreInteractions(guard, tables, orders);
    }

    @Test
    void getHidesMissingQrAfterOwnerAuthorization() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(tables.outletQr(tenantId, outletId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(owner, outletId));
        verify(tables).outletQr(tenantId, outletId);
    }

    @Test
    void createAbsentOutletQrPersistsOnceAndReturnsRandomUrlSafeToken() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(tables.outletQr(tenantId, outletId)).thenReturn(Optional.empty());

        FnbQrResponse response = service.outletQr(owner, outletId, false);

        assertEquals("OUTLET", response.getQrType());
        assertTrue(response.getToken().matches("[A-Za-z0-9_-]{43}"));
        verify(orders).lockTenant(tenantId);
        verify(tables).persist(org.mockito.ArgumentMatchers.any(TableQrTokenEntity.class));
    }

    @Test
    void repeatedCreateReturnsExistingQrWithoutPersistingAnotherRow() {
        TableQrTokenEntity existing = TableQrTokenEntity.outlet(tenant, outlet, token('b'));
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(tables.outletQr(tenantId, outletId)).thenReturn(Optional.of(existing));

        FnbQrResponse response = service.outletQr(owner, outletId, false);

        assertEquals(existing.getToken(), response.getToken());
        verify(tables, never()).persist(org.mockito.ArgumentMatchers.any(TableQrTokenEntity.class));
    }

    @Test
    void rotationChangesExistingTokenWithoutCreatingAnotherQr() {
        TableQrTokenEntity existing = TableQrTokenEntity.outlet(tenant, outlet, token('c'));
        String original = existing.getToken();
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(tables.outletQr(tenantId, outletId)).thenReturn(Optional.of(existing));

        FnbQrResponse response = service.outletQr(owner, outletId, true);

        assertNotEquals(original, response.getToken());
        assertTrue(response.getToken().matches("[A-Za-z0-9_-]{43}"));
        verify(tables, never()).persist(org.mockito.ArgumentMatchers.any(TableQrTokenEntity.class));
    }

    @Test
    void rotationWithoutExistingQrFailsWithoutCreation() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(tables.outletQr(tenantId, outletId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.outletQr(owner, outletId, true));
        verify(tables, never()).persist(org.mockito.ArgumentMatchers.any(TableQrTokenEntity.class));
    }

    private String token(char value) {
        return String.valueOf(value).repeat(43);
    }
}
