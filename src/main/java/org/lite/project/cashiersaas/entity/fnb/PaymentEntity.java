package org.lite.project.cashiersaas.entity.fnb;

import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Entity
@Table(name="payments", uniqueConstraints=@UniqueConstraint(name="uk_payments_sales_order",columnNames="sales_order_id"))
@NoArgsConstructor
public class PaymentEntity extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id",nullable=false)
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id",nullable=false)
    private OutletEntity outlet;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="sales_order_id",nullable=false)
    private SalesOrderEntity salesOrder;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false,length=32)
    private PaymentMethod method;
    @Column(nullable=false,precision=19,scale=2)
    private BigDecimal amountPaid;
    @Column(nullable=false,precision=19,scale=2)
    private BigDecimal changeAmount = BigDecimal.ZERO;
    @Column(nullable=false,length=32)
    private String receiptNumber;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="received_by_staff_user_id",nullable=false)
    private StaffUserEntity receivedBy;
    @Column(nullable=false)
    private Instant paidAt;
}
