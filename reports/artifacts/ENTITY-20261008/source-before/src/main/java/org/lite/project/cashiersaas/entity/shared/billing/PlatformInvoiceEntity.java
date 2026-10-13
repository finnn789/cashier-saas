package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name="platform_invoices")
@NoArgsConstructor
public class PlatformInvoiceEntity extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="subscription_id")
    private SubscriptionEntity subscription;
    @Column(name="invoice_number",nullable=false,unique=true,length=50)
    private String invoiceNumber;
    @Column(name="currency_code",nullable=false,length=3)
    private String currencyCode;
    @Column(name="amount_due",nullable=false,precision=19,scale=2)
    private BigDecimal amountDue;
    @Column(nullable=false,length=16)
    private String status;
    private Instant dueAt;
    @Column(name="issued_at",nullable=false)
    private Instant issuedAt;
    private Instant paidAt;
}
