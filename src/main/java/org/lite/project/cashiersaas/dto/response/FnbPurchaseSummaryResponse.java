package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbPurchaseSummaryResponse {
    private UUID purchaseId;
    private String supplierName;
    private LocalDate purchasedOn;
    private BigDecimal total;
    private int lineCount;
    private Instant createdAt;
    private String actorName;
}
