package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbInventoryItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockMovementResponse;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;

import java.math.BigDecimal;

public final class FnbInventoryMapper {
    private FnbInventoryMapper() { }
    // A missing balance row is reported as zero stock (OUT) instead of failing the read.
    public static FnbInventoryItemResponse item(InventoryItemEntity entity, InventoryBalanceEntity balance, boolean showCost) {
        FnbInventoryItemResponse response = new FnbInventoryItemResponse();
        response.setId(entity.getId()); response.setSku(entity.getSku());
        response.setName(entity.getName()); response.setUnit(entity.getUnit());
        response.setReorderLevel(entity.getReorderLevel()); response.setActive(entity.isActive());
        response.setQuantityOnHand(balance == null ? BigDecimal.ZERO : balance.getQuantityOnHand());
        response.setStockStatus(balance == null ? InventoryBalanceEntity.Status.OUT : balance.status());
        response.setAverageUnitCost(showCost && balance != null ? balance.getAverageUnitCost() : null);
        return response;
    }
    public static FnbStockMovementResponse movement(InventoryMovementEntity entity, boolean showCost) {
        FnbStockMovementResponse response = new FnbStockMovementResponse();
        response.setId(entity.getId()); response.setInventoryItemId(entity.getInventoryItem().getId());
        response.setType(entity.getType()); response.setQuantityDelta(entity.getQuantityDelta());
        response.setReason(entity.getReason()); response.setOrderId(entity.getOrderId());
        response.setOccurredAt(entity.getOccurredAt()); response.setActorMembershipId(entity.getActorMembershipId());
        response.setUnitCost(showCost ? entity.getUnitCost() : null);
        return response;
    }
}
