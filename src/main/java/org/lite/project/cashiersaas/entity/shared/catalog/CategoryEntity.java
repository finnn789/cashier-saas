package org.lite.project.cashiersaas.entity.shared.catalog;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "categories", uniqueConstraints = @UniqueConstraint(name = "uk_categories_outlet_name", columnNames = {"outlet_id", "name"}))
@NoArgsConstructor
public class CategoryEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false)
    private int displayOrder;
    @Column(nullable = false)
    private boolean archived = false;

    public static CategoryEntity create(TenantEntity tenant, OutletEntity outlet, String name, int displayOrder) {
        CategoryEntity category = new CategoryEntity();
        category.tenant = tenant;
        category.outlet = outlet;
        category.name = name;
        category.displayOrder = displayOrder;
        return category;
    }

    public void update(String name, int displayOrder) {
        this.name = name;
        this.displayOrder = displayOrder;
    }

    public void archive() {
        archived = true;
    }
}
