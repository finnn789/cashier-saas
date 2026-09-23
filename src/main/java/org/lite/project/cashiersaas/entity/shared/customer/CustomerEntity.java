package org.lite.project.cashiersaas.entity.shared.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name = "customers")
@NoArgsConstructor
public class CustomerEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id")
    private TenantEntity tenant;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(nullable = false, length = 40)
    private String phone;
    @Column(length = 320)
    private String email;
    @Column(length = 1000)
    private String notes;
}
