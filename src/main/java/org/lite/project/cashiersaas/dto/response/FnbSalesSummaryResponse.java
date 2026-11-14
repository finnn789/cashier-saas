package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbSalesSummaryResponse {
    private BigDecimal subtotal;
    private BigDecimal netSales;
    private BigDecimal total;
    private BigDecimal amountCollected;
    private long transactionCount;
    private BigDecimal averageTransaction;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal serviceChargeAmount;
    private long cancellationCount;
    private BigDecimal costOfGoodsSold;
    private BigDecimal grossProfit;
    private long itemsWithoutCost;
    private long refundCount;
    private BigDecimal refundAmount;
}
