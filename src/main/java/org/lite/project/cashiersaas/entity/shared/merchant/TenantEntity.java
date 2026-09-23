package org.lite.project.cashiersaas.entity.shared.merchant;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.billing.BusinessTypeEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import org.lite.project.cashiersaas.shared.StatusConstants;

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
    @Column(nullable = false, length = 20)
    private String status = StatusConstants.ACTIVE;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_type_id")
    private BusinessTypeEntity businessType;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private StaffUserEntity createdByUser;
    @Column(name = "onboarding_idempotency_key", length = 128)
    private String onboardingIdempotencyKey;
    @Column(name = "onboarding_request_fingerprint", length = 64)
    private String onboardingRequestFingerprint;
    @Column(name = "profile_completed_at")
    private Instant profileCompletedAt;

    public static TenantEntity startFreeTier(StaffUserEntity owner, BusinessTypeEntity type, String key, String fingerprint) {
        TenantEntity tenant = new TenantEntity();
        tenant.name = "Pending Laundry merchant";
        tenant.status = StatusConstants.PENDING_PROFILE;
        tenant.businessType = type;
        tenant.createdByUser = owner;
        tenant.onboardingIdempotencyKey = key;
        tenant.onboardingRequestFingerprint = fingerprint;
        return tenant;
    }

    public boolean isActive() {
        return StatusConstants.ACTIVE.equals(status);
    }

    public void completeFreeTierProfile(String businessName, String address, String phoneNumber) {
        if (!StatusConstants.PENDING_PROFILE.equals(status)) {
            throw new IllegalStateException("Merchant profile is not pending.");
        }
        this.name = businessName;
        this.address = address;
        this.phoneNumber = phoneNumber;
        this.profileCompletedAt = Instant.now();
        this.status = StatusConstants.ACTIVE;
    }

}
