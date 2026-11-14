package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbSalesCashierResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesHourlyResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesPaymentBreakdownResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesProductResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesShiftResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesSummaryResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesTransactionResponse;
import org.lite.project.cashiersaas.repository.FnbRefundRepository.RefundTotals;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.CashierRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.CostRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.HourlyRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.MethodRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.ProductRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.ShiftRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.SummaryRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.TransactionRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.List;

public final class FnbSalesReportMapper {
    private FnbSalesReportMapper() {
    }
    public static FnbSalesTransactionResponse transaction(TransactionRow row, ZoneId zone) {
        FnbSalesTransactionResponse value = new FnbSalesTransactionResponse();
        value.setOrderId(row.orderId);
        value.setOrderNumber(row.orderNumber);
        value.setOrderStatus(row.status);
        value.setPaymentMethod(row.method);
        value.setMethodLabel(row.methodLabel);
        value.setSubtotal(row.subtotal);
        value.setNetSales(row.subtotal.subtract(row.discount));
        value.setTotal(row.total);
        value.setAmountCollected(row.amountPaid.subtract(row.change));
        value.setDiscountAmount(row.discount);
        value.setTaxAmount(row.tax);
        value.setServiceChargeAmount(row.service);
        value.setReceiptNumber(row.receipt);
        value.setPaidAt(row.paidAt.atZone(zone));
        return value;
    }
    public static FnbSalesSummaryResponse summary(SummaryRow row, long cancellations, CostRow cost, RefundTotals refunds) {
        FnbSalesSummaryResponse value = new FnbSalesSummaryResponse();
        value.setSubtotal(row.subtotal);
        value.setNetSales(row.netSales);
        value.setTotal(row.total);
        value.setAmountCollected(row.amountCollected);
        value.setTransactionCount(row.count);
        value.setAverageTransaction(row.count == 0 ? BigDecimal.ZERO
                : row.total.divide(BigDecimal.valueOf(row.count), 2, RoundingMode.HALF_UP));
        value.setDiscountAmount(row.discount);
        value.setTaxAmount(row.tax);
        value.setServiceChargeAmount(row.service);
        value.setCancellationCount(cancellations);
        value.setCostOfGoodsSold(cost.cost);
        value.setGrossProfit(row.netSales.subtract(cost.cost));
        value.setItemsWithoutCost(cost.itemsWithoutCost);
        value.setRefundCount(refunds.count);
        value.setRefundAmount(refunds.amount);
        return value;
    }
    public static List<FnbSalesProductResponse> products(List<ProductRow> rows) {
        return rows.stream().map(row -> {
            FnbSalesProductResponse value = new FnbSalesProductResponse();
            value.setProductId(row.productId);
            value.setProductName(row.name);
            value.setQuantity(row.quantity);
            value.setGrossSales(row.grossSales);
            value.setCostOfGoodsSold(row.cost);
            value.setGrossProfit(row.grossSales.subtract(row.cost));
            value.setItemsWithoutCost(row.itemsWithoutCost);
            return value;
        }).toList();
    }
    public static List<FnbSalesPaymentBreakdownResponse> methods(List<MethodRow> rows) {
        return rows.stream().map(row -> {
            FnbSalesPaymentBreakdownResponse value = new FnbSalesPaymentBreakdownResponse();
            value.setMethod(row.method);
            value.setMethodLabel(row.methodLabel);
            value.setTransactionCount(row.count);
            value.setNetSales(row.netSales);
            value.setAmountCollected(row.amountCollected);
            return value;
        }).toList();
    }
    public static List<FnbSalesHourlyResponse> hourly(List<HourlyRow> rows) {
        return rows.stream().map(row -> {
            FnbSalesHourlyResponse value = new FnbSalesHourlyResponse();
            value.setHour(row.hour);
            value.setTransactionCount(row.count);
            value.setNetSales(row.netSales);
            return value;
        }).toList();
    }
    public static List<FnbSalesCashierResponse> cashiers(List<CashierRow> rows) {
        return rows.stream().map(row -> {
            FnbSalesCashierResponse value = new FnbSalesCashierResponse();
            value.setStaffUserId(row.staffUserId);
            value.setFullName(row.fullName);
            value.setTransactionCount(row.count);
            value.setNetSales(row.netSales);
            value.setAmountCollected(row.amountCollected);
            return value;
        }).toList();
    }
    public static List<FnbSalesShiftResponse> shifts(List<ShiftRow> rows) {
        return rows.stream().map(row -> {
            FnbSalesShiftResponse value = new FnbSalesShiftResponse();
            value.setShiftId(row.shiftId);
            value.setCashierName(row.cashierName);
            value.setOpenedAt(row.openedAt);
            value.setClosedAt(row.closedAt);
            value.setStatus(row.status == null ? null : row.status.name());
            value.setTransactionCount(row.count);
            value.setNetSales(row.netSales);
            return value;
        }).toList();
    }
}
