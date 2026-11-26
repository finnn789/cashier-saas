package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.dto.OnboardingMapper;
import org.lite.project.cashiersaas.dto.response.identity.OnboardingStatusResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OnboardingIntent;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.ErrorMessage;
import org.lite.project.cashiersaas.exception.runtime.AuthenticationRequiredException;
import org.lite.project.cashiersaas.exception.runtime.AccessForbiddenException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingIntentService {
    private static final String INTENT_LOCKED_MESSAGE = "Jalur pendaftaran tidak dapat diubah.";

    private final StaffUserRepository staffUserRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public OnboardingStatusResponse choose(AccountPrincipal principal, OnboardingIntent intent) {
        StaffUserEntity user = requireAccount(principal);
        TenantEntity pendingTenant = tenantRepository.findLatestPendingOwnedBy(user.getId()).orElse(null);
        // Once a pending business exists only the OWNER path is valid.
        if (pendingTenant != null && intent != OnboardingIntent.OWNER) {
            throw new OperationConflictException(INTENT_LOCKED_MESSAGE);
        }
        try {
            user.chooseOnboardingIntent(intent);
        } catch (IllegalStateException exception) {
            throw new OperationConflictException(INTENT_LOCKED_MESSAGE);
        }
        return OnboardingMapper.toStatus(user, pendingTenant);
    }

    @Transactional(readOnly = true)
    public OnboardingStatusResponse status(AccountPrincipal principal) {
        StaffUserEntity user = requireAccount(principal);
        return OnboardingMapper.toStatus(user, tenantRepository.findLatestPendingOwnedBy(user.getId()).orElse(null));
    }

    private StaffUserEntity requireAccount(AccountPrincipal principal) {
        StaffUserEntity user = staffUserRepository.findById(principal.getUserId())
                .orElseThrow(AuthenticationRequiredException::new);
        if (user.isDisabled()) {
            throw new AccessForbiddenException(ErrorMessage.ACCOUNT_FORBIDDEN.getMessage());
        }
        return user;
    }
}
