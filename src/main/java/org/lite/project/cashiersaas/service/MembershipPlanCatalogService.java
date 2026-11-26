package org.lite.project.cashiersaas.service;

import org.lite.project.cashiersaas.dto.response.MembershipPlanResponse;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MembershipPlanCatalogService {
    // IDR, PRD v2.2 section 8.15; the yearly price is ten months.
    private static final BigDecimal PRO_MONTHLY = new BigDecimal("59000");
    private static final BigDecimal PRO_YEARLY = new BigDecimal("590000");
    private static final BigDecimal PREMIUM_MONTHLY = new BigDecimal("119000");
    private static final BigDecimal PREMIUM_YEARLY = new BigDecimal("1190000");
    /** Per extra outlet per month on a paid plan (TASK-083 M2); shown with Pro and Premium. */
    public static final BigDecimal OUTLET_ADDON_MONTHLY = new BigDecimal("49000");

    /** Premium AI top-up: extra questions for the current month (PRD v3). */
    public static final BigDecimal AI_TOPUP_PRICE = new BigDecimal("29000");
    public static final int AI_TOPUP_QUESTIONS = 200;

    /** Server-side plan price for one billing cycle; FREETIER is not purchasable. */
    public BigDecimal price(PlanCode plan, BillingCycle cycle) {
        boolean yearly = cycle == BillingCycle.YEARLY;
        return switch (plan) {
            case PRO -> yearly ? PRO_YEARLY : PRO_MONTHLY;
            case PREMIUM -> yearly ? PREMIUM_YEARLY : PREMIUM_MONTHLY;
            case FREETIER -> throw new InvalidOperationException("Paket gratis tidak dapat dibeli.");
        };
    }

    public List<MembershipPlanResponse> getAvailablePlans() {
        return List.of(
                MembershipPlanResponse.of(PlanCode.FREETIER, BigDecimal.ZERO, BigDecimal.ZERO),
                withOutletAddOn(MembershipPlanResponse.of(PlanCode.PRO, PRO_MONTHLY, PRO_YEARLY)),
                withOutletAddOn(MembershipPlanResponse.of(PlanCode.PREMIUM, PREMIUM_MONTHLY, PREMIUM_YEARLY))
        );
    }

    private static MembershipPlanResponse withOutletAddOn(MembershipPlanResponse plan) {
        plan.setOutletAddOnMonthlyPrice(OUTLET_ADDON_MONTHLY);
        return plan;
    }
}
