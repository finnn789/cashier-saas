package org.lite.project.cashiersaas.entity.shared.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;

@Getter
@Entity
@Table(name="inventory_balances", uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","outlet_id","inventory_item_id"}))
@NoArgsConstructor
public class InventoryBalanceEntity extends MutableBaseEntity {
    @Column(name="tenant_id",nullable=false)
    private java.util.UUID tenantId;
    @Column(name="outlet_id",nullable=false)
    private java.util.UUID outletId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="inventory_item_id")
    private InventoryItemEntity inventoryItem;
    @Column(name="quantity_on_hand",nullable=false,precision=14,scale=3)
    private BigDecimal quantityOnHand;
}
