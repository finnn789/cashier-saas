package org.lite.project.cashiersaas.unit.entity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
    void createStartsAtZeroAndKeepsScopeAndItem() {
        InventoryItemEntity item = item("10");

        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item);

        assertEquals(0, BigDecimal.ZERO.compareTo(balance.getQuantityOnHand()));
        assertEquals(tenantId, balance.getTenantId());
        assertEquals(outletId, balance.getOutletId());
        assertSame(item, balance.getInventoryItem());
        assertEquals(InventoryBalanceEntity.Status.OUT, balance.status());
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

    @Test
    void negativeQuantityIsNegativeEvenJustBelowZero() {
        assertEquals(InventoryBalanceEntity.Status.NEGATIVE, balanceOf("10", "-0.001").status());
        assertEquals(InventoryBalanceEntity.Status.NEGATIVE, balanceOf("0", "-26").status());
    }

    @Test
    void exactlyZeroIsOutRegardlessOfReorderLevelAndScale() {
        assertEquals(InventoryBalanceEntity.Status.OUT, balanceOf("10", "0").status());
        assertEquals(InventoryBalanceEntity.Status.OUT, balanceOf("10", "0.000").status());
        assertEquals(InventoryBalanceEntity.Status.OUT, balanceOf("0", "0").status());
    }

    @Test
    void quantityEqualToReorderLevelIsLowIgnoringScaleDifferences() {
        assertEquals(InventoryBalanceEntity.Status.LOW, balanceOf("10", "10").status());
        assertEquals(InventoryBalanceEntity.Status.LOW, balanceOf("10", "10.000").status());
        assertEquals(InventoryBalanceEntity.Status.LOW, balanceOf("10.000", "10").status());
        assertEquals(InventoryBalanceEntity.Status.LOW, balanceOf("10", "0.001").status());
    }

    @Test
    void quantityJustAboveReorderLevelIsOk() {
        assertEquals(InventoryBalanceEntity.Status.OK, balanceOf("10", "10.001").status());
        assertEquals(InventoryBalanceEntity.Status.OK, balanceOf("0", "0.001").status());
    }

    @Test
    void statusFollowsQuantityAfterEachApply() {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item("100"));
        assertEquals(InventoryBalanceEntity.Status.OUT, balance.status());

        balance.apply(new BigDecimal("1000"));
        assertEquals(InventoryBalanceEntity.Status.OK, balance.status());

        balance.apply(new BigDecimal("-900"));
        assertEquals(InventoryBalanceEntity.Status.LOW, balance.status());

        balance.apply(new BigDecimal("-100"));
        assertEquals(InventoryBalanceEntity.Status.OUT, balance.status());

        balance.apply(new BigDecimal("-26"));
        assertEquals(InventoryBalanceEntity.Status.NEGATIVE, balance.status());
    }

    // ---- moving average (receive)

    private InventoryBalanceEntity emptyBalance() {
        return InventoryBalanceEntity.create(tenantId, outletId, item("10"));
    }

    @Test
    void newBalanceHasNoAverageCost() {
        assertNull(emptyBalance().getAverageUnitCost());
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

    @Test
    void averageIsRoundedHalfUpToFourDecimals() {
        InventoryBalanceEntity balance = emptyBalance();
        balance.receive(new BigDecimal("1"), new BigDecimal("1"));

        balance.receive(new BigDecimal("2"), new BigDecimal("2"));
        // (1*1 + 2*2) / 3 = 1.666666... -> 1.6667
        assertEquals(new BigDecimal("1.6667"), balance.getAverageUnitCost());

        InventoryBalanceEntity half = emptyBalance();
        half.receive(new BigDecimal("1"), new BigDecimal("0.0001"));
        half.receive(new BigDecimal("1"), new BigDecimal("0.0002"));
        // (0.0001 + 0.0002) / 2 = 0.00015 -> 0.0002 (HALF_UP)
        assertEquals(new BigDecimal("0.0002"), half.getAverageUnitCost());
    }

    @Test
    void priceWithMoreThanFourDecimalsIsRoundedOnFirstReceive() {
        InventoryBalanceEntity balance = emptyBalance();

        balance.receive(new BigDecimal("3"), new BigDecimal("1.23455"));

        assertEquals(new BigDecimal("1.2346"), balance.getAverageUnitCost());
    }

    @Test
    void zeroStockResetsAverageToTheNewPrice() {
        InventoryBalanceEntity balance = emptyBalance();
        balance.receive(new BigDecimal("10"), new BigDecimal("5"));
        balance.apply(new BigDecimal("-10"));

        balance.receive(new BigDecimal("4"), new BigDecimal("7"));

        assertEquals(new BigDecimal("7.0000"), balance.getAverageUnitCost());
        assertEquals(0, new BigDecimal("4").compareTo(balance.getQuantityOnHand()));
    }

    @Test
    void negativeStockResetsAverageToTheNewPriceInsteadOfWeighting() {
        InventoryBalanceEntity balance = emptyBalance();
        balance.receive(new BigDecimal("10"), new BigDecimal("5"));
        balance.apply(new BigDecimal("-15")); // on hand -5

        balance.receive(new BigDecimal("20"), new BigDecimal("8"));

        assertEquals(new BigDecimal("8.0000"), balance.getAverageUnitCost());
        assertEquals(0, new BigDecimal("15").compareTo(balance.getQuantityOnHand()));
    }

    @Test
    void positiveStockWithoutAverageTakesThePrice() {
        InventoryBalanceEntity balance = balanceOf("10", "20"); // stock without any cost yet

        balance.receive(new BigDecimal("5"), new BigDecimal("3"));

        assertEquals(new BigDecimal("3.0000"), balance.getAverageUnitCost());
        assertEquals(0, new BigDecimal("25").compareTo(balance.getQuantityOnHand()));
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
