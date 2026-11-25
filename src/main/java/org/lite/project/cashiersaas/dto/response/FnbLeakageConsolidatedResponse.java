package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class FnbLeakageConsolidatedResponse {
    private LocalDate from;
    private LocalDate to;
    private BigDecimal totalLoss;
    private List<FnbOutletLeakageResponse> outlets;
}
