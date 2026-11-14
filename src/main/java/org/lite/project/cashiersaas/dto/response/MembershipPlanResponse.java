package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.lite.project.cashiersaas.enums.PlanCode;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MembershipPlanResponse {

    private String code;
    private String name;
    private boolean available;
    private BigDecimal monthlyPrice;
    private BigDecimal yearlyPrice;

    public static MembershipPlanResponse of(PlanCode plan, BigDecimal monthlyPrice, BigDecimal yearlyPrice) {
        MembershipPlanResponse response = new MembershipPlanResponse();
        response.setCode(plan.name());
        response.setName(plan.getDisplayName());
        response.setAvailable(true);
        response.setMonthlyPrice(monthlyPrice);
        response.setYearlyPrice(yearlyPrice);
        return response;
    }
}
