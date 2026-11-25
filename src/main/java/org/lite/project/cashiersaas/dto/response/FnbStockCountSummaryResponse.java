package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class FnbStockCountSummaryResponse {
    private UUID stockCountId;
    private Instant countedAt;
    private String note;
    private int lineCount;
    private int flaggedCount;
    private BigDecimal totalLoss;
}
