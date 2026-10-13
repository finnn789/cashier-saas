package org.lite.project.cashiersaas.entity.shared.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;

import java.time.Instant;

@Getter
@Entity
@Table(name = "cashier_invitations")
@NoArgsConstructor
public class CashierInvitationEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;

    @Column(name = "invited_email", nullable = false, length = 320)
    private String invitedEmail;

    @Column(name = "code_digest", nullable = false, length = 64, unique = true)
    private String codeDigest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_membership_id", nullable = false)
    private TenantMembershipEntity invitedByMembership;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claimed_by_user_id")
    private StaffUserEntity claimedByUser;

    public static CashierInvitationEntity issue(TenantEntity tenant, TenantMembershipEntity inviter, OutletEntity outlet,
                                                String email, String digest, Instant expiresAt) {
        CashierInvitationEntity invitation = new CashierInvitationEntity();
        invitation.tenant = tenant;
        invitation.outlet = outlet;
        invitation.invitedEmail = email;
        invitation.codeDigest = digest;
        invitation.invitedByMembership = inviter;
        invitation.expiresAt = expiresAt;
        return invitation;
    }

    public boolean isClaimedBy(StaffUserEntity user) {
        return claimedByUser != null && claimedByUser.getId().equals(user.getId());
    }

    public void claim(StaffUserEntity user, Instant now) {
        if (claimedAt != null || !expiresAt.isAfter(now)) {
            throw new OperationConflictException("Undangan tidak dapat digunakan.");
        }
        claimedByUser = user;
        claimedAt = now;
    }
}
