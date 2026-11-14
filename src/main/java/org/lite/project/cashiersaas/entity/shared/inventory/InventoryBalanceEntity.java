package org.lite.project.cashiersaas.entity.shared.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;

@Getter
@Entity
@Table(name="inventory_balances", uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","outlet_id","inventory_item_id"}))
@NoArgsConstructor
public class InventoryBalanceEntity extends MutableBaseEntity {
    public enum Status { OK, LOW, OUT, NEGATIVE }

    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @Column(name="outlet_id",nullable=false)
    private UUID outletId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="inventory_item_id")
    private InventoryItemEntity inventoryItem;
    @Column(name="quantity_on_hand",nullable=false,precision=14,scale=3)
    private BigDecimal quantityOnHand;
    @Column(name="average_unit_cost",precision=19,scale=4)
    private BigDecimal averageUnitCost;

    public static InventoryBalanceEntity create(UUID tenantId, UUID outletId, InventoryItemEntity item) {
        InventoryBalanceEntity balance = new InventoryBalanceEntity();
        balance.tenantId = tenantId;
        balance.outletId = outletId;
        balance.inventoryItem = item;
        balance.quantityOnHand = BigDecimal.ZERO;
        return balance;
    }

    public void apply(BigDecimal delta) {
        quantityOnHand = quantityOnHand.add(delta);
    }

    // Moving average: reset to the price when stock is empty/negative or no average exists yet; no price leaves the average unchanged.
    public void receive(BigDecimal quantity, BigDecimal unitCost) {
        if (unitCost != null) {
            averageUnitCost = quantityOnHand.signum() <= 0 || averageUnitCost == null
                    ? unitCost.setScale(4, RoundingMode.HALF_UP)
                    : quantityOnHand.multiply(averageUnitCost).add(quantity.multiply(unitCost))
                            .divide(quantityOnHand.add(quantity), 4, RoundingMode.HALF_UP);
        }
        apply(quantity);
    }

    public Status status() {
        int sign = quantityOnHand.signum();
        if (sign < 0) return Status.NEGATIVE;
        if (sign == 0) return Status.OUT;
        if (quantityOnHand.compareTo(inventoryItem.getReorderLevel()) <= 0) return Status.LOW;
        return Status.OK;
    }
}
