package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class FnbOutletLeakageResponse {
    private UUID outletId;
    private String outletName;
    private int stockCountCount;
    private BigDecimal totalLoss;
    private int flaggedCount;
    private List<FnbLeakageLineResponse> lines;
}
