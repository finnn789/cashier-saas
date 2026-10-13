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
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "option_groups")
@NoArgsConstructor
public class OptionGroupEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false)
    private boolean required;
    @Column(nullable = false)
    private int minimumSelections;
    @Column(nullable = false)
    private int maximumSelections;
    @Column(nullable = false)
    private int displayOrder;

    public record Details(String name, boolean required, int minimumSelections,
                          int maximumSelections, int displayOrder) { }

    public static OptionGroupEntity create(TenantEntity tenant, OutletEntity outlet, ProductEntity product,
                                           Details details) {
        OptionGroupEntity group = new OptionGroupEntity();
        group.tenant = tenant;
        group.outlet = outlet;
        group.product = product;
        group.update(details);
        return group;
    }

    public void update(Details details) {
        this.name = details.name();
        this.required = details.required();
        this.minimumSelections = details.minimumSelections();
        this.maximumSelections = details.maximumSelections();
        this.displayOrder = details.displayOrder();
    }
}
