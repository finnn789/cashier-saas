package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** What Midtrans itself reports for an order (GET /v2/{order_id}/status). */
@Getter
@Setter
public class MidtransStatus {
    private String transactionStatus;
    private String fraudStatus;
    private BigDecimal grossAmount;
    private String transactionId;
    private String orderId;
}
