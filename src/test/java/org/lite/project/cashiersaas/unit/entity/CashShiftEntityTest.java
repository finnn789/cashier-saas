package org.lite.project.cashiersaas.unit.entity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    void openRejectsNullNegativeAndMoreThanTwoDecimals() {
        for (BigDecimal invalid : new BigDecimal[]{null, new BigDecimal("-0.01"), new BigDecimal("10.001")}) {
            InvalidOperationException ex = assertThrows(InvalidOperationException.class,
                    () -> CashShiftEntity.open(tenant, outlet, membership, invalid));
            assertEquals("Modal awal tidak valid.", ex.getMessage());
        }
    }

    // Rows: deficit with a padded note closed by the owner; surplus with a blank note; exact count with a null note.
    // Opening cash is also checked at scale two (zero opening cash is allowed).
    @ParameterizedTest
    @CsvSource(value = {
            "100000, 100000.00, true, 150000, 149500.50, -499.50, '  selisih kecil  ', selisih kecil",
            "0, 0.00, false, 10.00, 12.25, 2.25, '   ',",
            "0, 0.00, false, 10.00, 10.00, 0.00, ,"
    })
    void closeStoresExpectedCountedAndSignedDifference(String openingCash, BigDecimal storedOpening, boolean byOwner,
                                                       BigDecimal expected, BigDecimal counted, BigDecimal difference,
                                                       String note, String storedNote) {
        CashShiftEntity shift = open(openingCash);
        StaffUserEntity closer = byOwner ? owner : cashier;

        shift.close(closer, expected, counted, note);

        assertEquals(storedOpening, shift.getOpeningCash());
        assertFalse(shift.isOpen());
        assertEquals(CashShiftEntity.Status.CLOSED, shift.getStatus());
        assertSame(closer, shift.getClosedBy());
        assertNotNull(shift.getClosedAt());
        assertEquals(expected.setScale(2), shift.getExpectedCash());
        assertEquals(counted, shift.getCountedCash());
        assertEquals(difference, shift.getCashDifference(), "difference = counted - expected");
        assertEquals(storedNote, shift.getClosingNote());
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
