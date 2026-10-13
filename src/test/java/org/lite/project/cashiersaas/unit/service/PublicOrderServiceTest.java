package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.dto.request.PublicOrderRequest;
import org.lite.project.cashiersaas.dto.response.PublicOrderResponse;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.fnb.TableQrTokenEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.service.PublicOrderService;
import org.lite.project.cashiersaas.service.PublicOrderTokenService;
import org.lite.project.cashiersaas.service.PublicQrAccessService;
import org.lite.project.cashiersaas.service.FnbOrderPricingService;
import org.lite.project.cashiersaas.service.FnbRealtimePublisher;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PublicOrderServiceTest {
    @Mock private PublicQrAccessService publicQrAccess;
    @Mock private FnbCatalogRepository catalog;
    @Mock private FnbOrderRepository orders;
    @Mock private FnbRealtimePublisher realtime;

    private PublicOrderService service;
    private TenantEntity tenant;
    private OutletEntity outlet;
    private CategoryEntity category;
    private ProductEntity product;
    private TableQrTokenEntity outletQr;
    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID outletId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID productId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final String qrToken = "qr-token-0000000000000000000000000000000000000";

    @BeforeEach
    void setUp() {
        PublicOrderTokenService tokens = new PublicOrderTokenService(
                new SecretKeySpec("unit-test-public-order-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        service = new PublicOrderService(publicQrAccess, orders, tokens, new FnbOrderPricingService(catalog), realtime);
        tenant = new TenantEntity();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        ReflectionTestUtils.setField(tenant, "status", "ACTIVE");
        ReflectionTestUtils.setField(tenant, "taxRate", new BigDecimal("10.00"));
        ReflectionTestUtils.setField(tenant, "serviceChargeRate", new BigDecimal("5.00"));
        outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        ReflectionTestUtils.setField(outlet, "id", outletId);
        category = CategoryEntity.create(tenant, outlet, "Food", 1);
        product = ProductEntity.create(tenant, outlet,
                new ProductEntity.Details(category, "Nasi", null, new BigDecimal("99.95"), null, true, 1));
        ReflectionTestUtils.setField(product, "id", productId);
        outletQr = TableQrTokenEntity.outlet(tenant, outlet, qrToken);
    }

    @Test
    void checkoutCalculatesServerTotalsAndPersistsNewUnpaidSnapshot() {
        stubCompleteCheckoutPath();
        PublicOrderResponse response = service.checkout(qrToken, "checkout-key-0001", request(ServiceType.DINE_IN, 2));

        ArgumentCaptor<SalesOrderEntity> orderCaptor = ArgumentCaptor.forClass(SalesOrderEntity.class);
        ArgumentCaptor<OrderItemEntity> itemCaptor = ArgumentCaptor.forClass(OrderItemEntity.class);
        verify(orders).persist(orderCaptor.capture());
        verify(orders).persist(itemCaptor.capture());
        assertEquals(OrderStatus.NEW, response.getStatus());
        assertEquals(PaymentStatus.UNPAID, response.getPaymentStatus());
        assertEquals(new BigDecimal("199.90"), response.getSubtotal());
        assertEquals(new BigDecimal("19.99"), response.getTaxAmount());
        assertEquals(new BigDecimal("10.00"), response.getServiceChargeAmount());
        assertEquals(new BigDecimal("229.89"), response.getTotal());
        assertEquals(new BigDecimal("199.90"), orderCaptor.getValue().getSubtotal());
        assertEquals("Nasi", itemCaptor.getValue().getProductNameSnapshot());
        assertEquals(new BigDecimal("99.95"), itemCaptor.getValue().getUnitPriceSnapshot());
        assertEquals(2, itemCaptor.getValue().getQuantity());
        verify(orders).lockTenant(tenantId);
        verify(orders).flush();
        verify(realtime).orderChanged("ORDER_CREATED", orderCaptor.getValue());
        verifyNoMoreInteractions(realtime);
    }

    @Test
    void tableTakeawayIsRejectedBeforeCatalogAndPersistence() {
        stubQrAccess();
        org.lite.project.cashiersaas.entity.fnb.DiningTableEntity table =
                org.lite.project.cashiersaas.entity.fnb.DiningTableEntity.create(tenant, outlet, "T-01");
        ReflectionTestUtils.setField(table, "id", UUID.fromString("44444444-4444-4444-4444-444444444444"));
        TableQrTokenEntity tableQr = TableQrTokenEntity.create(tenant, outlet, table, qrToken);
        when(publicQrAccess.requirePublicQr(qrToken)).thenReturn(tableQr);

        assertThrows(InvalidOperationException.class,
                () -> service.checkout(qrToken, "checkout-key-0001", request(ServiceType.TAKEAWAY, 1)));

        verifyNoInteractions(catalog);
        verify(orders, never()).persist(any(SalesOrderEntity.class));
        verifyNoInteractions(realtime);
    }

    @Test
    void checkoutUsesHalfUpAtTaxBoundaryAndRejectsClientSelectedTotals() {
        stubCompleteCheckoutPath();
        ReflectionTestUtils.setField(product, "price", new BigDecimal("1.01"));
        ReflectionTestUtils.setField(tenant, "taxRate", new BigDecimal("12.50"));
        ReflectionTestUtils.setField(tenant, "serviceChargeRate", new BigDecimal("0.00"));

        PublicOrderResponse response = service.checkout(qrToken, "checkout-key-0001", request(ServiceType.DINE_IN, 1));

        assertEquals(new BigDecimal("1.01"), response.getSubtotal());
        assertEquals(new BigDecimal("0.13"), response.getTaxAmount());
        assertEquals(new BigDecimal("0.00"), response.getServiceChargeAmount());
        assertEquals(new BigDecimal("1.14"), response.getTotal());
        verify(realtime).orderChanged(eq("ORDER_CREATED"), any(SalesOrderEntity.class));
        verifyNoMoreInteractions(realtime);
    }

    @Test
    void replayWithSameKeyAndPayloadReturnsExistingOrderWithoutAnotherWrite() {
        stubCompleteCheckoutPath();
        PublicOrderRequest request = request(ServiceType.DINE_IN, 1);
        PublicOrderResponse first = service.checkout(qrToken, "checkout-key-0001", request);
        ArgumentCaptor<SalesOrderEntity> orderCaptor = ArgumentCaptor.forClass(SalesOrderEntity.class);
        verify(orders).persist(orderCaptor.capture());
        when(orders.byIdempotency(tenantId, "checkout-key-0001"))
                .thenReturn(Optional.of(orderCaptor.getValue()));
        verify(realtime).orderChanged("ORDER_CREATED", orderCaptor.getValue());
        clearInvocations(realtime);

        PublicOrderResponse replay = service.checkout(qrToken, "checkout-key-0001", request);

        assertEquals(first.getOrderNumber(), replay.getOrderNumber());
        assertEquals(first.getTotal(), replay.getTotal());
        verify(orders, org.mockito.Mockito.times(1)).persist(any(SalesOrderEntity.class));
        verifyNoInteractions(realtime);
    }

    @Test
    void replayWithSameKeyAndDifferentPayloadIsRejectedWithoutNewWrite() {
        stubQrAccess();
        SalesOrderEntity existing = SalesOrderEntity.publicQr(tenant, outlet,
                new SalesOrderEntity.PublicQrDetails(null, "checkout-key-0001", "different-fingerprint",
                        "QR-OLD", "unused", Instant.parse("2030-01-01T00:00:00Z"), "Customer"),
                new SalesOrderEntity.Charges(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO));
        when(orders.byIdempotency(tenantId, "checkout-key-0001")).thenReturn(Optional.of(existing));

        assertThrows(OperationConflictException.class,
                () -> service.checkout(qrToken, "checkout-key-0001", request(ServiceType.DINE_IN, 1)));

        verify(orders, never()).persist(any(SalesOrderEntity.class));
        verifyNoInteractions(catalog);
        verifyNoInteractions(realtime);
    }

    @Test
    void malformedIdempotencyKeyAndUnavailableProductAreRejectedBeforeWrite() {
        stubQrAccess();
        stubEmptyIdempotency();
        assertThrows(InvalidOperationException.class,
                () -> service.checkout(qrToken, "short", request(ServiceType.DINE_IN, 1)));
        verifyNoInteractions(publicQrAccess, orders, catalog);
        verifyNoInteractions(realtime);

        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.empty());
        assertThrows(InvalidOperationException.class,
                () -> service.checkout(qrToken, "checkout-key-0001", request(ServiceType.DINE_IN, 1)));
        verify(orders, never()).persist(any(SalesOrderEntity.class));
        verifyNoInteractions(realtime);
    }

    @Test
    void rotatedOrInactiveQrIsRejectedAfterTenantLockBeforeOrderWrite() {
        stubQrAccess();
        ReflectionTestUtils.setField(outletQr, "active", false);

        assertThrows(ResourceNotFoundException.class,
                () -> service.checkout(qrToken, "checkout-key-0001", request(ServiceType.DINE_IN, 1)));

        verify(orders).lockTenant(tenantId);
        verify(orders).refreshQr(outletQr);
        verify(orders, never()).byIdempotency(any(UUID.class), eq("checkout-key-0001"));
        verify(orders, never()).persist(any(SalesOrderEntity.class));
        verifyNoInteractions(realtime);
    }

    @Test
    void failedFlushDoesNotPublishCreatedOrder() {
        stubCompleteCheckoutPath();
        org.mockito.Mockito.doThrow(new IllegalStateException("flush failed")).when(orders).flush();

        assertThrows(IllegalStateException.class,
                () -> service.checkout(qrToken, "checkout-key-0001", request(ServiceType.DINE_IN, 1)));

        verifyNoInteractions(realtime);
    }

    @Test
    void validStatusResponseDoesNotExposeStatusTokenAgainAndInvalidTokenIsHidden() {
        SalesOrderEntity existing = SalesOrderEntity.publicQr(tenant, outlet,
                new SalesOrderEntity.PublicQrDetails(null, "checkout-key-0001", "fingerprint",
                        "QR-OLD", "digest", Instant.parse("2030-01-01T00:00:00Z"), "Customer"),
                new SalesOrderEntity.Charges(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO));
        String rawToken = new PublicOrderTokenService(
                new SecretKeySpec("unit-test-public-order-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .token(tenantId, "checkout-key-0001");
        ReflectionTestUtils.setField(existing, "publicStatusTokenDigest", new PublicOrderTokenService(
                new SecretKeySpec("unit-test-public-order-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .digest(rawToken));
        when(orders.byStatusDigest(any(String.class))).thenReturn(Optional.of(existing));

        PublicOrderResponse response = service.status(rawToken);

        assertEquals("QR-OLD", response.getOrderNumber());
        assertNull(response.getOrderToken());
        assertThrows(ResourceNotFoundException.class, () -> service.status("malformed"));
        verifyNoInteractions(realtime);
    }

    private void stubQrAccess() {
        when(publicQrAccess.requirePublicQr(qrToken)).thenReturn(outletQr);
    }

    private void stubEmptyIdempotency() {
        when(orders.byIdempotency(tenantId, "checkout-key-0001")).thenReturn(Optional.empty());
    }

    private void stubCompleteCheckoutPath() {
        stubQrAccess();
        stubEmptyIdempotency();
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.of(product));
        when(catalog.groups(tenantId, outletId, productId)).thenReturn(List.of());
    }

    private PublicOrderRequest request(ServiceType serviceType, int quantity) {
        PublicOrderRequest.Item item = new PublicOrderRequest.Item();
        item.setProductId(productId);
        item.setQuantity(quantity);
        item.setOptionIds(List.of());
        PublicOrderRequest request = new PublicOrderRequest();
        request.setServiceType(serviceType);
        request.setCustomerLabel(" Customer ");
        request.setItems(List.of(item));
        return request;
    }
}
