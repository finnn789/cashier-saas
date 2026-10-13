package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CashierSaleResponse {
    private UUID id;
    private UUID outletId;
    private String saleNumber;
    private String customerName;
    private BigDecimal total;
    private String currencyCode;
    private String paymentMethod;
    private String paymentStatus;
    private BigDecimal tenderedAmount;
    private BigDecimal changeAmount;
    private String paymentReference;
    private Instant paidAt;
    private List<CashierSaleLineResponse> items;
}
