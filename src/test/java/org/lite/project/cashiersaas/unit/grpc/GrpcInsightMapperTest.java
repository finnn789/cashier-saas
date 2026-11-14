package org.lite.project.cashiersaas.unit.grpc;

import com.cashier.grpc.v1.CashierAnomaly;
import com.cashier.grpc.v1.OutletAnomalies;
import com.cashier.grpc.v1.OutletRestock;
import com.cashier.grpc.v1.OutletSalesSummary;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.dto.response.FnbCashierAnomaly;
import org.lite.project.cashiersaas.dto.response.FnbOwnerSalesInsight;
import org.lite.project.cashiersaas.dto.response.FnbRestockSuggestion;
import org.lite.project.cashiersaas.dto.response.FnbSalesPaymentBreakdownResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesProductResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesReportResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesSummaryResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.grpc.mapper.GrpcInsightMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class GrpcInsightMapperTest {
    private final GrpcInsightMapper mapper = new GrpcInsightMapper();
    private final UUID outletId = UUID.randomUUID();
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = outlet("Kedai Mbo");
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet,
            TenantMembershipEntity.grantOwner(StaffUserEntity.createCashier("o@example.com", "hash", "Owner", null), tenant));

    private OutletEntity outlet(String name) {
        OutletEntity value = OutletEntity.open(tenant, name, "Asia/Jakarta", null, null);
        ReflectionTestUtils.setField(value, "id", outletId);
        return value;
    }

    private static FnbSalesSummaryResponse summary() {
        FnbSalesSummaryResponse summary = new FnbSalesSummaryResponse();
        summary.setTransactionCount(12);
        summary.setSubtotal(new BigDecimal("500000.00"));
        summary.setDiscountAmount(new BigDecimal("25000.00"));
        summary.setNetSales(new BigDecimal("475000.00"));
        summary.setTotal(new BigDecimal("522500.00"));
        summary.setTaxAmount(new BigDecimal("47500.00"));
        summary.setServiceChargeAmount(new BigDecimal("0.00"));
        summary.setCostOfGoodsSold(new BigDecimal("1E+5"));
        summary.setGrossProfit(new BigDecimal("375000.00"));
        summary.setItemsWithoutCost(2);
        summary.setRefundCount(1);
        summary.setRefundAmount(new BigDecimal("15000.00"));
        summary.setCancellationCount(3);
        return summary;
    }

    private static FnbOwnerSalesInsight insight(FnbSalesReportResponse report) {
        FnbOwnerSalesInsight insight = new FnbOwnerSalesInsight();
        insight.setReport(report);
        insight.setDiscountedOrderCount(4);
        insight.setExpensesTotal(new BigDecimal("30000.00"));
        insight.setCashDifferenceTotal(new BigDecimal("-5000.00"));
        insight.setClosedShiftCount(2);
        return insight;
    }

    @Test
    void salesSummaryMapsEveryFieldWithMoneyAsPlainStringsAndDatesAsIso() {
        FnbSalesReportResponse report = new FnbSalesReportResponse();
        report.setStartDate(LocalDate.of(2026, 11, 1));
        report.setEndDate(LocalDate.of(2026, 11, 7));
        report.setTimeZone("Asia/Jakarta");
        report.setSummary(summary());
        FnbSalesPaymentBreakdownResponse cash = new FnbSalesPaymentBreakdownResponse();
        cash.setMethod(PaymentMethod.CASH);
        cash.setMethodLabel("Tunai");
        cash.setTransactionCount(7);
        cash.setNetSales(new BigDecimal("300000.00"));
        report.setPaymentBreakdown(List.of(cash));
        FnbSalesProductResponse product = new FnbSalesProductResponse();
        product.setProductName("Kopi Susu");
        product.setQuantity(30);
        product.setGrossSales(new BigDecimal("450000.00"));
        product.setCostOfGoodsSold(new BigDecimal("120000.00"));
        product.setGrossProfit(new BigDecimal("330000.00"));
        product.setItemsWithoutCost(1);
        report.setTopProducts(List.of(product));

        OutletSalesSummary result = mapper.toSalesSummary(context, insight(report));

        assertEquals(outletId.toString(), result.getOutletId());
        assertEquals("Kedai Mbo", result.getOutletName());
        assertEquals("Asia/Jakarta", result.getTimeZone());
        assertEquals("2026-11-01", result.getStartDate());
        assertEquals("2026-11-07", result.getEndDate());
        assertEquals(12, result.getTransactionCount());
        assertEquals("500000.00", result.getGrossSales());
        assertEquals("25000.00", result.getDiscountAmount());
        assertEquals("475000.00", result.getNetSales());
        assertEquals("522500.00", result.getTotal());
        assertEquals("47500.00", result.getTaxAmount());
        assertEquals("0.00", result.getServiceChargeAmount());
        assertEquals("100000", result.getCostOfGoodsSold()); // 1E+5 is rendered without an exponent
        assertEquals("375000.00", result.getGrossProfit());
        assertEquals(2, result.getItemsWithoutCost());
        assertEquals(1, result.getRefundCount());
        assertEquals("15000.00", result.getRefundAmount());
        assertEquals(3, result.getCancellationCount());
        assertEquals(4, result.getDiscountedOrderCount());
        assertEquals("30000.00", result.getExpensesTotal());
        assertEquals("-5000.00", result.getCashDifferenceTotal());
        assertEquals(2, result.getClosedShiftCount());
        assertEquals(1, result.getLabelsCount());
        assertEquals("CASH", result.getLabels(0).getMethod());
        assertEquals("Tunai", result.getLabels(0).getLabel());
        assertEquals(7, result.getLabels(0).getCount());
        assertEquals("300000.00", result.getLabels(0).getNetSales());
        assertEquals(1, result.getTopProductsCount());
        assertEquals("Kopi Susu", result.getTopProducts(0).getName());
        assertEquals(30, result.getTopProducts(0).getQuantity());
        assertEquals("450000.00", result.getTopProducts(0).getGrossSales());
        assertEquals("120000.00", result.getTopProducts(0).getCostOfGoodsSold());
        assertEquals("330000.00", result.getTopProducts(0).getGrossProfit());
        assertEquals(1, result.getTopProducts(0).getItemsWithoutCost());
    }

    @Test
    void salesSummaryTurnsMissingValuesIntoZeroOrEmptyAndEmptyListsIntoNoRows() {
        FnbSalesReportResponse report = new FnbSalesReportResponse();
        report.setSummary(new FnbSalesSummaryResponse());
        FnbOwnerSalesInsight insight = new FnbOwnerSalesInsight();
        insight.setReport(report);

        OutletSalesSummary result = mapper.toSalesSummary(context, insight);

        assertEquals("", result.getStartDate());
        assertEquals("", result.getTimeZone());
        assertEquals("0", result.getNetSales());
        assertEquals("0", result.getExpensesTotal());
        assertEquals("0", result.getCashDifferenceTotal());
        assertEquals(0, result.getLabelsCount());
        assertEquals(0, result.getTopProductsCount());
    }

    @Test
    void restockMapsQuantitiesAsPlainStringsAndStatusAsName() {
        FnbRestockSuggestion item = new FnbRestockSuggestion();
        UUID itemId = UUID.randomUUID();
        item.setInventoryItemId(itemId);
        item.setName("Susu");
        item.setUnit("liter");
        item.setQuantityOnHand(new BigDecimal("5.000"));
        item.setReorderLevel(new BigDecimal("10.000"));
        item.setAverageDailyUsage(new BigDecimal("2.000"));
        item.setSuggestedQuantity(new BigDecimal("19.000"));
        item.setStockStatus(InventoryBalanceEntity.Status.LOW);

        OutletRestock result = mapper.toRestock(context, List.of(item));

        assertEquals(outletId.toString(), result.getOutletId());
        assertEquals("Kedai Mbo", result.getOutletName());
        assertEquals(1, result.getItemsCount());
        assertEquals(itemId.toString(), result.getItems(0).getInventoryItemId());
        assertEquals("Susu", result.getItems(0).getName());
        assertEquals("liter", result.getItems(0).getUnit());
        assertEquals("5.000", result.getItems(0).getQuantityOnHand());
        assertEquals("10.000", result.getItems(0).getReorderLevel());
        assertEquals("2.000", result.getItems(0).getAverageDailyUsage());
        assertEquals("19.000", result.getItems(0).getSuggestedQuantity());
        assertEquals("LOW", result.getItems(0).getStockStatus());
    }

    @Test
    void restockWithNoItemsKeepsTheOutletHeaderOnly() {
        OutletRestock result = mapper.toRestock(context, List.of());

        assertEquals(outletId.toString(), result.getOutletId());
        assertEquals(0, result.getItemsCount());
    }

    @Test
    void anomaliesMapKindValueAndBaselineWithTheDate() {
        FnbCashierAnomaly anomaly = new FnbCashierAnomaly();
        UUID staffId = UUID.randomUUID();
        anomaly.setStaffUserId(staffId);
        anomaly.setFullName("Kasir Satu");
        anomaly.setKind("CASH_DIFFERENCE");
        anomaly.setValue(new BigDecimal("25000.00"));
        anomaly.setBaselineDailyAverage(new BigDecimal("1000.00"));

        OutletAnomalies result = mapper.toAnomalies(context, LocalDate.of(2026, 11, 14), List.of(anomaly));

        assertEquals(outletId.toString(), result.getOutletId());
        assertEquals("2026-11-14", result.getDate());
        assertEquals(1, result.getAnomaliesCount());
        CashierAnomaly mapped = result.getAnomalies(0);
        assertEquals(staffId.toString(), mapped.getStaffUserId());
        assertEquals("Kasir Satu", mapped.getFullName());
        assertEquals("CASH_DIFFERENCE", mapped.getKind());
        assertEquals("25000.00", mapped.getValue());
        assertEquals("1000.00", mapped.getBaselineDailyAverage());
    }

    @Test
    void anomalyWithMissingFieldsMapsToEmptyStringsAndZero() {
        OutletAnomalies result = mapper.toAnomalies(context, LocalDate.of(2026, 11, 14), List.of(new FnbCashierAnomaly()));

        CashierAnomaly mapped = result.getAnomalies(0);
        assertEquals("", mapped.getStaffUserId());
        assertEquals("", mapped.getFullName());
        assertEquals("", mapped.getKind());
        assertEquals("0", mapped.getValue());
        assertEquals("0", mapped.getBaselineDailyAverage());
        assertTrue(result.getAnomaliesCount() == 1);
    }
}
