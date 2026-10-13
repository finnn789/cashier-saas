package org.lite.project.cashiersaas.entity.shared.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;

import java.time.Instant;

@Getter
@Entity
@Table(name = "auth_sessions")
@NoArgsConstructor
public class AuthSessionEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private StaffUserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "outlet_id")
    private OutletEntity outlet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_id")
    private TenantMembershipEntity membership;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Role role;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public static AuthSessionEntity start(StaffUserEntity user, Instant expiresAt) {
        AuthSessionEntity session = new AuthSessionEntity();
        session.user = user;
        session.expiresAt = expiresAt;
        return session;
    }

    public void selectMerchant(TenantMembershipEntity membership, OutletEntity outlet) {
        if (revokedAt != null || !membership.getTenant().getId().equals(outlet.getTenant().getId())) {
            throw new OperationConflictException("Sesi tidak dapat memilih outlet.");
        }
        tenant = membership.getTenant();
        this.membership = membership;
        this.outlet = outlet;
        role = membership.getRole();
    }

    public boolean isUsableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
