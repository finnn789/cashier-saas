package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbSalesPaymentBreakdownResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesProductResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesSummaryResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesTransactionResponse;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.MethodRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.ProductRow;
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
    public static FnbSalesSummaryResponse summary(SummaryRow row, long cancellations) {
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
        return value;
    }
    public static List<FnbSalesProductResponse> products(List<ProductRow> rows) {
        return rows.stream().map(row -> {
            FnbSalesProductResponse value = new FnbSalesProductResponse();
            value.setProductId(row.productId);
            value.setProductName(row.name);
            value.setQuantity(row.quantity);
            value.setGrossSales(row.grossSales);
            return value;
        }).toList();
    }
    public static List<FnbSalesPaymentBreakdownResponse> methods(List<MethodRow> rows) {
        return rows.stream().map(row -> {
            FnbSalesPaymentBreakdownResponse value = new FnbSalesPaymentBreakdownResponse();
            value.setMethod(row.method);
            value.setTransactionCount(row.count);
            value.setNetSales(row.netSales);
            value.setAmountCollected(row.amountCollected);
            return value;
        }).toList();
    }
}
