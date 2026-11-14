package org.lite.project.cashiersaas.grpc.mapper;

import com.cashier.grpc.v1.CashierAnomaly;
import com.cashier.grpc.v1.LabelTotal;
import com.cashier.grpc.v1.OutletAnomalies;
import com.cashier.grpc.v1.OutletRestock;
import com.cashier.grpc.v1.OutletSalesSummary;
import com.cashier.grpc.v1.ProductTotal;
import com.cashier.grpc.v1.RestockSuggestion;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.dto.response.FnbCashierAnomaly;
import org.lite.project.cashiersaas.dto.response.FnbOwnerSalesInsight;
import org.lite.project.cashiersaas.dto.response.FnbRestockSuggestion;
import org.lite.project.cashiersaas.dto.response.FnbSalesPaymentBreakdownResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesProductResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesReportResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesSummaryResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Maps owner-insight read models to gRPC messages: money as plain decimal strings, dates as ISO yyyy-MM-dd. */
@Component
public class GrpcInsightMapper {

    public OutletSalesSummary toSalesSummary(MerchantAccessContext context, FnbOwnerSalesInsight insight) {
        FnbSalesReportResponse report = insight.getReport();
        FnbSalesSummaryResponse summary = report.getSummary();
        OutletSalesSummary.Builder builder = OutletSalesSummary.newBuilder()
                .setOutletId(context.getOutlet().getId().toString())
                .setOutletName(text(context.getOutlet().getName()))
                .setTimeZone(text(report.getTimeZone()))
                .setStartDate(date(report.getStartDate()))
                .setEndDate(date(report.getEndDate()))
                .setTransactionCount(summary.getTransactionCount())
                .setGrossSales(money(summary.getSubtotal()))
                .setDiscountAmount(money(summary.getDiscountAmount()))
                .setNetSales(money(summary.getNetSales()))
                .setTotal(money(summary.getTotal()))
                .setTaxAmount(money(summary.getTaxAmount()))
                .setServiceChargeAmount(money(summary.getServiceChargeAmount()))
                .setCostOfGoodsSold(money(summary.getCostOfGoodsSold()))
                .setGrossProfit(money(summary.getGrossProfit()))
                .setItemsWithoutCost(summary.getItemsWithoutCost())
                .setRefundCount(summary.getRefundCount())
                .setRefundAmount(money(summary.getRefundAmount()))
                .setCancellationCount(summary.getCancellationCount())
                .setDiscountedOrderCount(insight.getDiscountedOrderCount())
                .setExpensesTotal(money(insight.getExpensesTotal()))
                .setCashDifferenceTotal(money(insight.getCashDifferenceTotal()))
                .setClosedShiftCount(insight.getClosedShiftCount());
        if (report.getPaymentBreakdown() != null) {
            for (FnbSalesPaymentBreakdownResponse row : report.getPaymentBreakdown()) {
                builder.addLabels(LabelTotal.newBuilder()
                        .setMethod(row.getMethod() != null ? row.getMethod().name() : "")
                        .setLabel(text(row.getMethodLabel()))
                        .setCount(row.getTransactionCount())
                        .setNetSales(money(row.getNetSales())));
            }
        }
        if (report.getTopProducts() != null) {
            for (FnbSalesProductResponse row : report.getTopProducts()) {
                builder.addTopProducts(ProductTotal.newBuilder()
                        .setName(text(row.getProductName()))
                        .setQuantity(row.getQuantity())
                        .setGrossSales(money(row.getGrossSales()))
                        .setCostOfGoodsSold(money(row.getCostOfGoodsSold()))
                        .setGrossProfit(money(row.getGrossProfit()))
                        .setItemsWithoutCost(row.getItemsWithoutCost()));
            }
        }
        return builder.build();
    }

    public OutletRestock toRestock(MerchantAccessContext context, List<FnbRestockSuggestion> items) {
        OutletRestock.Builder builder = OutletRestock.newBuilder()
                .setOutletId(context.getOutlet().getId().toString())
                .setOutletName(text(context.getOutlet().getName()));
        for (FnbRestockSuggestion item : items) {
            builder.addItems(RestockSuggestion.newBuilder()
                    .setInventoryItemId(item.getInventoryItemId() != null ? item.getInventoryItemId().toString() : "")
                    .setName(text(item.getName()))
                    .setUnit(text(item.getUnit()))
                    .setQuantityOnHand(money(item.getQuantityOnHand()))
                    .setReorderLevel(money(item.getReorderLevel()))
                    .setAverageDailyUsage(money(item.getAverageDailyUsage()))
                    .setSuggestedQuantity(money(item.getSuggestedQuantity()))
                    .setStockStatus(item.getStockStatus() != null ? item.getStockStatus().name() : ""));
        }
        return builder.build();
    }

    public OutletAnomalies toAnomalies(MerchantAccessContext context, LocalDate date, List<FnbCashierAnomaly> anomalies) {
        OutletAnomalies.Builder builder = OutletAnomalies.newBuilder()
                .setOutletId(context.getOutlet().getId().toString())
                .setOutletName(text(context.getOutlet().getName()))
                .setDate(date(date));
        for (FnbCashierAnomaly anomaly : anomalies) {
            builder.addAnomalies(CashierAnomaly.newBuilder()
                    .setStaffUserId(anomaly.getStaffUserId() != null ? anomaly.getStaffUserId().toString() : "")
                    .setFullName(text(anomaly.getFullName()))
                    .setKind(text(anomaly.getKind()))
                    .setValue(money(anomaly.getValue()))
                    .setBaselineDailyAverage(money(anomaly.getBaselineDailyAverage())));
        }
        return builder.build();
    }

    private static String money(BigDecimal value) {
        return value != null ? value.toPlainString() : "0";
    }

    private static String date(LocalDate value) {
        return value != null ? value.toString() : "";
    }

    private static String text(String value) {
        return value != null ? value : "";
    }
}
