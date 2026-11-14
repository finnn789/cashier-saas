package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbSalesProductResponse {
    private UUID productId;
    private String productName;
    private long quantity;
    private BigDecimal grossSales;
    private BigDecimal costOfGoodsSold;
    private BigDecimal grossProfit;
    private long itemsWithoutCost;
}
