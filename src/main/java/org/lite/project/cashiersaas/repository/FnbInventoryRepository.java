package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.PurchaseEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.PurchaseLineEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.QInventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.QInventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.QInventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.QRecipeLineEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.StockCountEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.StockCountLineEntity;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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

    /** True when the outlet owns any inventory item, inactive ones included. */
    public boolean hasItems(UUID tenant, UUID outlet) {
        return !entityManager.createQuery("select i.id from InventoryItemEntity i where i.tenant.id = :tenant and i.outlet.id = :outlet", UUID.class)
                .setParameter("tenant", tenant).setParameter("outlet", outlet).setMaxResults(1).getResultList().isEmpty();
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

    /** Membership id to the member's full name, tenant scoped; unknown ids are absent. */
    public Map<UUID, String> memberNames(UUID tenant, List<UUID> membershipIds) {
        Map<UUID, String> names = new HashMap<>();
        if (membershipIds.isEmpty()) return names;
        for (Object[] row : entityManager.createQuery("select m.id, m.user.fullName from TenantMembershipEntity m "
                        + "where m.tenant.id = :tenant and m.id in :ids", Object[].class)
                .setParameter("tenant", tenant).setParameter("ids", membershipIds).getResultList())
            names.put((UUID) row[0], (String) row[1]);
        return names;
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

    public Optional<StockCountEntity> stockCountByKey(UUID tenant, String idempotencyKey) {
        return entityManager.createQuery("select c from StockCountEntity c where c.tenantId = :tenant and c.idempotencyKey = :key",
                        StockCountEntity.class)
                .setParameter("tenant", tenant).setParameter("key", idempotencyKey).setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<StockCountEntity> stockCount(UUID tenant, UUID outlet, UUID id) {
        return entityManager.createQuery("select c from StockCountEntity c where c.tenantId = :tenant and c.outletId = :outlet and c.id = :id",
                        StockCountEntity.class)
                .setParameter("tenant", tenant).setParameter("outlet", outlet).setParameter("id", id)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    /** Newest first. */
    public List<StockCountEntity> stockCounts(UUID tenant, UUID outlet, int page, int size) {
        return entityManager.createQuery("select c from StockCountEntity c where c.tenantId = :tenant and c.outletId = :outlet "
                        + "order by c.countedAt desc, c.id desc", StockCountEntity.class)
                .setParameter("tenant", tenant).setParameter("outlet", outlet)
                .setFirstResult(page * size).setMaxResults(size).getResultList();
    }

    /** Counts with {@code from <= counted_at < to}, oldest first. */
    public List<StockCountEntity> stockCountsBetween(UUID tenant, UUID outlet, Instant from, Instant to) {
        return entityManager.createQuery("select c from StockCountEntity c where c.tenantId = :tenant and c.outletId = :outlet "
                        + "and c.countedAt >= :from and c.countedAt < :to order by c.countedAt, c.id", StockCountEntity.class)
                .setParameter("tenant", tenant).setParameter("outlet", outlet)
                .setParameter("from", from).setParameter("to", to).getResultList();
    }

    public List<StockCountLineEntity> stockCountLines(UUID tenant, UUID stockCountId) {
        return entityManager.createQuery("select l from StockCountLineEntity l join fetch l.inventoryItem i "
                        + "where l.tenantId = :tenant and l.stockCount.id = :count order by i.name, i.id", StockCountLineEntity.class)
                .setParameter("tenant", tenant).setParameter("count", stockCountId).getResultList();
    }

    public Optional<PurchaseEntity> purchaseByKey(UUID tenant, String idempotencyKey) {
        return entityManager.createQuery("select p from PurchaseEntity p where p.tenantId = :tenant and p.idempotencyKey = :key",
                        PurchaseEntity.class)
                .setParameter("tenant", tenant).setParameter("key", idempotencyKey).setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<PurchaseEntity> purchase(UUID tenant, UUID outlet, UUID id) {
        return entityManager.createQuery("select p from PurchaseEntity p where p.tenantId = :tenant and p.outletId = :outlet and p.id = :id",
                        PurchaseEntity.class)
                .setParameter("tenant", tenant).setParameter("outlet", outlet).setParameter("id", id)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    /** Newest purchase date first. */
    public List<PurchaseEntity> purchases(UUID tenant, UUID outlet, int page, int size) {
        return entityManager.createQuery("select p from PurchaseEntity p where p.tenantId = :tenant and p.outletId = :outlet "
                        + "order by p.purchasedOn desc, p.createdAt desc, p.id desc", PurchaseEntity.class)
                .setParameter("tenant", tenant).setParameter("outlet", outlet)
                .setFirstResult(page * size).setMaxResults(size).getResultList();
    }

    public List<PurchaseLineEntity> purchaseLines(UUID tenant, UUID purchaseId) {
        return entityManager.createQuery("select l from PurchaseLineEntity l join fetch l.inventoryItem i "
                        + "where l.tenantId = :tenant and l.purchase.id = :purchase order by i.name, i.id", PurchaseLineEntity.class)
                .setParameter("tenant", tenant).setParameter("purchase", purchaseId).getResultList();
    }

    /** Line count per purchase id in one grouped query. */
    public Map<UUID, Integer> purchaseLineCounts(UUID tenant, List<UUID> purchaseIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (purchaseIds.isEmpty()) return counts;
        for (Object[] row : entityManager.createQuery("select l.purchase.id, count(l) from PurchaseLineEntity l "
                        + "where l.tenantId = :tenant and l.purchase.id in :ids group by l.purchase.id", Object[].class)
                .setParameter("tenant", tenant).setParameter("ids", purchaseIds).getResultList())
            counts.put((UUID) row[0], ((Long) row[1]).intValue());
        return counts;
    }

    /** Per item, the counted_at of its latest stock count strictly before {@code before}; items never counted are absent. */
    public Map<UUID, Instant> previousCountTimes(UUID tenant, UUID outlet, List<UUID> itemIds, Instant before) {
        Map<UUID, Instant> times = new HashMap<>();
        for (Object[] row : entityManager.createQuery("select l.inventoryItem.id, max(l.stockCount.countedAt) from StockCountLineEntity l "
                        + "where l.tenantId = :tenant and l.stockCount.outletId = :outlet and l.inventoryItem.id in :items "
                        + "and l.stockCount.countedAt < :before group by l.inventoryItem.id", Object[].class)
                .setParameter("tenant", tenant).setParameter("outlet", outlet).setParameter("items", itemIds)
                .setParameter("before", before).getResultList())
            times.put((UUID) row[0], (Instant) row[1]);
        return times;
    }

    /**
     * Net quantity_delta per item and movement type with {@code start < occurred_at <= end}. Adjustments created by a
     * stock count (idempotency key {@code count:...}) are left out; the remaining ADJUSTMENT rows are manual ones.
     */
    public List<MovementTotal> movementTotals(UUID tenant, UUID outlet, List<UUID> itemIds, Instant start, Instant end) {
        List<MovementTotal> totals = new ArrayList<>();
        for (Object[] row : entityManager.createQuery("select m.inventoryItem.id, m.type, sum(m.quantityDelta) from InventoryMovementEntity m "
                        + "where m.tenantId = :tenant and m.outletId = :outlet and m.inventoryItem.id in :items "
                        + "and m.occurredAt > :start and m.occurredAt <= :end "
                        + "and (m.type <> :adjustment or m.idempotencyKey not like :countKey) group by m.inventoryItem.id, m.type", Object[].class)
                .setParameter("tenant", tenant).setParameter("outlet", outlet).setParameter("items", itemIds)
                .setParameter("start", start).setParameter("end", end)
                .setParameter("adjustment", InventoryMovementEntity.Type.ADJUSTMENT).setParameter("countKey", "count:%").getResultList())
            totals.add(new MovementTotal((UUID) row[0], (InventoryMovementEntity.Type) row[1], (BigDecimal) row[2]));
        return totals;
    }

    public static class MovementTotal {
        public final UUID itemId;
        public final InventoryMovementEntity.Type type;
        public final BigDecimal total;

        MovementTotal(UUID itemId, InventoryMovementEntity.Type type, BigDecimal total) {
            this.itemId = itemId;
            this.type = type;
            this.total = total;
        }
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
    public void persist(StockCountEntity stockCount) { entityManager.persist(stockCount); }
    public void persist(StockCountLineEntity line) { entityManager.persist(line); }
    public void persist(PurchaseEntity purchase) { entityManager.persist(purchase); }
    public void persist(PurchaseLineEntity line) { entityManager.persist(line); }

    public void flush() {
        entityManager.flush();
    }
}
