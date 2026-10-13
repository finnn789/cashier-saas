package org.lite.project.cashiersaas.entity.laundry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name="laundry_service_categories", uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","outlet_id","name"}))
@NoArgsConstructor
public class LaundryServiceCategoryEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id")
    private OutletEntity outlet;
    @Column(nullable=false,length=100)
    private String name;
    @Column(nullable=false)
    private int sortOrder;
    @Column(nullable=false)
    private boolean active=true;
}
