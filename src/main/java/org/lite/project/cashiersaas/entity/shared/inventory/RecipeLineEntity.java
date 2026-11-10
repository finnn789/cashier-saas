package org.lite.project.cashiersaas.entity.shared.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name = "recipe_lines")
@NoArgsConstructor
public class RecipeLineEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private ProductEntity product;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_option_id")
    private ProductOptionEntity productOption;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItemEntity inventoryItem;
    @Column(name = "quantity_per_unit", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantityPerUnit;

    public static RecipeLineEntity forProduct(TenantEntity tenant, OutletEntity outlet, ProductEntity product,
                                              InventoryItemEntity inventoryItem, BigDecimal quantityPerUnit) {
        RecipeLineEntity line = new RecipeLineEntity();
        line.tenant = tenant;
        line.outlet = outlet;
        line.product = product;
        line.inventoryItem = inventoryItem;
        line.quantityPerUnit = quantityPerUnit;
        return line;
    }

    public static RecipeLineEntity forOption(TenantEntity tenant, OutletEntity outlet, ProductOptionEntity productOption,
                                             InventoryItemEntity inventoryItem, BigDecimal quantityPerUnit) {
        RecipeLineEntity line = new RecipeLineEntity();
        line.tenant = tenant;
        line.outlet = outlet;
        line.productOption = productOption;
        line.inventoryItem = inventoryItem;
        line.quantityPerUnit = quantityPerUnit;
        return line;
    }
}
