package org.lite.project.cashiersaas.unit.dto;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.dto.FnbSalesReportMapper;
import org.lite.project.cashiersaas.dto.response.FnbSalesProductResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesShiftResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesSummaryResponse;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.repository.FnbRefundRepository.RefundTotals;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.CostRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.ProductRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.ShiftRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.SummaryRow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** M6 (TASK-079): COGS, gross profit, items without cost, refunds and the new report breakdowns. */
@Tag("unit")
class FnbSalesReportMapperTest {
    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static SummaryRow summaryRow(String subtotal, String netSales, long count) {
        // subtotal, netSales, total, amountCollected, count, discount, tax, service
        return new SummaryRow(bd(subtotal), bd(netSales), bd(netSales), bd(netSales), count,
                bd(subtotal).subtract(bd(netSales)), bd("0"), bd("0"));
    }

    @ParameterizedTest
    @CsvSource({
            "1000.00, 900.00, 4, 1, 400.00, 0, 500.00", // discount 100 -> net 900; COGS 400 -> gross profit 500 (D2)
            "500.00, 500.00, 2, 0, 0, 3, 500.00",       // no cost known: profit equals net sales
            "100.00, 100.00, 1, 0, 130.50, 0, -30.50"   // cost above net sales: profit can be negative
    })
    void grossProfitIsNetSalesAfterDiscountMinusCogs(String subtotal, String netSales, long count, long cancellations,
                                                     String cost, long itemsWithoutCost, String grossProfit) {
        FnbSalesSummaryResponse value = FnbSalesReportMapper.summary(summaryRow(subtotal, netSales, count), cancellations,
                new CostRow(bd(cost), itemsWithoutCost), new RefundTotals(0L, bd("0")));

        assertEquals(bd(cost), value.getCostOfGoodsSold());
        assertEquals(bd(grossProfit), value.getGrossProfit());
        assertEquals(bd(netSales), value.getNetSales());
        assertEquals(itemsWithoutCost, value.getItemsWithoutCost());
    }

    @Test
    void summaryCarriesItemsWithoutCostRefundsAndKeepsExistingFields() {
        FnbSalesSummaryResponse value = FnbSalesReportMapper.summary(summaryRow("1000.00", "900.00", 4), 2,
                new CostRow(bd("400.00"), 5L), new RefundTotals(2L, bd("75000.00")));

        assertEquals(5L, value.getItemsWithoutCost());
        assertEquals(2L, value.getRefundCount());
        assertEquals(bd("75000.00"), value.getRefundAmount());
        assertEquals(4L, value.getTransactionCount());
        assertEquals(2L, value.getCancellationCount());
        assertEquals(bd("225.00"), value.getAverageTransaction());
    }

    @Test
    void emptyPeriodGivesZeroAverageAndZeroProfit() {
        FnbSalesSummaryResponse value = FnbSalesReportMapper.summary(summaryRow("0", "0", 0), 0,
                new CostRow(bd("0"), 0L), new RefundTotals(0L, bd("0")));

        assertEquals(0, BigDecimal.ZERO.compareTo(value.getAverageTransaction()));
        assertEquals(0, BigDecimal.ZERO.compareTo(value.getGrossProfit()));
        assertEquals(0L, value.getRefundCount());
    }

    @Test
    void perMenuGrossProfitIsLineTotalsMinusCogsBeforeOrderDiscount() {
        UUID espresso = UUID.randomUUID();
        UUID latte = UUID.randomUUID();
        List<ProductRow> rows = List.of(
                new ProductRow(espresso, "Espresso", 10L, bd("250000.00"), bd("100000.00"), 0L),
                new ProductRow(latte, "Latte", 4L, bd("120000.00"), bd("0"), 4L));

        List<FnbSalesProductResponse> values = FnbSalesReportMapper.products(rows);

        FnbSalesProductResponse first = values.get(0);
        assertEquals(espresso, first.getProductId());
        assertEquals("Espresso", first.getProductName());
        assertEquals(10L, first.getQuantity());
        assertEquals(bd("250000.00"), first.getGrossSales());
        assertEquals(bd("100000.00"), first.getCostOfGoodsSold());
        assertEquals(bd("150000.00"), first.getGrossProfit());
        assertEquals(0L, first.getItemsWithoutCost());
        FnbSalesProductResponse second = values.get(1);
        // no cost known: profit is the full gross sales and every line is flagged
        assertEquals(0, bd("120000.00").compareTo(second.getGrossProfit()));
        assertEquals(4L, second.getItemsWithoutCost());
    }

    @Test
    void shiftRowsMapStatusNameAndKeepTheNoShiftRowNull() {
        UUID shiftId = UUID.randomUUID();
        Instant opened = Instant.parse("2026-11-14T01:00:00Z");
        Instant closed = Instant.parse("2026-11-14T09:00:00Z");

        List<FnbSalesShiftResponse> values = FnbSalesReportMapper.shifts(List.of(
                new ShiftRow(shiftId, "Sari", opened, closed, CashShiftEntity.Status.CLOSED, 5L, bd("500.00")),
                new ShiftRow(null, null, null, null, null, 1L, bd("20.00"))));

        FnbSalesShiftResponse shift = values.get(0);
        assertEquals(shiftId, shift.getShiftId());
        assertEquals("Sari", shift.getCashierName());
        assertEquals(opened, shift.getOpenedAt());
        assertEquals(closed, shift.getClosedAt());
        assertEquals("CLOSED", shift.getStatus());
        assertEquals(5L, shift.getTransactionCount());
        FnbSalesShiftResponse noShift = values.get(1);
        assertNull(noShift.getShiftId());
        assertNull(noShift.getStatus());
        assertNull(noShift.getOpenedAt());
        assertEquals(1L, noShift.getTransactionCount());
        assertEquals(bd("20.00"), noShift.getNetSales());
    }
}
