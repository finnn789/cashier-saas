package org.lite.project.cashiersaas.entity.fnb;

import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@Entity
@Table(name="order_item_options")
@NoArgsConstructor
public class OrderItemOptionEntity extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id",nullable=false)
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id",nullable=false)
    private OutletEntity outlet;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="order_item_id",nullable=false)
    private OrderItemEntity orderItem;
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="product_option_id")
    private ProductOptionEntity productOption;
    @Column(nullable=false,length=120)
    private String optionNameSnapshot;
    @Column(nullable=false,precision=19,scale=2)
    private BigDecimal priceAdjustmentSnapshot;
}
