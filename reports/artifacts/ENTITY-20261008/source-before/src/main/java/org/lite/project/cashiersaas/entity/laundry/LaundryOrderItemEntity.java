package org.lite.project.cashiersaas.entity.laundry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="laundry_order_items")
@NoArgsConstructor
public class LaundryOrderItemEntity extends BaseEntity {
    @Column(name="tenant_id",nullable=false)
    private java.util.UUID tenantId;
    @Column(name="outlet_id",nullable=false)
    private java.util.UUID outletId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="laundry_order_id")
    private LaundryOrderEntity laundryOrder;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="laundry_service_id")
    private LaundryServiceEntity laundryService;
    @Column(name="source_price_id",nullable=false)
    private java.util.UUID sourcePriceId;
    @Column(name="service_name_snapshot",nullable=false,length=140)
    private String serviceNameSnapshot;
    @Column(name="billing_unit_snapshot",nullable=false,length=10)
    private String billingUnitSnapshot;
    @Column(name="billed_quantity",nullable=false,precision=14,scale=3)
    private BigDecimal billedQuantity;
    @Column(name="actual_quantity",precision=14,scale=3)
    private BigDecimal actualQuantity;
    @Column(name="minimum_quantity_snapshot",nullable=false,precision=14,scale=3)
    private BigDecimal minimumQuantitySnapshot;
    @Column(name="quantity_increment_snapshot",nullable=false,precision=14,scale=3)
    private BigDecimal quantityIncrementSnapshot;
    @Column(name="pricing_rule_snapshot",nullable=false,length=24)
    private String pricingRuleSnapshot;
    @Column(name="estimated_duration_minutes")
    private Integer estimatedDurationMinutes;
    @Column(name="unit_price_snapshot",nullable=false,precision=19,scale=2)
    private BigDecimal unitPriceSnapshot;
    @Column(name="line_total_amount",nullable=false,precision=19,scale=2)
    private BigDecimal lineTotalAmount;
    @Column(name="estimated_ready_at")
    private Instant estimatedReadyAt;
    @Column(name="item_description",columnDefinition="TEXT")
    private String itemDescription;
    @Column(name="condition_notes",columnDefinition="TEXT")
    private String conditionNotes;
}
