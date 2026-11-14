package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** kind is one of CANCELLATIONS, DISCOUNTS, CASH_DIFFERENCE; value is a count or a rupiah amount. */
@Getter
@Setter
public class FnbCashierAnomaly {
    private UUID staffUserId;
    private String fullName;
    private String kind;
    private BigDecimal value;
    private BigDecimal baselineDailyAverage;
}
