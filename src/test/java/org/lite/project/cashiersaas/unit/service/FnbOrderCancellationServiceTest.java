package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbCancelOrderRequest;
import org.lite.project.cashiersaas.dto.response.FnbCancellationResponse;
import org.lite.project.cashiersaas.entity.fnb.DiscountEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.AccessForbiddenException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbDiscountRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.service.FnbOrderCancellationService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbOrderCancellationServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbOrderRepository orders;
    @Mock private FnbDiscountRepository discounts;
    @Mock private AuditLogRepository audit;
    @InjectMocks private FnbOrderCancellationService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final StaffUserEntity actor = StaffUserEntity.createCashier("a@example.com", "hash", "Aktor", null);
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenantId, Set.of(Role.CASHIER));
    private SalesOrderEntity order;

    @BeforeEach
    void setUp() {
        order = withId(SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("key-0001", "fp", "POS-1", ServiceType.DINE_IN, null, null),
                new SalesOrderEntity.Charges(new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO)), orderId);
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
    }

    private void actingAs(TenantMembershipEntity membership) {
        when(guard.requireOutlet(principal, outletId)).thenReturn(new MerchantAccessContext(tenant, outlet, membership));
    }

    private FnbCancelOrderRequest request() {
        FnbCancelOrderRequest request = new FnbCancelOrderRequest();
        request.setReason("Salah pesan");
        return request;
    }

    @Test
    void cashierCannotCancelADiscountedOrder() {
        actingAs(TenantMembershipEntity.grantCashier(actor, tenant));
        when(discounts.byOrder(tenantId, outletId, orderId)).thenReturn(Optional.of(mock(DiscountEntity.class)));

        AccessForbiddenException ex = assertThrows(AccessForbiddenException.class,
                () -> service.cancel(principal, outletId, orderId, request()));

        assertEquals("Pesanan dengan diskon hanya dapat dibatalkan oleh owner.", ex.getMessage());
        assertEquals(OrderStatus.OPEN, order.getStatus());
        verify(audit, never()).persist(any());
    }

    @Test
    void ownerIsNotBlockedByTheCashierDiscountRule() {
        actingAs(TenantMembershipEntity.grantOwner(actor, tenant));

        FnbCancellationResponse response = service.cancel(principal, outletId, orderId, request());

        assertEquals(OrderStatus.CANCELLED, response.getStatus());
        verify(audit).persist(any());
    }

    @Test
    void cashierCanCancelAnOrderWithoutDiscount() {
        actingAs(TenantMembershipEntity.grantCashier(actor, tenant));
        when(discounts.byOrder(tenantId, outletId, orderId)).thenReturn(Optional.empty());

        assertEquals(OrderStatus.CANCELLED, service.cancel(principal, outletId, orderId, request()).getStatus());
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
