package org.lite.project.cashiersaas.entity.shared.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Entity
@Table(name="purchase_lines",uniqueConstraints=@UniqueConstraint(columnNames={"purchase_id","inventory_item_id"}))
@NoArgsConstructor
public class PurchaseLineEntity extends BaseEntity {
    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="purchase_id")
    private PurchaseEntity purchase;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="inventory_item_id")
    private InventoryItemEntity inventoryItem;
    @Column(nullable=false,precision=14,scale=3)
    private BigDecimal quantity;
    @Column(name="unit_cost",nullable=false,precision=19,scale=4)
    private BigDecimal unitCost;
    @Column(name="line_total",nullable=false,precision=19,scale=2)
    private BigDecimal lineTotal;
    @Column(name="movement_id",nullable=false)
    private UUID movementId;

    public static PurchaseLineEntity record(UUID tenantId, PurchaseEntity purchase, InventoryItemEntity item, BigDecimal quantity,
                                            BigDecimal unitCost, BigDecimal lineTotal, UUID movementId) {
        PurchaseLineEntity line = new PurchaseLineEntity();
        line.tenantId = tenantId;
        line.purchase = purchase;
        line.inventoryItem = item;
        line.quantity = quantity;
        line.unitCost = unitCost;
        line.lineTotal = lineTotal;
        line.movementId = movementId;
        return line;
    }
}
