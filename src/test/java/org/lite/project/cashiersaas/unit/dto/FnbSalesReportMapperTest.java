package org.lite.project.cashiersaas.unit.dto;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.dto.FnbSalesReportMapper;
import org.lite.project.cashiersaas.dto.response.FnbSalesCashierResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesHourlyResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesProductResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesShiftResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesSummaryResponse;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.repository.FnbRefundRepository.RefundTotals;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.CashierRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.CostRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.HourlyRow;
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

    @Test
    void totalGrossProfitIsNetSalesAfterDiscountMinusCogs() {
        // subtotal 1000, discount 100 -> net 900; COGS 400 -> gross profit 500 (D2)
        FnbSalesSummaryResponse value = FnbSalesReportMapper.summary(summaryRow("1000.00", "900.00", 4), 1,
                new CostRow(bd("400.00"), 0L), new RefundTotals(0L, bd("0")));

        assertEquals(bd("400.00"), value.getCostOfGoodsSold());
        assertEquals(bd("500.00"), value.getGrossProfit());
        assertEquals(bd("900.00"), value.getNetSales());
    }

    @Test
    void grossProfitEqualsNetSalesWhenNoCostIsKnownAndCanBeNegative() {
        FnbSalesSummaryResponse unknown = FnbSalesReportMapper.summary(summaryRow("500.00", "500.00", 2), 0,
                new CostRow(bd("0"), 3L), new RefundTotals(0L, bd("0")));
        assertEquals(0, bd("500.00").compareTo(unknown.getGrossProfit()));
        assertEquals(3L, unknown.getItemsWithoutCost());

        FnbSalesSummaryResponse loss = FnbSalesReportMapper.summary(summaryRow("100.00", "100.00", 1), 0,
                new CostRow(bd("130.50"), 0L), new RefundTotals(0L, bd("0")));
        assertEquals(bd("-30.50"), loss.getGrossProfit());
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
    void hourlyRowsKeepOrderAndValues() {
        List<FnbSalesHourlyResponse> values = FnbSalesReportMapper.hourly(List.of(
                new HourlyRow(8, 3L, bd("45000.00")), new HourlyRow(23, 1L, bd("12000.00"))));

        assertEquals(2, values.size());
        assertEquals(8, values.get(0).getHour());
        assertEquals(3L, values.get(0).getTransactionCount());
        assertEquals(bd("45000.00"), values.get(0).getNetSales());
        assertEquals(23, values.get(1).getHour());
    }

    @Test
    void cashierRowsKeepOrderAndValues() {
        UUID top = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        List<FnbSalesCashierResponse> values = FnbSalesReportMapper.cashiers(List.of(
                new CashierRow(top, "Sari", 7L, bd("900.00"), bd("1000.00")),
                new CashierRow(other, "Budi", 2L, bd("100.00"), bd("100.00"))));

        assertEquals(top, values.get(0).getStaffUserId());
        assertEquals("Sari", values.get(0).getFullName());
        assertEquals(7L, values.get(0).getTransactionCount());
        assertEquals(bd("900.00"), values.get(0).getNetSales());
        assertEquals(bd("1000.00"), values.get(0).getAmountCollected());
        assertEquals("Budi", values.get(1).getFullName());
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
