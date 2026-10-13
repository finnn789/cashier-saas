package org.lite.project.cashiersaas.dto.request;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;

@Getter @Setter
public class FnbSalesReportFilter {
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate startDate;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate endDate;
    private OrderStatus orderStatus;
    private PaymentMethod paymentMethod;
    private Integer page;
    private Integer size;
    private Integer topProducts;
}
