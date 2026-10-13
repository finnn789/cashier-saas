package org.lite.project.cashiersaas.entity.shared.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name = "customer_addresses")
@NoArgsConstructor
public class CustomerAddressEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private CustomerEntity customer;
    @Column(length = 80)
    private String label;
    @Column(name = "recipient_name", nullable = false, length = 160)
    private String recipientName;
    @Column(name = "recipient_phone", nullable = false, length = 40)
    private String recipientPhone;
    @Column(name = "address_text", nullable = false, columnDefinition = "TEXT")
    private String addressText;
    private boolean isDefault;
}
