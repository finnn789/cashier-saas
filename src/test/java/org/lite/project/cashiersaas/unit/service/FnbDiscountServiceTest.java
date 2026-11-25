package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbDiscountRequest;
import org.lite.project.cashiersaas.dto.response.FnbDiscountResponse;
import org.lite.project.cashiersaas.entity.fnb.DiscountEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.DiscountType;
import org.lite.project.cashiersaas.enums.Permission;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbDiscountRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.service.FnbDiscountService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Order discount rules: owners are uncapped, cashiers are limited by the tenant's cashierMaxDiscountPct. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbDiscountServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbOrderRepository orders;
    @Mock private FnbDiscountRepository discounts;
    @Mock private AuditLogRepository audit;
    @InjectMocks private FnbDiscountService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final TenantPrincipal owner = principal(Role.OWNERMERCHANT);
    private final TenantPrincipal cashier = principal(Role.CASHIER);
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);

    private TenantPrincipal principal(Role role) {
        return new TenantPrincipal(UUID.randomUUID(), tenantId, outletId, UUID.randomUUID(), UUID.randomUUID(), Set.of(role));
    }

    private SalesOrderEntity stubOrderAndGuard(TenantPrincipal principal, String subtotal) {
        TenantMembershipEntity membership = principal == owner
                ? TenantMembershipEntity.grantOwner(null, tenant) : TenantMembershipEntity.grantCashier(null, tenant);
        when(guard.require(principal, outletId, Permission.ORDER_DISCOUNT))
                .thenReturn(new MerchantAccessContext(tenant, outlet, membership));
        BigDecimal amount = new BigDecimal(subtotal);
        SalesOrderEntity order = SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("key", "fp", "ORD-1", ServiceType.TAKEAWAY, null, null),
                new SalesOrderEntity.Charges(amount, BigDecimal.ZERO, BigDecimal.ZERO));
        order.snapshotRates(BigDecimal.ZERO, BigDecimal.ZERO);
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
        return order;
    }

    private FnbDiscountRequest request(DiscountType type, String value) {
        FnbDiscountRequest request = new FnbDiscountRequest();
        request.setType(type);
        request.setValue(new BigDecimal(value));
        request.setReason("Promo");
        return request;
    }

    @ParameterizedTest
    @CsvSource({"CASHIER,20.00,PERCENTAGE,20,20000.00", "CASHIER,20.00,NOMINAL,20000,20000",
            "OWNER,0.00,PERCENTAGE,50,50000.00"})
    void discountAtTheCashierCapOrByTheOwnerIsGrantedAndAudited(String actor, String cap, DiscountType type, String value,
                                                               String expectedAmount) {
        TenantPrincipal principal = actor.equals("OWNER") ? owner : cashier;
        SalesOrderEntity order = stubOrderAndGuard(principal, "100000.00");
        tenant.changeCashierMaxDiscount(new BigDecimal(cap));

        FnbDiscountResponse response = service.grant(principal, outletId, orderId, request(type, value));

        assertEquals(0, new BigDecimal(expectedAmount).compareTo(response.getAmount()));
        assertEquals(0, new BigDecimal(expectedAmount).compareTo(order.getDiscountAmount()));
        verify(discounts).persist(any(DiscountEntity.class));
        verify(audit).persist(any(AuditLogEntity.class));
    }

    @ParameterizedTest
    @CsvSource({"20.00,PERCENTAGE,20.01,Diskon kasir maksimal 20%.", "20.00,NOMINAL,20000.01,Diskon kasir maksimal 20%.",
            "12.50,PERCENTAGE,13,Diskon kasir maksimal 12.5%.", "0.00,PERCENTAGE,0.01,Diskon kasir maksimal 0%."})
    void cashierDiscountOverTheCapIsRejectedWithTheCapInTheMessageAndChangesNothing(String cap, DiscountType type,
                                                                                    String value, String message) {
        SalesOrderEntity order = stubOrderAndGuard(cashier, "100000.00");
        tenant.changeCashierMaxDiscount(new BigDecimal(cap));

        InvalidOperationException error = assertThrows(InvalidOperationException.class,
                () -> service.grant(cashier, outletId, orderId, request(type, value)));

        assertEquals(message, error.getMessage());
        assertEquals(0, BigDecimal.ZERO.compareTo(order.getDiscountAmount()));
        verify(discounts, never()).persist(any(DiscountEntity.class));
        verify(audit, never()).persist(any(AuditLogEntity.class));
    }

    @Test
    void cashierNominalDiscountOnAZeroSubtotalIsOverEveryCap() {
        stubOrderAndGuard(cashier, "0.00");
        tenant.changeCashierMaxDiscount(new BigDecimal("20.00"));

        InvalidOperationException error = assertThrows(InvalidOperationException.class,
                () -> service.grant(cashier, outletId, orderId, request(DiscountType.NOMINAL, "1")));

        assertEquals("Diskon kasir maksimal 20%.", error.getMessage());
        verify(discounts, never()).persist(any(DiscountEntity.class));
    }
}
