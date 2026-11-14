package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class FnbStockMovementResponse {
    private UUID id;
    private UUID inventoryItemId;
    private InventoryMovementEntity.Type type;
    private BigDecimal quantityDelta;
    private String reason;
    private UUID orderId;
    private Instant occurredAt;
    private UUID actorMembershipId;
    private BigDecimal unitCost;
}
