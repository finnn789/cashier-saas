package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class FnbConsolidatedSalesReportResponse {
    private LocalDate startDate;
    private LocalDate endDate;
    private FnbSalesSummaryResponse totals;
    private List<FnbOutletSalesSummaryResponse> outlets;
}
