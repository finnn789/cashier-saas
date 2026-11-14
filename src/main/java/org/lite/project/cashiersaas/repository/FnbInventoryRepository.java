package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.QInventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.QInventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.QInventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.QRecipeLineEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbInventoryRepository {
    private static final QInventoryItemEntity ITEM = new QInventoryItemEntity("inventoryItem");
    private static final QInventoryBalanceEntity BALANCE = new QInventoryBalanceEntity("inventoryBalance");
    private static final QInventoryMovementEntity MOVEMENT = new QInventoryMovementEntity("inventoryMovement");
    private static final QRecipeLineEntity RECIPE = new QRecipeLineEntity("recipeLine");
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<InventoryItemEntity> item(UUID tenant, UUID outlet, UUID id) {
        return criteriaBuilderFactory.create(entityManager, InventoryItemEntity.class, ITEM.getMetadata().getName())
                .where(ITEM.tenant.id.toString()).eq(tenant).where(ITEM.outlet.id.toString()).eq(outlet)
                .where(ITEM.id.toString()).eq(id).where(ITEM.active.toString()).eq(true)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public List<InventoryItemEntity> items(UUID tenant, UUID outlet) {
        return criteriaBuilderFactory.create(entityManager, InventoryItemEntity.class, ITEM.getMetadata().getName())
                .where(ITEM.tenant.id.toString()).eq(tenant).where(ITEM.outlet.id.toString()).eq(outlet)
                .where(ITEM.active.toString()).eq(true).orderByAsc(ITEM.name.toString()).getResultList();
    }

    public Optional<InventoryBalanceEntity> balance(UUID tenant, UUID outlet, UUID itemId) {
        return criteriaBuilderFactory.create(entityManager, InventoryBalanceEntity.class, BALANCE.getMetadata().getName())
                .where(BALANCE.tenantId.toString()).eq(tenant).where(BALANCE.outletId.toString()).eq(outlet)
                .where(BALANCE.inventoryItem.id.toString()).eq(itemId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public List<InventoryBalanceEntity> balances(UUID tenant, UUID outlet, List<UUID> itemIds) {
        if (itemIds.isEmpty()) return List.of();
        return criteriaBuilderFactory.create(entityManager, InventoryBalanceEntity.class, BALANCE.getMetadata().getName())
                .where(BALANCE.tenantId.toString()).eq(tenant).where(BALANCE.outletId.toString()).eq(outlet)
                .where(BALANCE.inventoryItem.id.toString()).in(itemIds).getResultList();
    }

    public void lock(InventoryBalanceEntity balance) {
        entityManager.refresh(balance, LockModeType.PESSIMISTIC_WRITE);
    }

    public List<InventoryMovementEntity> movements(UUID tenant, UUID outlet, UUID itemId, int page, int size) {
        return criteriaBuilderFactory.create(entityManager, InventoryMovementEntity.class, MOVEMENT.getMetadata().getName())
                .where(MOVEMENT.tenantId.toString()).eq(tenant).where(MOVEMENT.outletId.toString()).eq(outlet)
                .where(MOVEMENT.inventoryItem.id.toString()).eq(itemId)
                .orderByDesc(MOVEMENT.occurredAt.toString()).orderByDesc(MOVEMENT.id.toString())
                .setFirstResult(page * size).setMaxResults(size).getResultList();
    }

    public List<InventoryMovementEntity> saleMovements(UUID tenant, UUID outlet, UUID orderId) {
        return criteriaBuilderFactory.create(entityManager, InventoryMovementEntity.class, MOVEMENT.getMetadata().getName())
                .where(MOVEMENT.tenantId.toString()).eq(tenant).where(MOVEMENT.outletId.toString()).eq(outlet)
                .where(MOVEMENT.orderId.toString()).eq(orderId).where(MOVEMENT.type.toString()).eq(InventoryMovementEntity.Type.SALE)
                .orderByAsc(MOVEMENT.inventoryItem.id.toString()).getResultList();
    }

    public Optional<InventoryMovementEntity> movementByKey(UUID tenant, String idempotencyKey) {
        return criteriaBuilderFactory.create(entityManager, InventoryMovementEntity.class, MOVEMENT.getMetadata().getName())
                .where(MOVEMENT.tenantId.toString()).eq(tenant).where(MOVEMENT.idempotencyKey.toString()).eq(idempotencyKey)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    /** Net quantity_delta per item of the given movement types since the instant (usage is negative); one grouped query. */
    public Map<UUID, BigDecimal> netDeltaByItem(UUID tenant, UUID outlet, Instant since, List<InventoryMovementEntity.Type> types) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (Object[] row : entityManager.createQuery("select m.inventoryItem.id, sum(m.quantityDelta) from InventoryMovementEntity m "
                        + "where m.tenantId = :tenant and m.outletId = :outlet and m.occurredAt >= :since and m.type in :types "
                        + "group by m.inventoryItem.id", Object[].class)
                .setParameter("tenant", tenant).setParameter("outlet", outlet).setParameter("since", since)
                .setParameter("types", types).getResultList())
            totals.put((UUID) row[0], (BigDecimal) row[1]);
        return totals;
    }

    public List<RecipeLineEntity> productRecipes(UUID tenant, UUID outlet, List<UUID> productIds) {
        if (productIds.isEmpty()) return List.of();
        return criteriaBuilderFactory.create(entityManager, RecipeLineEntity.class, RECIPE.getMetadata().getName())
                .where(RECIPE.tenant.id.toString()).eq(tenant).where(RECIPE.outlet.id.toString()).eq(outlet)
                .where(RECIPE.product.id.toString()).in(productIds).orderByAsc(RECIPE.createdAt.toString()).getResultList();
    }

    public List<RecipeLineEntity> optionRecipes(UUID tenant, UUID outlet, List<UUID> optionIds) {
        if (optionIds.isEmpty()) return List.of();
        return criteriaBuilderFactory.create(entityManager, RecipeLineEntity.class, RECIPE.getMetadata().getName())
                .where(RECIPE.tenant.id.toString()).eq(tenant).where(RECIPE.outlet.id.toString()).eq(outlet)
                .where(RECIPE.productOption.id.toString()).in(optionIds).orderByAsc(RECIPE.createdAt.toString()).getResultList();
    }

    // Hibernate flushes inserts before deletes, so flush here to let a replacement recipe re-insert the same unique pairs.
    public void deleteProductRecipe(UUID tenant, UUID outlet, UUID productId) {
        productRecipes(tenant, outlet, List.of(productId)).forEach(entityManager::remove);
        entityManager.flush();
    }

    public void deleteOptionRecipe(UUID tenant, UUID outlet, UUID optionId) {
        optionRecipes(tenant, outlet, List.of(optionId)).forEach(entityManager::remove);
        entityManager.flush();
    }

    public void persist(InventoryItemEntity item) { entityManager.persist(item); }
    public void persist(InventoryBalanceEntity balance) { entityManager.persist(balance); }
    public void persist(InventoryMovementEntity movement) { entityManager.persist(movement); }
    public void persist(RecipeLineEntity recipeLine) { entityManager.persist(recipeLine); }

    public void flush() {
        entityManager.flush();
    }
}
