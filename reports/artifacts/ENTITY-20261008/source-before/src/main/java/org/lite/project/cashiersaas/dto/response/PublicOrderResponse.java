package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class PublicOrderResponse {
    private String orderNumber;
    private String orderToken;
    private Instant statusTokenExpiresAt;
    private OrderStatus status;
    private PaymentStatus paymentStatus;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal serviceChargeAmount;
    private BigDecimal total;
}
