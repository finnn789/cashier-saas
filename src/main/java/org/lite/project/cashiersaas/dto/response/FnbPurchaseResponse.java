package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbPurchaseResponse {
    private UUID purchaseId;
    private String supplierName;
    private LocalDate purchasedOn;
    private String note;
    private BigDecimal total;
    private Instant createdAt;
    private String actorName;
    private List<FnbPurchaseLineResponse> lines;
    /** Set on the POST that recorded the purchase only; null (omitted) on GET and replay. */
    private List<String> marginWarnings;
}
