package org.lite.project.cashiersaas.entity.laundry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name="laundry_services", uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","outlet_id","name"}))
@NoArgsConstructor
public class LaundryServiceEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id")
    private OutletEntity outlet;
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="category_id")
    private LaundryServiceCategoryEntity category;
    @Column(nullable=false,length=140)
    private String name;
    @Column(name="billing_unit",nullable=false,length=10)
    private String billingUnit;
    @Column(name="minimum_quantity",nullable=false,precision=14,scale=3)
    private BigDecimal minimumQuantity;
    @Column(name="quantity_increment",nullable=false,precision=14,scale=3)
    private BigDecimal quantityIncrement;
    @Column(nullable=false)
    private boolean active=true;
}
