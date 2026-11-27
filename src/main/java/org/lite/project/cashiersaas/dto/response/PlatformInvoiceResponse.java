package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class PlatformInvoiceResponse {
    private UUID id;
    private String invoiceNumber;
    private InvoiceKind kind;
    private String status;
    private PlanCode planCode;
    private BillingCycle billingCycle;
    private Integer periodMonths;
    private int extraOutlets;
    private int aiQuestions;
    private BigDecimal amountDue;
    private String currencyCode;
    private Instant issuedAt;
    private Instant dueAt;
    private Instant paidAt;
    private Instant cancelledAt;
    private String providerCode;
    private String providerPaymentRef;
    private boolean mockPaymentEnabled;
    /** Hosted payment page; set only while the invoice is ISSUED and its latest attempt is an open Midtrans one. */
    private String paymentUrl;
    /** Null until a subscription invoice is paid. */
    private Instant subscriptionStartsAt;
    private Instant subscriptionEndsAt;
}
