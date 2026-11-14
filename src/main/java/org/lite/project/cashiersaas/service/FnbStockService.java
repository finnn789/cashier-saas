package org.lite.project.cashiersaas.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FnbStockService {
    private final FnbOrderRepository orders;
    private final FnbInventoryRepository inventory;
    private final DigestService tokens;

    // Runs inside the caller's payment transaction. Stock may go negative; this method never blocks a payment.
    public List<String> consumeForPaidOrder(MerchantAccessContext context, SalesOrderEntity order) {
        UUID tenantId = context.getTenant().getId();
        UUID outletId = context.getOutlet().getId();
        List<OrderItemEntity> items = orders.items(tenantId, outletId, List.of(order.getId()));
        List<OrderItemOptionEntity> options = orders.itemOptions(tenantId, outletId, items.stream().map(OrderItemEntity::getId).toList());
        List<UUID> productIds = items.stream().filter(item -> item.getProduct() != null).map(item -> item.getProduct().getId()).distinct().toList();
        List<UUID> optionIds = options.stream().filter(option -> option.getProductOption() != null).map(option -> option.getProductOption().getId()).distinct().toList();
        Map<UUID, List<RecipeLineEntity>> productRecipes = new HashMap<>();
        for (RecipeLineEntity line : inventory.productRecipes(tenantId, outletId, productIds))
            productRecipes.computeIfAbsent(line.getProduct().getId(), key -> new ArrayList<>()).add(line);
        Map<UUID, List<RecipeLineEntity>> optionRecipes = new HashMap<>();
        for (RecipeLineEntity line : inventory.optionRecipes(tenantId, outletId, optionIds))
            optionRecipes.computeIfAbsent(line.getProductOption().getId(), key -> new ArrayList<>()).add(line);

        Map<UUID, BigDecimal> needs = new TreeMap<>();
        Map<UUID, InventoryItemEntity> inventoryItems = new HashMap<>();
        for (OrderItemEntity item : items) {
            if (item.getProduct() == null) continue;
            addNeeds(needs, inventoryItems, productRecipes.get(item.getProduct().getId()), item.getQuantity());
        }
        for (OrderItemOptionEntity option : options) {
            if (option.getProductOption() == null) continue;
            addNeeds(needs, inventoryItems, optionRecipes.get(option.getProductOption().getId()), option.getOrderItem().getQuantity());
        }
        if (needs.isEmpty()) return List.of();

        Map<UUID, InventoryBalanceEntity> balances = new HashMap<>();
        for (InventoryBalanceEntity balance : inventory.balances(tenantId, outletId, new ArrayList<>(needs.keySet())))
            balances.put(balance.getInventoryItem().getId(), balance);

        List<String> warnings = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> need : needs.entrySet()) {
            UUID itemId = need.getKey();
            String key = "sale:" + order.getId() + ":" + itemId;
            if (inventory.movementByKey(tenantId, key).isPresent()) continue;
            InventoryItemEntity inventoryItem = inventoryItems.get(itemId);
            BigDecimal delta = need.getValue();
            InventoryBalanceEntity balance = lockedBalance(balances, tenantId, outletId, inventoryItem);
            balance.apply(delta.negate());
            inventory.persist(InventoryMovementEntity.record(tenantId, outletId, inventoryItem, order.getId(), InventoryMovementEntity.Type.SALE,
                    delta.negate(), "Penjualan " + order.getOrderNumber(), key,
                    tokens.digest("sale|" + order.getId() + "|" + itemId + "|" + delta.toPlainString()), context.getMembership().getId()));
            String warning = warning(inventoryItem, balance);
            if (warning != null) warnings.add(warning);
        }
        return warnings;
    }

    // Runs inside the refund transaction. Puts back exactly what the SALE movements took out; idempotent per (order, item).
    public List<String> reverseForRefundedOrder(MerchantAccessContext context, SalesOrderEntity order) {
        UUID tenantId = context.getTenant().getId();
        UUID outletId = context.getOutlet().getId();
        List<InventoryMovementEntity> sales = inventory.saleMovements(tenantId, outletId, order.getId()); // ordered by item id
        if (sales.isEmpty()) return List.of();
        Map<UUID, InventoryBalanceEntity> balances = new HashMap<>();
        for (InventoryBalanceEntity balance : inventory.balances(tenantId, outletId, sales.stream().map(sale -> sale.getInventoryItem().getId()).toList()))
            balances.put(balance.getInventoryItem().getId(), balance);

        List<String> warnings = new ArrayList<>();
        for (InventoryMovementEntity sale : sales) {
            InventoryItemEntity inventoryItem = sale.getInventoryItem();
            String key = "sale-reversal:" + order.getId() + ":" + inventoryItem.getId();
            if (inventory.movementByKey(tenantId, key).isPresent()) continue;
            BigDecimal delta = sale.getQuantityDelta().negate();
            InventoryBalanceEntity balance = lockedBalance(balances, tenantId, outletId, inventoryItem);
            balance.apply(delta);
            inventory.persist(InventoryMovementEntity.record(tenantId, outletId, inventoryItem, order.getId(), InventoryMovementEntity.Type.SALE_REVERSAL,
                    delta, "Refund pesanan " + order.getOrderNumber(), key,
                    tokens.digest("sale-reversal|" + order.getId() + "|" + inventoryItem.getId() + "|" + delta.toPlainString()), context.getMembership().getId(), sale.getId()));
            String warning = warning(inventoryItem, balance);
            if (warning != null) warnings.add(warning);
        }
        return warnings;
    }

    private InventoryBalanceEntity lockedBalance(Map<UUID, InventoryBalanceEntity> balances, UUID tenantId, UUID outletId, InventoryItemEntity item) {
        InventoryBalanceEntity balance = balances.get(item.getId());
        if (balance == null) {
            balance = InventoryBalanceEntity.create(tenantId, outletId, item);
            inventory.persist(balance);
        } else {
            inventory.lock(balance);
        }
        return balance;
    }

    private static void addNeeds(Map<UUID, BigDecimal> needs, Map<UUID, InventoryItemEntity> inventoryItems, List<RecipeLineEntity> recipe, int quantity) {
        if (recipe == null) return;
        for (RecipeLineEntity line : recipe) {
            UUID itemId = line.getInventoryItem().getId();
            inventoryItems.putIfAbsent(itemId, line.getInventoryItem());
            needs.merge(itemId, line.getQuantityPerUnit().multiply(BigDecimal.valueOf(quantity)), BigDecimal::add);
        }
    }

    private static String warning(InventoryItemEntity item, InventoryBalanceEntity balance) {
        String label = switch (balance.status()) {
            case NEGATIVE -> "minus";
            case OUT -> "habis";
            case LOW -> "menipis";
            case OK -> null;
        };
        if (label == null) return null;
        return item.getName() + ": sisa " + balance.getQuantityOnHand().stripTrailingZeros().toPlainString() + " " + item.getUnit() + " (" + label + ")";
    }
}
