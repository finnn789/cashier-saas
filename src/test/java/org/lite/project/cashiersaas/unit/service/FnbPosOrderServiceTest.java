package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbPosOrderRequest;
import org.lite.project.cashiersaas.dto.response.FnbStaffOrderResponse;
import org.lite.project.cashiersaas.entity.fnb.DiscountEntity;
import org.lite.project.cashiersaas.entity.fnb.FnbGatewayAttemptEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.BusinessTypeEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbDiscountRepository;
import org.lite.project.cashiersaas.repository.FnbGatewayAttemptRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.service.FnbOrderPricingService;
import org.lite.project.cashiersaas.service.FnbPosOrderService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbPosOrderServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbOrderRepository orders;
    @Mock private FnbOrderPricingService pricing;
    @Mock private FnbGatewayAttemptRepository attempts;
    @Mock private FnbDiscountRepository discounts;
    @Mock private AuditLogRepository audit;
    @InjectMocks private FnbPosOrderService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, new TenantMembershipEntity());
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenantId, Set.of(Role.CASHIER));
    private SalesOrderEntity order;

    @BeforeEach
    void setUp() {
        BusinessTypeEntity fnb = new BusinessTypeEntity();
        ReflectionTestUtils.setField(fnb, "code", "FNB");
        ReflectionTestUtils.setField(tenant, "businessType", fnb);
        // Tenant rates differ from the order snapshot: pricing must use the snapshot.
        ReflectionTestUtils.setField(tenant, "taxRate", new BigDecimal("11"));
        ReflectionTestUtils.setField(tenant, "serviceChargeRate", new BigDecimal("2"));
        order = withId(SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0001", "fingerprint", "POS-ORDER-1",
                        ServiceType.DINE_IN, "Budi"),
                new SalesOrderEntity.Charges(new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("5.00"))),
                orderId);
        order.snapshotRates(new BigDecimal("10"), new BigDecimal("5"));
        when(guard.requireOutlet(principal, outletId)).thenReturn(context);
    }

    @Test
    void happyPathReplacesLinesWithSnapshotRatesAndWritesAudit() {
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
        ProductEntity product = withId(new ProductEntity(), productId);
        ReflectionTestUtils.setField(product, "name", "Es Teh");
        ReflectionTestUtils.setField(product, "price", new BigDecimal("50.00"));
        FnbOrderPricingService.LineInput input = new FnbOrderPricingService.LineInput(productId, 4, "less ice", List.of());
        when(pricing.price(eq(tenantId), eq(outletId), anyList(), eq(new BigDecimal("10")), eq(new BigDecimal("5"))))
                .thenReturn(new FnbOrderPricingService.PricedOrder(
                        List.of(new FnbOrderPricingService.PricedLine(input, product, List.of(),
                                new BigDecimal("50.00"), new BigDecimal("200.00"))),
                        new BigDecimal("200.00"), new BigDecimal("20.00"), new BigDecimal("10.00"),
                        new BigDecimal("230.00")));

        FnbStaffOrderResponse response = service.replaceItems(principal, outletId, orderId,
                request(ServiceType.TAKEAWAY, " Siti ", 4));

        InOrder inOrder = inOrder(orders, pricing, audit);
        inOrder.verify(orders).lockTenant(tenantId);
        inOrder.verify(orders).byId(tenantId, outletId, orderId);
        inOrder.verify(pricing).price(eq(tenantId), eq(outletId), anyList(), eq(new BigDecimal("10")), eq(new BigDecimal("5")));
        inOrder.verify(orders).deleteLines(tenantId, outletId, order);
        ArgumentCaptor<OrderItemEntity> lineCaptor = ArgumentCaptor.forClass(OrderItemEntity.class);
        inOrder.verify(orders).persist(lineCaptor.capture());
        inOrder.verify(orders).flush();
        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        inOrder.verify(audit).persist(auditCaptor.capture());

        assertEquals(order, lineCaptor.getValue().getSalesOrder());
        assertEquals("Es Teh", lineCaptor.getValue().getProductNameSnapshot());
        assertEquals(4, lineCaptor.getValue().getQuantity());
        assertEquals(new BigDecimal("200.00"), order.getSubtotal());
        assertEquals(new BigDecimal("20.00"), order.getTaxAmount());
        assertEquals(new BigDecimal("10.00"), order.getServiceChargeAmount());
        assertEquals(new BigDecimal("230.00"), order.getTotal());
        assertEquals(ServiceType.TAKEAWAY, order.getServiceType());
        assertEquals("Siti", order.getCustomerLabel());
        assertEquals(OrderStatus.OPEN, order.getStatus());
        assertEquals("fingerprint", order.getRequestFingerprint());
        assertEquals("checkout-key-0001", order.getIdempotencyKey());

        AuditLogEntity log = auditCaptor.getValue();
        assertEquals("FNB_ORDER_ITEMS_REPLACED", log.getAction());
        assertEquals("SALES_ORDER", log.getEntityType());
        assertEquals(orderId, log.getEntityId());
        assertEquals("subtotal=100.00->200.00", log.getDetails());
        assertNotNull(response);
        assertEquals(new BigDecimal("230.00"), response.getTotal());
    }

    @Test
    void lockTenantRunsBeforeTheOrderIsLoaded() {
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceItems(principal, outletId, orderId, request(ServiceType.DINE_IN, "Budi", 1)));

        InOrder inOrder = inOrder(orders);
        inOrder.verify(orders).lockTenant(tenantId);
        inOrder.verify(orders).byId(tenantId, outletId, orderId);
    }

    @Test
    void missingOrderIsNotFoundAndTouchesNothing() {
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceItems(principal, outletId, orderId, request(ServiceType.DINE_IN, "Budi", 1)));

        verifyUntouched();
    }

    @Test
    void paidOrderIsRejected() {
        order.confirmPaid();
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));

        assertThrows(OperationConflictException.class,
                () -> service.replaceItems(principal, outletId, orderId, request(ServiceType.DINE_IN, "Budi", 1)));

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(new BigDecimal("115.00"), order.getTotal());
        verifyUntouched();
    }

    @Test
    void cancelledOrderIsRejected() {
        order.cancel("Salah pesan");
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));

        assertThrows(OperationConflictException.class,
                () -> service.replaceItems(principal, outletId, orderId, request(ServiceType.DINE_IN, "Budi", 1)));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verifyUntouched();
    }

    @Test
    void qrSourcedOrderIsRejected() {
        ReflectionTestUtils.setField(order, "source", OrderSource.QR);
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));

        assertThrows(OperationConflictException.class,
                () -> service.replaceItems(principal, outletId, orderId, request(ServiceType.DINE_IN, "Budi", 1)));

        verifyUntouched();
    }

    @Test
    void pendingGatewayAttemptIsRejected() {
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
        when(attempts.pendingForOrder(tenantId, outletId, orderId)).thenReturn(Optional.of(new FnbGatewayAttemptEntity()));

        assertThrows(OperationConflictException.class,
                () -> service.replaceItems(principal, outletId, orderId, request(ServiceType.DINE_IN, "Budi", 1)));

        verifyUntouched();
    }

    @Test
    void existingDiscountIsRejected() {
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
        when(discounts.byOrder(tenantId, outletId, orderId)).thenReturn(Optional.of(new DiscountEntity()));

        assertThrows(OperationConflictException.class,
                () -> service.replaceItems(principal, outletId, orderId, request(ServiceType.DINE_IN, "Budi", 1)));

        verifyUntouched();
    }

    private void verifyUntouched() {
        verify(orders, never()).deleteLines(any(UUID.class), any(UUID.class), any(SalesOrderEntity.class));
        verify(orders, never()).persist(any(OrderItemEntity.class));
        verify(pricing, never()).price(any(UUID.class), any(UUID.class), anyList(), any(BigDecimal.class), any(BigDecimal.class));
        verify(audit, never()).persist(any(AuditLogEntity.class));
        assertEquals(new BigDecimal("100.00"), order.getSubtotal());
    }

    private FnbPosOrderRequest request(ServiceType serviceType, String customerLabel, int quantity) {
        FnbPosOrderRequest.Item item = new FnbPosOrderRequest.Item();
        item.setProductId(productId);
        item.setQuantity(quantity);
        item.setNote("less ice");
        item.setOptionIds(List.of());
        FnbPosOrderRequest request = new FnbPosOrderRequest();
        request.setServiceType(serviceType);
        request.setCustomerLabel(customerLabel);
        request.setItems(List.of(item));
        return request;
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
