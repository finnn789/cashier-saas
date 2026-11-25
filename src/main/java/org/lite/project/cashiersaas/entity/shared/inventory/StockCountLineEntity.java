package org.lite.project.cashiersaas.entity.shared.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Entity
@Table(name="stock_count_lines",uniqueConstraints=@UniqueConstraint(columnNames={"stock_count_id","inventory_item_id"}))
@NoArgsConstructor
public class StockCountLineEntity extends BaseEntity {
    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="stock_count_id")
    private StockCountEntity stockCount;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="inventory_item_id")
    private InventoryItemEntity inventoryItem;
    @Column(name="previous_quantity",nullable=false,precision=14,scale=3)
    private BigDecimal previousQuantity;
    @Column(name="counted_quantity",nullable=false,precision=14,scale=3)
    private BigDecimal countedQuantity;
    @Column(nullable=false,precision=14,scale=3)
    private BigDecimal difference;
    @Column(name="unit_cost",precision=19,scale=4)
    private BigDecimal unitCost;
    @Column(name="movement_id")
    private UUID movementId;

    /** {@code unitCost} is the balance average cost at the count (null when unknown); {@code movementId} is null when nothing changed. */
    public static StockCountLineEntity record(UUID tenantId, StockCountEntity stockCount, InventoryItemEntity item,
                                              BigDecimal previousQuantity, BigDecimal countedQuantity, BigDecimal difference,
                                              BigDecimal unitCost, UUID movementId) {
        StockCountLineEntity line = new StockCountLineEntity();
        line.tenantId = tenantId;
        line.stockCount = stockCount;
        line.inventoryItem = item;
        line.previousQuantity = previousQuantity;
        line.countedQuantity = countedQuantity;
        line.difference = difference;
        line.unitCost = unitCost;
        line.movementId = movementId;
        return line;
    }
}
