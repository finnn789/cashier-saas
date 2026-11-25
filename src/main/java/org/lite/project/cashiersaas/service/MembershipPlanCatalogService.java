package org.lite.project.cashiersaas.service;

import org.lite.project.cashiersaas.dto.response.MembershipPlanResponse;
import org.lite.project.cashiersaas.enums.PlanCode;
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
