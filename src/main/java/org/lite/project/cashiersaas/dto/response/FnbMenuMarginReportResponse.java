package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class FnbMenuMarginReportResponse {
    private UUID outletId;
    private String outletName;
    private BigDecimal targetMarginPct;
    private int belowTargetCount;
    private List<FnbMenuMarginResponse> menus;
}
