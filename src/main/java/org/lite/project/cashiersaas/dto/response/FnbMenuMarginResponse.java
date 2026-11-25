package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class FnbMenuMarginResponse {
    private UUID productId;
    private String name;
    private String categoryName;
    private BigDecimal price;
    private BigDecimal hpp;
    private BigDecimal marginPct;
    private String status;
    private BigDecimal suggestedPrice;
    private List<String> missingCostItems = List.of();
}
