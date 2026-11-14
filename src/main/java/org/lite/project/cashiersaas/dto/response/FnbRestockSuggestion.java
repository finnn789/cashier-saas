package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class FnbRestockSuggestion {
    private UUID inventoryItemId;
    private String name;
    private String unit;
    private BigDecimal quantityOnHand;
    private BigDecimal reorderLevel;
    private BigDecimal averageDailyUsage;
    private BigDecimal suggestedQuantity;
    private InventoryBalanceEntity.Status stockStatus;
}
