package org.lite.project.cashiersaas.unit.entity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CashShiftEntityTest {
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
    private final StaffUserEntity cashier = StaffUserEntity.createCashier("k@example.com", "hash", "Kasir", null);
    private final StaffUserEntity owner = StaffUserEntity.createCashier("o@example.com", "hash", "Owner", null);
    private final TenantMembershipEntity membership = TenantMembershipEntity.grantCashier(cashier, tenant);

    private CashShiftEntity open(String openingCash) {
        return CashShiftEntity.open(tenant, outlet, membership, new BigDecimal(openingCash));
    }

    @Test
    void openStartsAnOpenShiftOfTheMemberWithOpeningCashAtScaleTwo() {
        CashShiftEntity shift = open("100000");

        assertTrue(shift.isOpen());
        assertEquals(CashShiftEntity.Status.OPEN, shift.getStatus());
        assertEquals(new BigDecimal("100000.00"), shift.getOpeningCash());
        assertSame(tenant, shift.getTenant());
        assertSame(outlet, shift.getOutlet());
        assertSame(membership, shift.getMembership());
        assertSame(cashier, shift.getOpenedBy());
        assertNotNull(shift.getOpenedAt());
        assertNull(shift.getClosedAt());
        assertNull(shift.getExpectedCash());
        assertNull(shift.getCashDifference());
        assertEquals(new BigDecimal("0.00"), open("0").getOpeningCash(), "zero opening cash is allowed");
    }

    @Test
    void openRejectsNullNegativeAndMoreThanTwoDecimals() {
        for (BigDecimal invalid : new BigDecimal[]{null, new BigDecimal("-0.01"), new BigDecimal("10.001")}) {
            InvalidOperationException ex = assertThrows(InvalidOperationException.class,
                    () -> CashShiftEntity.open(tenant, outlet, membership, invalid));
            assertEquals("Modal awal tidak valid.", ex.getMessage());
        }
    }

    @Test
    void closeStoresExpectedCountedAndSignedDifference() {
        CashShiftEntity shift = open("100000.00");

        shift.close(owner, new BigDecimal("150000"), new BigDecimal("149500.50"), "  selisih kecil  ");

        assertFalse(shift.isOpen());
        assertEquals(CashShiftEntity.Status.CLOSED, shift.getStatus());
        assertSame(owner, shift.getClosedBy());
        assertNotNull(shift.getClosedAt());
        assertEquals(new BigDecimal("150000.00"), shift.getExpectedCash());
        assertEquals(new BigDecimal("149500.50"), shift.getCountedCash());
        assertEquals(new BigDecimal("-499.50"), shift.getCashDifference(), "difference = counted - expected");
        assertEquals("selisih kecil", shift.getClosingNote());
    }

    @Test
    void closeWithSurplusIsPositiveAndBlankNoteIsStoredAsNull() {
        CashShiftEntity shift = open("0");

        shift.close(cashier, new BigDecimal("10.00"), new BigDecimal("12.25"), "   ");

        assertEquals(new BigDecimal("2.25"), shift.getCashDifference());
        assertNull(shift.getClosingNote());
        CashShiftEntity exact = open("0");
        exact.close(cashier, new BigDecimal("10.00"), new BigDecimal("10.00"), null);
        assertEquals(0, exact.getCashDifference().signum());
    }

    @Test
    void closingTwiceIsAConflictAndKeepsTheFirstResult() {
        CashShiftEntity shift = open("10");
        shift.close(cashier, new BigDecimal("10.00"), new BigDecimal("10.00"), null);

        OperationConflictException ex = assertThrows(OperationConflictException.class,
                () -> shift.close(owner, new BigDecimal("99.00"), new BigDecimal("1.00"), "lagi"));

        assertEquals("Shift sudah ditutup.", ex.getMessage());
        assertSame(cashier, shift.getClosedBy());
        assertEquals(new BigDecimal("10.00"), shift.getCountedCash());
    }

    @Test
    void closeRejectsInvalidCountedCashAndLeavesTheShiftOpen() {
        for (BigDecimal invalid : new BigDecimal[]{null, new BigDecimal("-1.00"), new BigDecimal("1.001")}) {
            CashShiftEntity shift = open("10");
            assertThrows(InvalidOperationException.class,
                    () -> shift.close(cashier, new BigDecimal("10.00"), invalid, null));
            assertTrue(shift.isOpen());
            assertNull(shift.getCountedCash());
        }
    }
}
