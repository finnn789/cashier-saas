package org.lite.project.cashiersaas.entity.shared.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name="inventory_items", uniqueConstraints=@UniqueConstraint(name="uk_inventory_items_outlet_name", columnNames={"outlet_id","name"}))
@NoArgsConstructor
public class InventoryItemEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id")
    private OutletEntity outlet;
    @Column(length=80)
    private String sku;
    @Column(nullable=false,length=140)
    private String name;
    @Column(nullable=false,length=30)
    private String unit;
    @Column(name="reorder_level",nullable=false,precision=14,scale=3)
    private BigDecimal reorderLevel;
    @Column(nullable=false)
    private boolean active=true;

    public static InventoryItemEntity create(TenantEntity tenant, OutletEntity outlet, String sku, String name, String unit, BigDecimal reorderLevel) {
        InventoryItemEntity item = new InventoryItemEntity();
        item.tenant = tenant;
        item.outlet = outlet;
        item.sku = sku;
        item.name = name;
        item.unit = unit;
        item.reorderLevel = reorderLevel;
        return item;
    }

    public void update(String sku, String name, String unit, BigDecimal reorderLevel) {
        this.sku = sku;
        this.name = name;
        this.unit = unit;
        this.reorderLevel = reorderLevel;
    }

    public void deactivate() {
        active = false;
    }
}
