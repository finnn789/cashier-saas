package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class FnbPurchaseLineResponse {
    private UUID inventoryItemId;
    private String name;
    private String unit;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal lineTotal;
    private UUID movementId;
}
