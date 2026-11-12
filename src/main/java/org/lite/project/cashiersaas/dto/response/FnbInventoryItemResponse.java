package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class FnbInventoryItemResponse {
    private UUID id;
    private String sku;
    private String name;
    private String unit;
    private BigDecimal reorderLevel;
    private boolean active;
    private BigDecimal quantityOnHand;
    private InventoryBalanceEntity.Status stockStatus;
}
