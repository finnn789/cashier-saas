package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.entity.fnb.TableQrTokenEntity;
import org.lite.project.cashiersaas.entity.shared.billing.BusinessTypeEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbTableRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.service.PublicQrAccessService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PublicQrAccessServiceTest {
    @Mock private FnbTableRepository tables;
    @Mock private SubscriptionRepository subscriptions;
    @InjectMocks private PublicQrAccessService service;

    @Test
    void malformedOrMissingTokenIsRejectedBeforeRepositoryLookup() {
        assertThrows(ResourceNotFoundException.class, () -> service.requirePublicQr(null));
        assertThrows(ResourceNotFoundException.class, () -> service.requirePublicQr("too-short"));
        verify(tables, never()).byToken(any(String.class));
    }

    @Test
    void activeOutletQrIsAcceptedWithoutDiningTableForActiveFnbFreeTierTenant() {
        TenantEntity tenant = fnbTenant();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        TableQrTokenEntity qr = TableQrTokenEntity.outlet(tenant, outlet, validToken());
        when(tables.byToken(qr.getToken())).thenReturn(Optional.of(qr));
        when(subscriptions.findActiveForTenant(tenant.getId())).thenReturn(Optional.of(
                SubscriptionEntity.activateFreeTier(tenant)));

        assertSame(qr, service.requirePublicQr(qr.getToken()));
        verify(tables).byToken(qr.getToken());
        verify(subscriptions).findActiveForTenant(tenant.getId());
    }

    @Test
    void activeTableQrIsAcceptedWhenTenantOutletAndSubscriptionAreValid() {
        TenantEntity tenant = fnbTenant();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        org.lite.project.cashiersaas.entity.fnb.DiningTableEntity table =
                org.lite.project.cashiersaas.entity.fnb.DiningTableEntity.create(tenant, outlet, "A1");
        TableQrTokenEntity qr = TableQrTokenEntity.create(tenant, outlet, table, validToken());
        when(tables.byToken(qr.getToken())).thenReturn(Optional.of(qr));
        when(subscriptions.findActiveForTenant(tenant.getId())).thenReturn(Optional.of(
                SubscriptionEntity.activateFreeTier(tenant)));

        assertSame(qr, service.requirePublicQr(qr.getToken()));
    }

    @Test
    void inactiveTableOutletOrTenantIsHidden() {
        TenantEntity tenant = fnbTenant();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        org.lite.project.cashiersaas.entity.fnb.DiningTableEntity table =
                org.lite.project.cashiersaas.entity.fnb.DiningTableEntity.create(tenant, outlet, "A1");
        TableQrTokenEntity qr = TableQrTokenEntity.create(tenant, outlet, table, validToken());
        when(tables.byToken(qr.getToken())).thenReturn(Optional.of(qr));

        table.setActive(false);
        assertThrows(ResourceNotFoundException.class, () -> service.requirePublicQr(qr.getToken()));
        table.setActive(true);
        ReflectionTestUtils.setField(outlet, "active", false);
        assertThrows(ResourceNotFoundException.class, () -> service.requirePublicQr(qr.getToken()));
        ReflectionTestUtils.setField(outlet, "active", true);
        ReflectionTestUtils.setField(tenant, "status", "INACTIVE");
        assertThrows(ResourceNotFoundException.class, () -> service.requirePublicQr(qr.getToken()));
    }

    @Test
    void nonFnbTenantMissingSubscriptionOrNonFreeTierIsRejected() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        TableQrTokenEntity qr = TableQrTokenEntity.outlet(tenant, outlet, validToken());
        when(tables.byToken(qr.getToken())).thenReturn(Optional.of(qr));

        assertThrows(ResourceNotFoundException.class, () -> service.requirePublicQr(qr.getToken()));

        TenantEntity fnbTenant = fnbTenant();
        OutletEntity fnbOutlet = OutletEntity.open(fnbTenant, "Outlet", "Asia/Jakarta", null, null);
        TableQrTokenEntity fnbQr = TableQrTokenEntity.outlet(fnbTenant, fnbOutlet, validToken());
        when(tables.byToken(fnbQr.getToken())).thenReturn(Optional.of(fnbQr));
        when(subscriptions.findActiveForTenant(fnbTenant.getId())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.requirePublicQr(fnbQr.getToken()));

        org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity paidPlan =
                org.mockito.Mockito.mock(org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity.class);
        when(subscriptions.findActiveForTenant(fnbTenant.getId())).thenReturn(Optional.of(paidPlan));
        when(paidPlan.getPlanCode()).thenReturn("PRO");
        assertThrows(ResourceNotFoundException.class, () -> service.requirePublicQr(fnbQr.getToken()));
    }

    private TenantEntity fnbTenant() {
        TenantEntity tenant = new TenantEntity();
        BusinessTypeEntity businessType = new BusinessTypeEntity();
        ReflectionTestUtils.setField(businessType, "code", "FNB");
        ReflectionTestUtils.setField(tenant, "businessType", businessType);
        return tenant;
    }

    private String validToken() {
        return "A".repeat(43);
    }
}
