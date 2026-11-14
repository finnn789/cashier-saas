package org.lite.project.cashiersaas.entity.shared.merchant;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.billing.BusinessTypeEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.shared.StatusConstants;

import java.util.Objects;

@Getter
@Entity
@Table(name = "tenants")
@NoArgsConstructor
public class TenantEntity extends MutableBaseEntity {
    public static final Duration PREMIUM_TRIAL_DURATION = Duration.ofDays(14);

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
    @Column(name = "receipt_footer", length = 200)
    private String receiptFooter;
    @Column(name = "premium_trial_started_at")
    private Instant premiumTrialStartedAt;

    public static TenantEntity startFreeTier(StaffUserEntity owner, BusinessTypeEntity type, String key, String fingerprint) {
        TenantEntity tenant = new TenantEntity();
        tenant.name = "Pending merchant";
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

    public void startPremiumTrial(Instant now) {
        if (premiumTrialStartedAt == null) {
            premiumTrialStartedAt = now;
        }
    }

    public boolean isPremiumTrialActiveAt(Instant now) {
        return premiumTrialStartedAt != null && now.isBefore(premiumTrialStartedAt.plus(PREMIUM_TRIAL_DURATION));
    }

    public boolean isFnbEnabled() {
        return businessType != null && businessType.isActive() && "FNB".equals(businessType.getCode());
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

    public boolean updateBusinessSettings(String name, String logoUrl, String currency, String address,
                                          String phoneNumber, BigDecimal taxRate, BigDecimal serviceChargeRate,
                                          String timeZone) {
        boolean changed = !Objects.equals(this.name, name) || !Objects.equals(this.logoUrl, logoUrl)
                || !Objects.equals(this.currency, currency) || !Objects.equals(this.address, address)
                || !Objects.equals(this.phoneNumber, phoneNumber) || ratesDiffer(this.taxRate, taxRate)
                || ratesDiffer(this.serviceChargeRate, serviceChargeRate) || !Objects.equals(this.timeZone, timeZone);
        if (changed) {
            this.name = name;
            this.logoUrl = logoUrl;
            this.currency = currency;
            this.address = address;
            this.phoneNumber = phoneNumber;
            this.taxRate = taxRate;
            this.serviceChargeRate = serviceChargeRate;
            this.timeZone = timeZone;
        }
        return changed;
    }

    public boolean changeReceiptFooter(String footer) {
        String normalized = footer == null || footer.isBlank() ? null : footer.trim();
        if (normalized != null && normalized.length() > 200)
            throw new InvalidOperationException("Footer struk maksimal 200 karakter.");
        boolean changed = !Objects.equals(receiptFooter, normalized);
        receiptFooter = normalized;
        return changed;
    }

    private boolean ratesDiffer(BigDecimal current, BigDecimal replacement) {
        if (current == null || replacement == null) return current != replacement;
        return current.compareTo(replacement) != 0;
    }

}
