package org.lite.project.cashiersaas.unit.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.component.FnbOrderChangedEvent;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.service.FnbRealtimePublisher;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class FnbRealtimePublisherTest {
    @Test
    void publishedSnapshotsRetainScopeAndStatusAfterOriginalOrderIsMutated() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        FnbRealtimePublisher publisher = new FnbRealtimePublisher(events);
        UUID tenantId = UUID.randomUUID(), outletId = UUID.randomUUID(), orderId = UUID.randomUUID();
        TenantEntity tenant = new TenantEntity();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        ReflectionTestUtils.setField(outlet, "id", outletId);
        SalesOrderEntity order = SalesOrderEntity.publicQr(tenant, outlet,
                new SalesOrderEntity.PublicQrDetails(null, "checkout-key-0001", "fingerprint",
                        "QR-TEST", "digest", Instant.parse("2100-01-01T00:00:00Z"), "Sensitive customer"),
                new SalesOrderEntity.Charges(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO));
        ReflectionTestUtils.setField(order, "id", orderId);
        Instant before = Instant.now();
        publisher.orderChanged("ORDER_CREATED", order);
        order.confirmPaid();
        publisher.orderChanged("PAYMENT_RECORDED", order);
        Instant after = Instant.now();
        order.startPreparing();
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(outlet, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(captor.capture());
        FnbOrderChangedEvent created = assertInstanceOf(FnbOrderChangedEvent.class, captor.getAllValues().get(0));
        FnbOrderChangedEvent paid = assertInstanceOf(FnbOrderChangedEvent.class, captor.getAllValues().get(1));
        assertEquals("ORDER_CREATED", created.getType());
        assertEquals(OrderStatus.NEW, created.getOrderStatus());
        assertEquals(PaymentStatus.UNPAID, created.getPaymentStatus());
        assertEquals("PAYMENT_RECORDED", paid.getType());
        assertEquals(OrderStatus.CONFIRMED, paid.getOrderStatus());
        assertEquals(PaymentStatus.PAID, paid.getPaymentStatus());
        for (FnbOrderChangedEvent snapshot : new FnbOrderChangedEvent[] {created, paid}) {
            assertEquals(tenantId, snapshot.getTenantId());
            assertEquals(outletId, snapshot.getOutletId());
            assertEquals(orderId, snapshot.getOrderId());
            assertFalse(snapshot.getOccurredAt().isBefore(before));
            assertFalse(snapshot.getOccurredAt().isAfter(after));
            assertNotNull(snapshot.getEventId());
        }
        assertNotEquals(created.getEventId(), paid.getEventId());
        assertEquals(OrderStatus.PREPARING, order.getStatus());
        verifyNoMoreInteractions(events);
    }
}
