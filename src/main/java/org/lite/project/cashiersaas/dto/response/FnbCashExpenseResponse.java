package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class FnbCashExpenseResponse {
    private UUID id;
    private BigDecimal amount;
    private String description;
    private Instant recordedAt;
    private String recordedBy;
}
