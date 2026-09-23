package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="plan_prices",uniqueConstraints=@UniqueConstraint(columnNames={"plan_version_id","currency_code","billing_interval","effective_from"}))
@NoArgsConstructor
public class PlanPriceEntity extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="plan_version_id")
    private PlanVersionEntity planVersion;
    @Column(name="currency_code",nullable=false,length=3)
    private String currencyCode;
    @Column(name="billing_interval",nullable=false,length=16)
    private String billingInterval;
    @Column(nullable=false,precision=19,scale=2)
    private BigDecimal amount;
    @Column(name="effective_from",nullable=false)
    private Instant effectiveFrom;
    @Column(name="effective_until")
    private Instant effectiveUntil;
}
