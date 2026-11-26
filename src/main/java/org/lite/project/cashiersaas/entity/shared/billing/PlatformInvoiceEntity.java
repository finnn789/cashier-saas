package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;

@Getter
@Entity
@Table(name="platform_invoices")
@NoArgsConstructor
public class PlatformInvoiceEntity extends BaseEntity {
    public static final String ISSUED = "ISSUED";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
    private static final long PAYMENT_WINDOW_HOURS = 24;

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
    @Enumerated(EnumType.STRING)
    @Column(nullable=false,length=20)
    private InvoiceKind kind;
    @Column(name="plan_code",length=40)
    private String planCode;
    @Enumerated(EnumType.STRING)
    @Column(name="billing_cycle",length=10)
    private BillingCycle billingCycle;
    @Column(name="period_months")
    private Integer periodMonths;
    @Column(name="extra_outlets",nullable=false)
    private int extraOutlets;
    @Column(name="ai_questions",nullable=false)
    private int aiQuestions;
    private Instant cancelledAt;
    @Column(name="cancel_reason",length=500)
    private String cancelReason;

    public static PlatformInvoiceEntity issue(TenantEntity tenant, SubscriptionEntity subscription, String invoiceNumber,
                                              InvoiceKind kind, String planCode, BillingCycle billingCycle, Integer periodMonths,
                                              int extraOutlets, int aiQuestions, BigDecimal amountDue, Instant now) {
        PlatformInvoiceEntity invoice = new PlatformInvoiceEntity();
        invoice.tenant = tenant;
        invoice.subscription = subscription;
        invoice.invoiceNumber = invoiceNumber;
        invoice.currencyCode = "IDR";
        invoice.amountDue = amountDue;
        invoice.status = ISSUED;
        invoice.issuedAt = now;
        invoice.dueAt = now.plusSeconds(PAYMENT_WINDOW_HOURS * 3600);
        invoice.kind = kind;
        invoice.planCode = planCode;
        invoice.billingCycle = billingCycle;
        invoice.periodMonths = periodMonths;
        invoice.extraOutlets = extraOutlets;
        invoice.aiQuestions = aiQuestions;
        return invoice;
    }

    public boolean isIssued() {
        return ISSUED.equals(status);
    }

    public boolean isPastDue(Instant now) {
        return dueAt != null && !dueAt.isAfter(now);
    }

    public void markPaid(Instant now) {
        this.status = PAID;
        this.paidAt = now;
    }

    public void cancel(String reason, Instant now) {
        this.status = CANCELLED;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }

    public void expire() {
        this.status = EXPIRED;
    }
}
