package org.lite.project.cashiersaas.entity.shared.merchant;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Getter
@Entity
@Table(name = "outlets", uniqueConstraints = @UniqueConstraint(name = "uk_outlets_tenant_name", columnNames = {"tenant_id", "name"}))
@NoArgsConstructor
public class OutletEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 64)
    private String timeZone;
    @Column(length = 255)
    private String address;
    @Column(length = 32)
    private String phoneNumber;
    @Column(nullable = false)
    private boolean active = true;

    public static OutletEntity open(TenantEntity tenant, String name, String timeZone, String address, String phoneNumber) {
        OutletEntity outlet = new OutletEntity();
        outlet.tenant = tenant;
        outlet.name = name;
        outlet.timeZone = timeZone;
        outlet.address = address;
        outlet.phoneNumber = phoneNumber;
        return outlet;
    }

    public boolean updateBusinessSettings(String name, String timeZone, String address, String phoneNumber) {
        boolean changed = !Objects.equals(this.name, name) || !Objects.equals(this.timeZone, timeZone)
                || !Objects.equals(this.address, address) || !Objects.equals(this.phoneNumber, phoneNumber);
        if (changed) {
            this.name = name;
            this.timeZone = timeZone;
            this.address = address;
            this.phoneNumber = phoneNumber;
        }
        return changed;
    }
}
