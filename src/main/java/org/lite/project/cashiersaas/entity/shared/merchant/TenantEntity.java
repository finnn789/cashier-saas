package org.lite.project.cashiersaas.entity.shared.merchant;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "tenants")
@NoArgsConstructor
public class TenantEntity extends MutableBaseEntity {
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 3)
    private String currency = "IDR";
    @Column(nullable = false, length = 64)
    private String timeZone = "Asia/Jakarta";
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate = BigDecimal.ZERO;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal serviceChargeRate = BigDecimal.ZERO;
    @Column(length = 512)
    private String logoUrl;
    @Column(length = 255)
    private String address;
    @Column(length = 32)
    private String phoneNumber;

    public static TenantEntity createFromBusinessProfile(String businessName) {
        TenantEntity tenant = new TenantEntity();
        tenant.name = businessName;
        return tenant;
    }
}
