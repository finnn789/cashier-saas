package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class FnbLeakageReportResponse {
    private UUID stockCountId;
    private UUID outletId;
    private Instant countedAt;
    private String note;
    private BigDecimal totalLoss;
    private int flaggedCount;
    private List<FnbLeakageLineResponse> lines;
}
