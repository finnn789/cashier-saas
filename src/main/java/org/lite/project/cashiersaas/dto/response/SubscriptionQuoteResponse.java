package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class SubscriptionQuoteResponse {
    private InvoiceKind kind;
    private PlanCode planCode;
    private BillingCycle billingCycle;
    private Integer periodMonths;
    private int extraOutlets;
    /** NEW, RENEW, UPGRADE, DOWNGRADE or AI_TOPUP. */
    private String changeType;
    private List<Line> lines;
    private BigDecimal totalAmount;
    private String currencyCode;
    private Instant estimatedStartsAt;
    private Instant estimatedEndsAt;
    private int aiQuestions;

    @Getter
    @Setter
    public static class Line {
        private String label;
        private BigDecimal amount;
    }
}
