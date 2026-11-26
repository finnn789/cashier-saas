package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbInventoryItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbPurchaseLineResponse;
import org.lite.project.cashiersaas.dto.response.FnbPurchaseResponse;
import org.lite.project.cashiersaas.dto.response.FnbPurchaseSummaryResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountLineResponse;
import org.lite.project.cashiersaas.entity.shared.inventory.PurchaseEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.PurchaseLineEntity;
import org.lite.project.cashiersaas.dto.response.FnbStockMovementResponse;
import org.lite.project.cashiersaas.entity.shared.inventory.StockCountLineEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;

import java.math.BigDecimal;
import java.util.List;

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
    public static FnbStockCountLineResponse stockCountLine(StockCountLineEntity entity) {
        FnbStockCountLineResponse response = new FnbStockCountLineResponse();
        response.setInventoryItemId(entity.getInventoryItem().getId()); response.setName(entity.getInventoryItem().getName());
        response.setUnit(entity.getInventoryItem().getUnit()); response.setPreviousQuantity(entity.getPreviousQuantity());
        response.setCountedQuantity(entity.getCountedQuantity()); response.setDifference(entity.getDifference());
        response.setMovementId(entity.getMovementId());
        return response;
    }
    public static FnbPurchaseLineResponse purchaseLine(PurchaseLineEntity entity) {
        FnbPurchaseLineResponse response = new FnbPurchaseLineResponse();
        response.setInventoryItemId(entity.getInventoryItem().getId()); response.setName(entity.getInventoryItem().getName());
        response.setUnit(entity.getInventoryItem().getUnit()); response.setQuantity(entity.getQuantity());
        response.setUnitCost(entity.getUnitCost()); response.setLineTotal(entity.getLineTotal());
        response.setMovementId(entity.getMovementId());
        return response;
    }
    public static FnbPurchaseResponse purchase(PurchaseEntity entity, List<PurchaseLineEntity> lines, String actorName) {
        FnbPurchaseResponse response = new FnbPurchaseResponse();
        response.setPurchaseId(entity.getId()); response.setSupplierName(entity.getSupplierName());
        response.setPurchasedOn(entity.getPurchasedOn()); response.setNote(entity.getNote());
        response.setTotal(entity.getTotal()); response.setCreatedAt(entity.getCreatedAt());
        response.setActorName(actorName);
        response.setLines(lines.stream().map(FnbInventoryMapper::purchaseLine).toList());
        return response;
    }
    public static FnbPurchaseSummaryResponse purchaseSummary(PurchaseEntity entity, int lineCount, String actorName) {
        FnbPurchaseSummaryResponse response = new FnbPurchaseSummaryResponse();
        response.setPurchaseId(entity.getId()); response.setSupplierName(entity.getSupplierName());
        response.setPurchasedOn(entity.getPurchasedOn()); response.setTotal(entity.getTotal());
        response.setLineCount(lineCount); response.setCreatedAt(entity.getCreatedAt());
        response.setActorName(actorName);
        return response;
    }
    public static FnbStockMovementResponse movement(InventoryMovementEntity entity, boolean showCost, String actorName) {
        FnbStockMovementResponse response = new FnbStockMovementResponse();
        response.setId(entity.getId()); response.setInventoryItemId(entity.getInventoryItem().getId());
        response.setType(entity.getType()); response.setQuantityDelta(entity.getQuantityDelta());
        response.setReason(entity.getReason()); response.setOrderId(entity.getOrderId());
        response.setOccurredAt(entity.getOccurredAt()); response.setActorMembershipId(entity.getActorMembershipId());
        response.setActorName(actorName);
        response.setUnitCost(showCost ? entity.getUnitCost() : null);
        return response;
    }
}
