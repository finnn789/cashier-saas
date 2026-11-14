package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class FnbStockCountLineResponse {
    private UUID inventoryItemId;
    private String name;
    private String unit;
    private BigDecimal previousQuantity;
    private BigDecimal countedQuantity;
    private BigDecimal difference;
    private UUID movementId;
}
