package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.IdentityMapper;
import org.lite.project.cashiersaas.dto.response.identity.MerchantStaffPasswordResetResponse;
import org.lite.project.cashiersaas.dto.response.identity.MerchantStaffResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.AuthSessionRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MerchantStaffService {
    private static final SecureRandom RANDOM = new SecureRandom();
    // No 0, O, 1, l or I: the password is read from a screen and typed by hand.
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final int TEMPORARY_PASSWORD_LENGTH = 12;

    private final MerchantAccessGuard merchantAccessGuard;
    private final TenantMembershipRepository membershipRepository;
    private final AuthSessionRepository sessionRepository;
    private final AuditLogRepository audit;
    private final MemberSyncPublisher memberSyncPublisher;
    private final PasswordEncoder passwordEncoder;
    private final PlanEntitlementService planEntitlementService;

    @Transactional(readOnly = true)
    public List<MerchantStaffResponse> list(TenantPrincipal principal, UUID outletId) {
        merchantAccessGuard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        return membershipRepository.listCashiersForOutlet(principal.getTenantId(), outletId).stream()
                .map(IdentityMapper::toStaff).toList();
    }

    @Transactional
    public MerchantStaffResponse deactivate(TenantPrincipal principal, UUID outletId, UUID userId) {
        MerchantAccessContext context = merchantAccessGuard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        TenantMembershipEntity target = requireTarget(principal, outletId, userId);
        if (target.deactivate()) {
            sessionRepository.revokeForMembership(target.getId(), Instant.now());
            recordAudit(context, AuditLogEntity.Action.STAFF_DEACTIVATED, userId);
            memberSyncPublisher.memberChanged(context.getTenant(), target.getUser(), target.getRole(),
                    StatusConstants.DISABLED);
        }
        return IdentityMapper.toStaff(target);
    }

    @Transactional
    public MerchantStaffResponse reactivate(TenantPrincipal principal, UUID outletId, UUID userId) {
        MerchantAccessContext context = merchantAccessGuard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        TenantMembershipEntity target = requireTarget(principal, outletId, userId);
        if (target.reactivate()) {
            recordAudit(context, AuditLogEntity.Action.STAFF_REACTIVATED, userId);
            memberSyncPublisher.memberChanged(context.getTenant(), target.getUser(), target.getRole(),
                    StatusConstants.ACTIVE);
        }
        return IdentityMapper.toStaff(target);
    }

    @Transactional
    public MerchantStaffPasswordResetResponse resetPassword(TenantPrincipal principal, UUID outletId, UUID userId) {
        MerchantAccessContext context = merchantAccessGuard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        TenantMembershipEntity target = requireTarget(principal, outletId, userId);
        if (membershipRepository.hasActiveMembershipOutsideTenant(userId, principal.getTenantId())) {
            throw new OperationConflictException(
                    "Akun kasir ini terhubung ke bisnis lain; minta kasir mengganti password sendiri.");
        }
        String temporaryPassword = generateTemporaryPassword();
        target.getUser().changePasswordHash(passwordEncoder.encode(temporaryPassword));
        sessionRepository.revokeForUserExcept(userId, null, Instant.now());
        recordAudit(context, AuditLogEntity.Action.STAFF_PASSWORD_RESET, userId);
        MerchantStaffPasswordResetResponse response = new MerchantStaffPasswordResetResponse();
        response.setUserId(userId);
        response.setTemporaryPassword(temporaryPassword);
        return response;
    }

    // Other tenants, owners and users without access to this outlet all look the same: not found.
    private TenantMembershipEntity requireTarget(TenantPrincipal principal, UUID outletId, UUID userId) {
        return membershipRepository.findCashierForOutlet(principal.getTenantId(), outletId, userId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private void recordAudit(MerchantAccessContext context, AuditLogEntity.Action action, UUID targetUserId) {
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(),
                context.getMembership().getUser(), action, targetUserId, null, null));
    }

    private static String generateTemporaryPassword() {
        char[] password = new char[TEMPORARY_PASSWORD_LENGTH];
        String all = LETTERS + DIGITS;
        password[0] = LETTERS.charAt(RANDOM.nextInt(LETTERS.length()));
        password[1] = DIGITS.charAt(RANDOM.nextInt(DIGITS.length()));
        for (int i = 2; i < password.length; i++) {
            password[i] = all.charAt(RANDOM.nextInt(all.length()));
        }
        for (int i = password.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char swap = password[i];
            password[i] = password[j];
            password[j] = swap;
        }
        return new String(password);
    }
}
