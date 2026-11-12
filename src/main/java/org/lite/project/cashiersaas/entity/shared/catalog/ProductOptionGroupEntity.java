package org.lite.project.cashiersaas.entity.shared.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name = "product_option_groups")
@NoArgsConstructor
public class ProductOptionGroupEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_group_id", nullable = false)
    private OptionGroupEntity optionGroup;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public static ProductOptionGroupEntity create(TenantEntity tenant, OutletEntity outlet, ProductEntity product,
                                                  OptionGroupEntity optionGroup, int displayOrder) {
        ProductOptionGroupEntity link = new ProductOptionGroupEntity();
        link.tenant = tenant;
        link.outlet = outlet;
        link.product = product;
        link.optionGroup = optionGroup;
        link.displayOrder = displayOrder;
        return link;
    }
}
