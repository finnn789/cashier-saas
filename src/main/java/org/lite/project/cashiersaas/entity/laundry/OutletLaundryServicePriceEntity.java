package org.lite.project.cashiersaas.entity.laundry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name="outlet_laundry_service_prices", uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","outlet_id","laundry_service_id","effective_from"}))
@NoArgsConstructor
public class OutletLaundryServicePriceEntity extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id")
    private OutletEntity outlet;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="laundry_service_id")
    private LaundryServiceEntity laundryService;
    @Column(name="currency_code",nullable=false,length=3)
    private String currencyCode;
    @Column(name="unit_price",nullable=false,precision=19,scale=2)
    private BigDecimal unitPrice;
    @Column(name="effective_from",nullable=false)
    private Instant effectiveFrom;
    @Column(name="effective_until")
    private Instant effectiveUntil;
}
