package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbSalesTransactionResponse {
    private UUID orderId;
    private String orderNumber;
    private OrderStatus orderStatus;
    private PaymentMethod paymentMethod;
    private String methodLabel;
    private BigDecimal subtotal;
    private BigDecimal netSales;
    private BigDecimal total;
    private BigDecimal amountCollected;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal serviceChargeAmount;
    private String receiptNumber;
    private ZonedDateTime paidAt;
}
