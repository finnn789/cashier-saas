package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;

/** Used by both the quote and the checkout; plan and cycle are required only for SUBSCRIPTION (checked in the service). */
@Getter
@Setter
public class SubscriptionCheckoutRequest {
    @NotNull
    private InvoiceKind kind;
    private PlanCode planCode;
    private BillingCycle billingCycle;
    @Min(0)
    @Max(20)
    private Integer extraOutlets;
}
