package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbSalesShiftResponse {
    private UUID shiftId;
    private String cashierName;
    private Instant openedAt;
    private Instant closedAt;
    private String status;
    private long transactionCount;
    private BigDecimal netSales;
}
