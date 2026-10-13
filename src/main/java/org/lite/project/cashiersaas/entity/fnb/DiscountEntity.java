package org.lite.project.cashiersaas.entity.fnb;

import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.enums.DiscountType;
import java.math.BigDecimal;

@Getter
@Entity
@Table(name="discounts")
@NoArgsConstructor
public class DiscountEntity extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id",nullable=false)
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id",nullable=false)
    private OutletEntity outlet;
    @OneToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="sales_order_id",nullable=false,unique=true)
    private SalesOrderEntity salesOrder;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false,length=24)
    private DiscountType type;
    @Column(nullable=false,precision=19,scale=2)
    private BigDecimal value;
    @Column(nullable=false,precision=19,scale=2)
    private BigDecimal amount;
    @Column(nullable=false,length=255)
    private String reason;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="granted_by_staff_user_id",nullable=false)
    private StaffUserEntity grantedBy;

    public static DiscountEntity grant(TenantEntity tenant, OutletEntity outlet, SalesOrderEntity order,
            DiscountType type, BigDecimal value, BigDecimal amount, String reason, StaffUserEntity grantedBy) {
        DiscountEntity discount = new DiscountEntity();
        discount.tenant = tenant; discount.outlet = outlet; discount.salesOrder = order; discount.type = type;
        discount.value = value; discount.amount = amount; discount.reason = reason; discount.grantedBy = grantedBy;
        return discount;
    }
}
