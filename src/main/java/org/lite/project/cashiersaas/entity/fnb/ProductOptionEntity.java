package org.lite.project.cashiersaas.entity.fnb;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "product_options")
@NoArgsConstructor
public class ProductOptionEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_group_id", nullable = false)
    private OptionGroupEntity optionGroup;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal priceAdjustment = BigDecimal.ZERO;
    @Column(nullable = false)
    private boolean available = true;
    @Column(nullable = false)
    private int displayOrder;

    public static ProductOptionEntity create(TenantEntity tenant, OutletEntity outlet, OptionGroupEntity group,
                                             String name, BigDecimal adjustment, boolean available, int displayOrder) {
        ProductOptionEntity option = new ProductOptionEntity();
        option.tenant = tenant;
        option.outlet = outlet;
        option.optionGroup = group;
        option.update(name, adjustment, available, displayOrder);
        return option;
    }

    public void update(String name, BigDecimal adjustment, boolean available, int displayOrder) {
        this.name = name;
        this.priceAdjustment = adjustment;
        this.available = available;
        this.displayOrder = displayOrder;
    }
}
