package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.OnboardingMapper;
import org.lite.project.cashiersaas.dto.response.identity.CashierInvitationClaimResponse;
import org.lite.project.cashiersaas.dto.response.identity.CashierInvitationResponse;
import org.lite.project.cashiersaas.entity.shared.identity.CashierInvitationEntity;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.enums.OnboardingIntent;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.enums.Role;

import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.exception.runtime.AuthenticationRequiredException;
import org.lite.project.cashiersaas.repository.CashierInvitationRepository;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.repository.MerchantReferenceRepository;
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CashierInvitationService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MerchantReferenceRepository references;
    private final CashierInvitationRepository invitationRepository;
    private final StaffUserRepository userRepository;
    private final TenantMembershipRepository membershipRepository;
    private final MembershipOutletAccessRepository outletAccessRepository;
    private final PlanEntitlementService planEntitlementService;
    private final MerchantAccessGuard merchantAccessGuard;
    private final MemberSyncPublisher memberSyncPublisher;

    @Transactional
    public CashierInvitationResponse issue(TenantPrincipal principal, UUID outletId, String email) {
        merchantAccessGuard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        CashierInvitationEntity invitation = CashierInvitationEntity.issue(
                references.tenant(principal.getTenantId()), references.membership(principal.getMembershipId()),
                references.outlet(outletId), email.trim().toLowerCase(Locale.ROOT), digest(code),
                Instant.now().plus(24, ChronoUnit.HOURS));
        invitationRepository.persist(invitation);
        return OnboardingMapper.toInvitation(invitation, code);
    }

    @Transactional
    public CashierInvitationClaimResponse claim(AccountPrincipal principal, String code) {
        if (code == null || code.length() != 43) {
            throw new ResourceNotFoundException("Undangan tidak ditemukan atau tidak dapat digunakan.");
        }
        CashierInvitationEntity invitation = invitationRepository.findByDigest(digest(code))
                .orElseThrow(() -> new ResourceNotFoundException("Undangan tidak ditemukan atau tidak dapat digunakan."));
        invitationRepository.lockAndRefresh(invitation);
        StaffUserEntity user = userRepository.findById(principal.getUserId())
                .orElseThrow(AuthenticationRequiredException::new);
        if (!user.getEmail().equals(invitation.getInvitedEmail())
                || user.getOnboardingIntent() != OnboardingIntent.CASHIER_ONLY
                || (!user.isPendingProfile() && !user.isActive())
                || !invitation.getTenant().isFnbEnabled()) {
            throw new ResourceNotFoundException("Undangan tidak ditemukan atau tidak dapat digunakan.");
        }
        if (invitation.isClaimedBy(user)) {
            // Idempotent re-claim: re-publish so an earlier failed Ai-services sync is healed.
            memberSyncPublisher.memberChanged(invitation.getTenant(), user, Role.CASHIER, StatusConstants.ACTIVE);
            return OnboardingMapper.toClaim(invitation);
        }
        if (invitation.getClaimedAt() != null || !invitation.getExpiresAt().isAfter(Instant.now())
                || !invitation.getTenant().isActive() || !invitation.getOutlet().isActive()
                || !invitation.getInvitedByMembership().isActive()
                || invitation.getInvitedByMembership().getRole() != Role.OWNERMERCHANT
                // Issued during PRO but claimed after a downgrade: refused like any unusable invitation (no 402).
                || planEntitlementService.effectivePlan(invitation.getTenant().getId(), Instant.now()).compareTo(PlanCode.PRO) < 0) {
            throw new ResourceNotFoundException("Undangan tidak ditemukan atau tidak dapat digunakan.");
        }
        TenantMembershipEntity membership = membershipRepository.findActive(user.getId(), invitation.getTenant().getId())
                .orElse(null);
        if (membership != null && membership.getRole() != Role.CASHIER) {
            throw new OperationConflictException("Akun memiliki peran lain pada usaha ini.");
        }
        if (membership == null) {
            membership = TenantMembershipEntity.grantCashier(user, invitation.getTenant());
            membershipRepository.persist(membership);
        }
        if (outletAccessRepository.findAccess(invitation.getTenant().getId(), membership.getId(),
                invitation.getOutlet().getId()).isEmpty()) {
            outletAccessRepository.persist(MembershipOutletAccessEntity.grant(membership, invitation.getOutlet()));
        }
        invitation.claim(user, Instant.now());
        if (user.isPendingProfile()) {
            user.activateInvitedCashier();
        }
        memberSyncPublisher.memberChanged(invitation.getTenant(), user, Role.CASHIER, StatusConstants.ACTIVE);
        return OnboardingMapper.toClaim(invitation);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
