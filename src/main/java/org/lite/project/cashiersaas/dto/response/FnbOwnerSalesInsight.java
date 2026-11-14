package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class FnbOwnerSalesInsight {
    private FnbSalesReportResponse report;
    private long discountedOrderCount;
    private BigDecimal expensesTotal;
    private BigDecimal cashDifferenceTotal;
    private long closedShiftCount;
}
