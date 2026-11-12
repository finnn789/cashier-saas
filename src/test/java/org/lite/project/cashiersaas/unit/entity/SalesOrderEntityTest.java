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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class SalesOrderEntityTest {
    @Test
    void posFactoryCreatesNewUnpaidOrderWithImmutableCheckoutSnapshots() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);

        SalesOrderEntity order = SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0001", "fingerprint", "POS-ORDER-1",
                        ServiceType.DINE_IN, "Customer"),
                new SalesOrderEntity.Charges(new BigDecimal("100.00"),
                        new BigDecimal("11.00"), new BigDecimal("5.00")));

        assertEquals(tenant, order.getTenant());
        assertEquals(outlet, order.getOutlet());
        assertEquals(OrderSource.POS, order.getSource());
        assertEquals(ServiceType.DINE_IN, order.getServiceType());
        assertEquals(OrderStatus.NEW, order.getStatus());
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
                        ServiceType.TAKEAWAY, "Customer"), charges);

        assertEquals(ServiceType.TAKEAWAY, order.getServiceType());
        assertThrows(InvalidOperationException.class, () -> SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0002", "fingerprint", "POS-ORDER-2",
                        null, "Customer"), charges));
    }
}
