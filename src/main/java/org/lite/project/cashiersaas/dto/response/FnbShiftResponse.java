package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbShiftResponse {
    private UUID id;
    private CashShiftEntity.Status status;
    private UUID userId;
    private String fullName;
    private BigDecimal openingCash;
    private Instant openedAt;
    private Instant closedAt;
    private BigDecimal expectedCash;
    private BigDecimal countedCash;
    private BigDecimal cashDifference;
    private String closingNote;
    private long paymentCount;
    private List<FnbShiftMethodTotalResponse> totalsByMethod;
    private BigDecimal cashIn;
    private BigDecimal cashRefunds;
    private BigDecimal expensesTotal;
    private List<FnbCashExpenseResponse> expenses;
    private int openOrderCount;
}
