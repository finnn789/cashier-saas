package org.lite.project.cashiersaas.unit.entity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.entity.fnb.DiningTableEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class SalesOrderEntityTest {
    @Test
    void publicQrFactoryCreatesNewUnpaidOrderWithImmutableCheckoutSnapshots() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        DiningTableEntity table = DiningTableEntity.create(tenant, outlet, "T-01");
        Instant expiry = Instant.parse("2030-01-01T00:00:00Z");

        SalesOrderEntity order = SalesOrderEntity.publicQr(tenant, outlet,
                new SalesOrderEntity.PublicQrDetails(table, "checkout-key-0001", "fingerprint",
                        "QR-ORDER-1", "digest", expiry, "Customer"),
                new SalesOrderEntity.Charges(new BigDecimal("100.00"),
                        new BigDecimal("11.00"), new BigDecimal("5.00")));

        assertEquals(tenant, order.getTenant());
        assertEquals(outlet, order.getOutlet());
        assertEquals(table, order.getDiningTable());
        assertEquals(OrderSource.QR, order.getSource());
        assertEquals(ServiceType.DINE_IN, order.getServiceType());
        assertEquals(OrderStatus.NEW, order.getStatus());
        assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus());
        assertEquals(new BigDecimal("100.00"), order.getSubtotal());
        assertEquals(new BigDecimal("11.00"), order.getTaxAmount());
        assertEquals(new BigDecimal("5.00"), order.getServiceChargeAmount());
        assertEquals(new BigDecimal("116.00"), order.getTotal());
        assertEquals("fingerprint", order.getRequestFingerprint());
        assertEquals("digest", order.getPublicStatusTokenDigest());
        assertEquals(expiry, order.getPublicStatusTokenExpiresAt());
    }

    @Test
    void outletOrderSupportsTakeawayAndRejectsNullServiceType() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        SalesOrderEntity order = SalesOrderEntity.publicQr(tenant, outlet,
                new SalesOrderEntity.PublicQrDetails(null, "checkout-key-0001", "fingerprint",
                        "QR-ORDER-1", "digest", Instant.parse("2030-01-01T00:00:00Z"), "Customer"),
                new SalesOrderEntity.Charges(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO));

        order.selectPublicServiceType(ServiceType.TAKEAWAY);

        assertEquals(ServiceType.TAKEAWAY, order.getServiceType());
        assertThrows(InvalidOperationException.class, () -> order.selectPublicServiceType(null));
    }

    @Test
    void tableTakeawayIsRejectedWithoutMutatingExistingServiceType() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        DiningTableEntity table = DiningTableEntity.create(tenant, outlet, "T-01");
        SalesOrderEntity order = SalesOrderEntity.publicQr(tenant, outlet,
                new SalesOrderEntity.PublicQrDetails(table, "checkout-key-0001", "fingerprint",
                        "QR-ORDER-1", "digest", Instant.parse("2030-01-01T00:00:00Z"), "Customer"),
                new SalesOrderEntity.Charges(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThrows(InvalidOperationException.class, () -> order.selectPublicServiceType(ServiceType.TAKEAWAY));

        assertEquals(ServiceType.DINE_IN, order.getServiceType());
        assertNull(ReflectionTestUtils.getField(order, "cancellationReason"));
    }
}
