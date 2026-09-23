package org.lite.project.cashiersaas.entity.shared.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;

@Getter
@Entity
@Table(name = "tenant_memberships", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "user_id"}))
@NoArgsConstructor
public class TenantMembershipEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private StaffUserEntity user;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role;
    @Column(nullable = false)
    private boolean active = true;

    public static TenantMembershipEntity grantOwner(StaffUserEntity user, TenantEntity tenant) {
        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.user = user;
        membership.tenant = tenant;
        membership.role = Role.OWNERMERCHANT;
        membership.active = true;
        return membership;
    }
}
