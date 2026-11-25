package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.PlanCode;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MerchantSubscriptionResponse {
    private PlanCode planCode;
    private Instant activeUntil;
    private Trial trial;
    private Outlets outlets;
    private Ai ai;
    private BigDecimal outletAddOnMonthlyPrice;

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Trial {
        private boolean active;
        private Instant startedAt;
        private Instant endsAt;
        private boolean eligible;
    }

    @Getter
    @Setter
    public static class Outlets {
        private int allowed;
        private int active;
        private int extra;
    }

    @Getter
    @Setter
    public static class Ai {
        private int monthlyLimit;
        private int monthlyUsed;
        private int dailyCap;
        private int dailyUsed;
        private Instant resetsAt;
    }
}
