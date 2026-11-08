package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.dto.FreeTierMapper;
import org.lite.project.cashiersaas.dto.request.identity.BusinessProfileRequest;
import org.lite.project.cashiersaas.dto.response.identity.FreeTierStartResponse;
import org.lite.project.cashiersaas.dto.response.identity.RegisterOwnerResponse;
import org.lite.project.cashiersaas.entity.shared.billing.BusinessTypeEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.enums.OnboardingIntent;
import org.lite.project.cashiersaas.exception.ErrorMessage;
import org.lite.project.cashiersaas.exception.runtime.AuthenticationRequiredException;
import org.lite.project.cashiersaas.exception.runtime.AccessForbiddenException;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.BusinessTypeRepository;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.repository.TenantRepository;
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FreeTierOnboardingService {
    private final StaffUserRepository staffUserRepository;
    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository membershipRepository;
    private final BusinessTypeRepository businessTypeRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OutletRepository outletRepository;
    private final MembershipOutletAccessRepository accessRepository;
    private final AuthSessionService authSessionService;
    private final MemberSyncPublisher memberSyncPublisher;

    @Transactional
    public FreeTierStartResponse startFreeTier(AccountPrincipal principal, String idempotencyKey,
                                               String businessTypeCode) {
        String key = validatedKey(idempotencyKey);
        String typeCode = businessTypeCode == null ? "" : businessTypeCode.trim().toUpperCase(Locale.ROOT);
        if (!"FNB".equals(typeCode)) {
            throw new InvalidOperationException("Tipe usaha belum tersedia untuk Freetier.");
        }
        String fingerprint = sha256(typeCode + "|FREETIER|1");
        StaffUserEntity owner = staffUserRepository.findById(principal.getUserId())
                .orElseThrow(AuthenticationRequiredException::new);
        if (owner.isDisabled()) {
            throw new AccessForbiddenException(ErrorMessage.ACCOUNT_FORBIDDEN.getMessage());
        }
        TenantEntity existing = tenantRepository.findByOnboardingKey(principal.getUserId(), key).orElse(null);
        if (existing != null) {
            if (!fingerprint.equals(existing.getOnboardingRequestFingerprint())) {
                throw new OperationConflictException("Idempotency key telah dipakai untuk permintaan berbeda.");
            }
            return FreeTierMapper.toStartResponse(existing);
        }

        if (!owner.isPendingProfile()) {
            throw new OperationConflictException(ErrorMessage.ONBOARDING_STAGE_INVALID.getMessage());
        }
        if (owner.getOnboardingIntent() != OnboardingIntent.OWNER) {
            throw new OperationConflictException(ErrorMessage.OWNER_INTENT_REQUIRED.getMessage());
        }
        BusinessTypeEntity businessType = businessTypeRepository.findActiveByCode(typeCode)
                .orElseThrow(() -> new OperationConflictException("Jenis bisnis F&B belum tersedia."));

        TenantEntity tenant = TenantEntity.startFreeTier(owner, businessType, key, fingerprint);
        tenantRepository.persist(tenant);
        membershipRepository.persist(TenantMembershipEntity.grantOwner(owner, tenant));
        subscriptionRepository.persist(SubscriptionEntity.activateFreeTier(tenant));
        return FreeTierMapper.toStartResponse(tenant);
    }

    @Transactional
    public RegisterOwnerResponse complete(AccountPrincipal principal, UUID tenantId, BusinessProfileRequest request) {
        TenantEntity tenant = tenantRepository.findOwnedOnboarding(tenantId, principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Usaha tidak ditemukan atau tidak dapat diakses."));
        if (!tenant.isFnbEnabled()) {
            throw new ResourceNotFoundException("Usaha tidak ditemukan atau tidak dapat diakses.");
        }
        TenantMembershipEntity ownerMembership = membershipRepository.findActive(principal.getUserId(), tenantId)
                .filter(membership -> membership.getRole() == Role.OWNERMERCHANT)
                .orElseThrow(ResourceNotFoundException::new);
        SubscriptionEntity subscription = subscriptionRepository.findActiveForTenant(tenantId)
                .orElseThrow(() -> new OperationConflictException(ErrorMessage.SUBSCRIPTION_INACTIVE.getMessage()));
        String timeZone = validatedTimeZone(request.getTimeZone());
        String businessName = request.getBusinessName().trim();
        String outletName = request.getOutletName().trim();
        if (businessName.isEmpty() || outletName.isEmpty()) {
            throw new InvalidOperationException("Nama bisnis dan outlet wajib diisi.");
        }
        String address = trimmedOrNull(request.getAddress());
        String phoneNumber = trimmedOrNull(request.getPhoneNumber());
        if (!subscription.isActiveAt(Instant.now())) {
            throw new OperationConflictException("Langganan tidak aktif.");
        }
        if (tenant.isActive()) {
            OutletEntity existingOutlet = accessRepository.findFirstActiveForMembership(tenantId,
                    ownerMembership.getId()).map(MembershipOutletAccessEntity::getOutlet)
                    .orElseThrow(ResourceNotFoundException::new);
            if (!matchesCompletedProfile(tenant, existingOutlet, businessName, outletName, timeZone,
                    address, phoneNumber)) {
                throw new OperationConflictException("Profil usaha telah diselesaikan dengan data berbeda.");
            }
            // Idempotent re-complete: re-publish so an earlier failed Ai-services sync is healed.
            memberSyncPublisher.memberChanged(tenant, ownerMembership.getUser(), Role.OWNERMERCHANT);
            AuthSessionService.IssuedTokens tokens = authSessionService.selectMerchant(principal,
                    ownerMembership, existingOutlet, subscription);
            return FreeTierMapper.toOwnerResponse(tokens.getAccessToken(), tokens.getExpiresAt(),
                    ownerMembership, existingOutlet);
        }
        if (!StatusConstants.PENDING_PROFILE.equals(tenant.getStatus())) {
            throw new OperationConflictException("Merchant tidak dapat menyelesaikan profil pada status ini.");
        }
        OutletEntity outlet = OutletEntity.open(tenant, outletName, timeZone, address, phoneNumber);
        tenant.completeFreeTierProfile(businessName, address, phoneNumber);
        outletRepository.persist(outlet);
        accessRepository.persist(MembershipOutletAccessEntity.grant(ownerMembership, outlet));
        StaffUserEntity owner = ownerMembership.getUser();
        if (owner.getOnboardingIntent() != OnboardingIntent.OWNER) {
            throw new ResourceNotFoundException();
        }
        if (owner.isPendingProfile()) {
            owner.completeBusinessProfile();
        }
        memberSyncPublisher.memberChanged(tenant, owner, Role.OWNERMERCHANT);
        AuthSessionService.IssuedTokens tokens = authSessionService.selectMerchant(principal, ownerMembership, outlet,
                subscription);
        return FreeTierMapper.toOwnerResponse(tokens.getAccessToken(), tokens.getExpiresAt(), ownerMembership, outlet);
    }

    private boolean matchesCompletedProfile(TenantEntity tenant, OutletEntity outlet, String businessName,
                                            String outletName, String timeZone, String address,
                                            String phoneNumber) {
        return tenant.getName().equals(businessName)
                && Objects.equals(tenant.getAddress(), address)
                && Objects.equals(tenant.getPhoneNumber(), phoneNumber)
                && outlet.getName().equals(outletName)
                && outlet.getTimeZone().equals(timeZone)
                && Objects.equals(outlet.getAddress(), address)
                && Objects.equals(outlet.getPhoneNumber(), phoneNumber);
    }

    private String validatedKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InvalidOperationException("Idempotency-Key wajib diisi, maksimal 128 karakter.");
        }
        return key.trim();
    }

    private String validatedTimeZone(String value) {
        try {
            return ZoneId.of(value.trim()).getId();
        } catch (ZoneRulesException | IllegalArgumentException exception) {
            throw new InvalidOperationException("Zona waktu outlet tidak valid.");
        }
    }

    private String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
