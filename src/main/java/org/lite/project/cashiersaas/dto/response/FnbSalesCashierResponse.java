package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbSalesCashierResponse {
    private UUID staffUserId;
    private String fullName;
    private long transactionCount;
    private BigDecimal netSales;
    private BigDecimal amountCollected;
}
