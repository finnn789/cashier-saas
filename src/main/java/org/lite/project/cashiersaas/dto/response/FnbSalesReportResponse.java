package org.lite.project.cashiersaas.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude; import lombok.Getter; import lombok.Setter; import java.time.LocalDate; import java.util.List;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbSalesReportResponse { private LocalDate startDate; private LocalDate endDate; private String timeZone; private FnbSalesSummaryResponse summary; private List<FnbSalesTransactionResponse> transactions; private List<FnbSalesProductResponse> topProducts; private List<FnbSalesPaymentBreakdownResponse> paymentBreakdown; private List<FnbSalesHourlyResponse> hourly; private List<FnbSalesCashierResponse> cashiers; private List<FnbSalesShiftResponse> shifts; private int page; private int size; private boolean hasNext; }
