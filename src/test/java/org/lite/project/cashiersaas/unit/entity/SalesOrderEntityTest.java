package org.lite.project.cashiersaas.unit.entity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class SalesOrderEntityTest {
    @Test
    void posFactoryCreatesOpenUnpaidOrderWithImmutableCheckoutSnapshots() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);

        SalesOrderEntity order = SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0001", "fingerprint", "POS-ORDER-1",
                        ServiceType.DINE_IN, "Customer", null),
                new SalesOrderEntity.Charges(new BigDecimal("100.00"),
                        new BigDecimal("11.00"), new BigDecimal("5.00")));

        assertEquals(tenant, order.getTenant());
        assertEquals(outlet, order.getOutlet());
        assertEquals(OrderSource.POS, order.getSource());
        assertEquals(ServiceType.DINE_IN, order.getServiceType());
        assertEquals(OrderStatus.OPEN, order.getStatus());
        assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus());
        assertEquals(new BigDecimal("100.00"), order.getSubtotal());
        assertEquals(new BigDecimal("11.00"), order.getTaxAmount());
        assertEquals(new BigDecimal("5.00"), order.getServiceChargeAmount());
        assertEquals(new BigDecimal("116.00"), order.getTotal());
        assertEquals("fingerprint", order.getRequestFingerprint());
    }

    @Test
    void posFactorySupportsTakeawayAndRejectsNullServiceType() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        SalesOrderEntity.Charges charges = new SalesOrderEntity.Charges(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);

        SalesOrderEntity order = SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0001", "fingerprint", "POS-ORDER-1",
                        ServiceType.TAKEAWAY, "Customer", null), charges);

        assertEquals(ServiceType.TAKEAWAY, order.getServiceType());
        assertThrows(InvalidOperationException.class, () -> SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0002", "fingerprint", "POS-ORDER-2",
                        null, "Customer", null), charges));
    }

    private static SalesOrderEntity openOrder() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        SalesOrderEntity order = SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0001", "fingerprint", "POS-ORDER-1",
                        ServiceType.DINE_IN, "Customer", null),
                new SalesOrderEntity.Charges(new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("5.00")));
        order.snapshotRates(new BigDecimal("10"), new BigDecimal("5"));
        return order;
    }

    private static SalesOrderEntity.Charges charges(String subtotal, String tax, String serviceCharge) {
        return new SalesOrderEntity.Charges(new BigDecimal(subtotal), new BigDecimal(tax), new BigDecimal(serviceCharge));
    }

    @Test
    void confirmPaidMovesOpenUnpaidOrderToCompletedAndPaid() {
        SalesOrderEntity order = openOrder();

        order.confirmPaid();

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertThrows(InvalidOperationException.class, order::confirmPaid);
    }

    @Test
    void cancelOnlyOnOpenUnpaidOrderAndRequiresReason() {
        SalesOrderEntity order = openOrder();
        assertThrows(InvalidOperationException.class, () -> order.cancel(" "));
        assertEquals(OrderStatus.OPEN, order.getStatus());

        order.cancel(" Salah pesan ");

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals("Salah pesan", order.getCancellationReason());
        assertThrows(InvalidOperationException.class, () -> order.cancel("again"));

        SalesOrderEntity paid = openOrder();
        paid.confirmPaid();
        assertThrows(InvalidOperationException.class, () -> paid.cancel("too late"));
        assertEquals(OrderStatus.COMPLETED, paid.getStatus());
    }

    @Test
    void discountOnlyOnOpenUnpaidOrder() {
        SalesOrderEntity order = openOrder();

        order.applyDiscount(new BigDecimal("10.00"));

        assertEquals(new BigDecimal("10.00"), order.getDiscountAmount());
        // (100 - 10) x 10% = 9.00 tax, 90 x 5% = 4.50 service charge.
        assertEquals(new BigDecimal("103.50"), order.getTotal());

        SalesOrderEntity paid = openOrder();
        paid.confirmPaid();
        assertThrows(InvalidOperationException.class, () -> paid.applyDiscount(BigDecimal.ONE));
        SalesOrderEntity cancelled = openOrder();
        cancelled.cancel("Batal");
        assertThrows(InvalidOperationException.class, () -> cancelled.applyDiscount(BigDecimal.ONE));
    }

    @Test
    void replaceLinesRecomputesTotalsOnOpenUnpaidOrder() {
        SalesOrderEntity order = openOrder();

        order.replaceLines(ServiceType.TAKEAWAY, "Budi", null, charges("200.00", "20.00", "10.00"));

        assertEquals(ServiceType.TAKEAWAY, order.getServiceType());
        assertEquals("Budi", order.getCustomerLabel());
        assertEquals(new BigDecimal("200.00"), order.getSubtotal());
        assertEquals(new BigDecimal("20.00"), order.getTaxAmount());
        assertEquals(new BigDecimal("10.00"), order.getServiceChargeAmount());
        assertEquals(new BigDecimal("230.00"), order.getTotal());
        assertEquals(OrderStatus.OPEN, order.getStatus());
        assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus());
        assertEquals("fingerprint", order.getRequestFingerprint());
    }

    @Test
    void replaceLinesRejectsPaidCancelledDiscountedAndNullServiceType() {
        SalesOrderEntity.Charges charges = charges("200.00", "20.00", "10.00");
        SalesOrderEntity paid = openOrder();
        paid.confirmPaid();
        assertThrows(InvalidOperationException.class,
                () -> paid.replaceLines(ServiceType.DINE_IN, "Budi", null, charges));
        assertEquals(new BigDecimal("115.00"), paid.getTotal());

        SalesOrderEntity cancelled = openOrder();
        cancelled.cancel("Batal");
        assertThrows(InvalidOperationException.class,
                () -> cancelled.replaceLines(ServiceType.DINE_IN, "Budi", null, charges));

        SalesOrderEntity discounted = openOrder();
        discounted.applyDiscount(new BigDecimal("10.00"));
        assertThrows(InvalidOperationException.class,
                () -> discounted.replaceLines(ServiceType.DINE_IN, "Budi", null, charges));
        assertEquals(new BigDecimal("100.00"), discounted.getSubtotal());

        SalesOrderEntity open = openOrder();
        assertThrows(InvalidOperationException.class, () -> open.replaceLines(null, "Budi", null, charges));
        assertEquals(ServiceType.DINE_IN, open.getServiceType());
        assertEquals(new BigDecimal("115.00"), open.getTotal());
    }

    @Test
    void orderNoteIsStoredByPosAndReplaceLinesAndNullClearsIt() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        SalesOrderEntity order = SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0001", "fingerprint", "POS-ORDER-1",
                        ServiceType.DINE_IN, null, "tanpa es"), charges("100.00", "10.00", "5.00"));
        assertEquals("tanpa es", order.getOrderNote());
        assertNull(order.getCustomerLabel());

        order.replaceLines(ServiceType.DINE_IN, "Budi", "extra pedas", charges("100.00", "10.00", "5.00"));
        assertEquals("extra pedas", order.getOrderNote());

        order.replaceLines(ServiceType.DINE_IN, null, null, charges("100.00", "10.00", "5.00"));
        assertNull(order.getOrderNote());
        assertNull(order.getCustomerLabel());
    }

    @Test
    void markRefundedOnAPaidOrderKeepsItCompletedAndSetsRefunded() {
        SalesOrderEntity order = openOrder();
        order.confirmPaid();

        order.markRefunded();

        assertEquals(PaymentStatus.REFUNDED, order.getPaymentStatus());
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @Test
    void markRefundedRejectsOpenCancelledAndAlreadyRefundedOrders() {
        SalesOrderEntity open = openOrder();
        OperationConflictException ex = assertThrows(OperationConflictException.class, open::markRefunded);
        assertEquals("Hanya pesanan lunas yang dapat direfund.", ex.getMessage());
        assertEquals(PaymentStatus.UNPAID, open.getPaymentStatus());

        SalesOrderEntity cancelled = openOrder();
        cancelled.cancel("Batal");
        assertThrows(OperationConflictException.class, cancelled::markRefunded);
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());

        SalesOrderEntity refunded = openOrder();
        refunded.confirmPaid();
        refunded.markRefunded();
        assertThrows(OperationConflictException.class, refunded::markRefunded);
        assertEquals(PaymentStatus.REFUNDED, refunded.getPaymentStatus());
    }
}
