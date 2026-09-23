package org.lite.project.cashiersaas.entity.fnb;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.enums.DiningTableStatus;

@Getter
@Entity
@Table(name="dining_tables", uniqueConstraints=@UniqueConstraint(name="uk_dining_tables_outlet_name",columnNames={"outlet_id","name"}))
@NoArgsConstructor
public class DiningTableEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id",nullable=false)
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id",nullable=false)
    private OutletEntity outlet;
    @Column(nullable=false,length=80)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false,length=32)
    private DiningTableStatus status = DiningTableStatus.VACANT;
    @Column(nullable=false)
    private boolean active = true;
}
