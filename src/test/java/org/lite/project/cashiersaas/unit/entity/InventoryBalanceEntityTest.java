package org.lite.project.cashiersaas.unit.entity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class InventoryBalanceEntityTest {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);

    private InventoryItemEntity item(String reorderLevel) {
        return InventoryItemEntity.create(tenant, outlet, null, "Susu", "ml", new BigDecimal(reorderLevel));
    }

    private InventoryBalanceEntity balanceOf(String reorderLevel, String quantity) {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item(reorderLevel));
        balance.apply(new BigDecimal(quantity));
        return balance;
    }

    @Test
    void applyAccumulatesPositiveAndNegativeDeltasKeepingScale() {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item("10"));

        balance.apply(new BigDecimal("1.500"));
        balance.apply(new BigDecimal("2.250"));
        assertEquals(new BigDecimal("3.750"), balance.getQuantityOnHand());

        balance.apply(new BigDecimal("-5.000"));
        assertEquals(new BigDecimal("-1.250"), balance.getQuantityOnHand());
    }

    @ParameterizedTest
    @CsvSource({
            "10, -0.001, NEGATIVE",
            "0, -26, NEGATIVE",
            "10, 0, OUT",
            "10, 0.000, OUT",
            "0, 0, OUT",
            "10, 10, LOW",
            "10, 10.000, LOW",
            "10.000, 10, LOW",
            "10, 0.001, LOW",
            "10, 10.001, OK",
            "0, 0.001, OK"
    })
    void statusComparesQuantityToReorderLevelIgnoringScaleAndNegativeIsBelowZero(
            String reorderLevel, String quantity, InventoryBalanceEntity.Status expected) {
        assertEquals(expected, balanceOf(reorderLevel, quantity).status());
    }

    // ---- moving average (receive)

    private InventoryBalanceEntity emptyBalance() {
        return InventoryBalanceEntity.create(tenantId, outletId, item("10"));
    }

    @Test
    void firstPricedReceiveSetsAverageToThePriceAndAddsStock() {
        InventoryBalanceEntity balance = emptyBalance();

        balance.receive(new BigDecimal("10"), new BigDecimal("5.5"));

        assertEquals(new BigDecimal("5.5000"), balance.getAverageUnitCost());
        assertEquals(0, new BigDecimal("10").compareTo(balance.getQuantityOnHand()));
    }

    @Test
    void secondPricedReceiveBlendsByQuantityWeight() {
        InventoryBalanceEntity balance = emptyBalance();
        balance.receive(new BigDecimal("10"), new BigDecimal("5"));

        balance.receive(new BigDecimal("30"), new BigDecimal("9"));

        // (10*5 + 30*9) / 40 = 8
        assertEquals(new BigDecimal("8.0000"), balance.getAverageUnitCost());
        assertEquals(0, new BigDecimal("40").compareTo(balance.getQuantityOnHand()));
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1, 2, 2, 1.6667", // (1*1 + 2*2) / 3 = 1.666666... -> 1.6667
            "1, 0.0001, 1, 0.0002, 0.0002", // (0.0001 + 0.0002) / 2 = 0.00015 -> 0.0002 (HALF_UP)
            "3, 1.23455, , , 1.2346" // more than four decimals on the first receive only
    })
    void averageIsRoundedHalfUpToFourDecimals(BigDecimal firstQuantity, BigDecimal firstPrice,
                                              BigDecimal secondQuantity, BigDecimal secondPrice, BigDecimal expected) {
        InventoryBalanceEntity balance = emptyBalance();
        balance.receive(firstQuantity, firstPrice);
        if (secondQuantity != null) {
            balance.receive(secondQuantity, secondPrice);
        }

        assertEquals(expected, balance.getAverageUnitCost());
    }

    // Rows: zero stock, negative stock (-5 on hand) and positive stock without any cost yet; the average resets to the new price.
    @ParameterizedTest
    @CsvSource({
            "10, 5, -10, 4, 7, 7.0000, 4",
            "10, 5, -15, 20, 8, 8.0000, 15",
            ", , 20, 5, 3, 3.0000, 25"
    })
    void averageResetsToTheNewPriceWhenStockIsZeroNegativeOrUncosted(
            BigDecimal initialQuantity, BigDecimal initialPrice, BigDecimal delta, BigDecimal quantity,
            BigDecimal price, BigDecimal expectedAverage, BigDecimal expectedOnHand) {
        InventoryBalanceEntity balance = emptyBalance();
        if (initialPrice != null) {
            balance.receive(initialQuantity, initialPrice);
        }
        balance.apply(delta);

        balance.receive(quantity, price);

        assertEquals(expectedAverage, balance.getAverageUnitCost());
        assertEquals(0, expectedOnHand.compareTo(balance.getQuantityOnHand()));
    }

    @Test
    void receiveWithoutPriceKeepsAverageAndStillAddsStock() {
        InventoryBalanceEntity balance = emptyBalance();
        balance.receive(new BigDecimal("10"), new BigDecimal("5"));

        balance.receive(new BigDecimal("10"), null);

        assertEquals(new BigDecimal("5.0000"), balance.getAverageUnitCost());
        assertEquals(0, new BigDecimal("20").compareTo(balance.getQuantityOnHand()));
        InventoryBalanceEntity unpriced = emptyBalance();
        unpriced.receive(new BigDecimal("3"), null);
        assertNull(unpriced.getAverageUnitCost());
    }

    @Test
    void applyNeverChangesTheAverage() {
        InventoryBalanceEntity balance = emptyBalance();
        balance.receive(new BigDecimal("10"), new BigDecimal("5"));

        balance.apply(new BigDecimal("-4"));
        balance.apply(new BigDecimal("100"));

        assertEquals(new BigDecimal("5.0000"), balance.getAverageUnitCost());
    }
}
