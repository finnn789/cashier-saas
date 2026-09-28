package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.dto.request.identity.LoginRequest;
import org.lite.project.cashiersaas.dto.response.identity.LoginResponse;
import org.lite.project.cashiersaas.dto.response.identity.MerchantOutletChoiceResponse;
import org.lite.project.cashiersaas.dto.IdentityMapper;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidCredentialsException;
import org.lite.project.cashiersaas.exception.ErrorMessage;
import org.lite.project.cashiersaas.exception.runtime.AuthenticationRequiredException;
import org.lite.project.cashiersaas.exception.runtime.AccessForbiddenException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.MerchantSelectionRepository;
import org.lite.project.cashiersaas.shared.AuthenticationConstants;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginService {

    private final StaffUserRepository staffUserRepository;
    private final MembershipOutletAccessRepository accessRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;
    private final MerchantSelectionRepository merchantSelectionRepository;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
        StaffUserEntity staffUser = staffUserRepository.findByNormalizedEmail(normalizedEmail)
                .orElseThrow(InvalidCredentialsException::new);

        if (staffUser.isDisabled() || !passwordEncoder.matches(request.getPassword(), staffUser.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (!staffUser.isPendingProfile() && !staffUser.isActive()) {
            throw new InvalidCredentialsException();
        }
        return toLoginResponse(authSessionService.start(staffUser));
    }

    @Transactional
    public LoginResponse selectMerchant(AccountPrincipal principal, UUID tenantId, UUID outletId) {
        MerchantSelectionRepository.Selection selection = merchantSelectionRepository
                .findAccessible(principal.getUserId(), tenantId, outletId)
                .orElseGet(() -> {
                    requireActiveAccount(principal);
                    throw new ResourceNotFoundException(ErrorMessage.RESOURCE_UNAVAILABLE.getMessage());
                });
        TenantMembershipEntity membership = selection.getMembership();
        StaffUserEntity user = membership.getUser();
        if (user.isPendingProfile()) {
            throw new OperationConflictException(ErrorMessage.PROFILE_INCOMPLETE.getMessage());
        }
        if (!user.isActive()) {
            throw new AccessForbiddenException(ErrorMessage.ACCOUNT_FORBIDDEN.getMessage());
        }
        var outlet = selection.getOutlet();
        if (!outlet.isActive()) {
            throw new ResourceNotFoundException(ErrorMessage.RESOURCE_UNAVAILABLE.getMessage());
        }
        if (!membership.getTenant().isFnbEnabled()) {
            throw new ResourceNotFoundException(ErrorMessage.RESOURCE_UNAVAILABLE.getMessage());
        }
        if (!membership.getTenant().isActive()) {
            throw new OperationConflictException(ErrorMessage.MERCHANT_INACTIVE.getMessage());
        }
        var subscription = selection.getSubscription();
        if (subscription == null || !subscription.isActiveAt(Instant.now())) {
            throw new OperationConflictException(ErrorMessage.SUBSCRIPTION_INACTIVE.getMessage());
        }
        return toLoginResponse(authSessionService.selectMerchant(principal, membership, outlet, subscription));
    }

    @Transactional(readOnly = true)
    public List<MerchantOutletChoiceResponse> listChoices(AccountPrincipal principal) {
        StaffUserEntity user = requireActiveAccount(principal);
        return accessRepository.listActiveChoices(user.getId(), Instant.now()).stream()
                .map(IdentityMapper::toChoice).toList();
    }

    private StaffUserEntity requireActiveAccount(AccountPrincipal principal) {
        StaffUserEntity user = staffUserRepository.findById(principal.getUserId())
                .orElseThrow(AuthenticationRequiredException::new);
        if (user.isPendingProfile()) {
            throw new OperationConflictException(ErrorMessage.PROFILE_INCOMPLETE.getMessage());
        }
        if (!user.isActive()) {
            throw new AccessForbiddenException(ErrorMessage.ACCOUNT_FORBIDDEN.getMessage());
        }
        return user;
    }

    private LoginResponse toLoginResponse(AuthSessionService.IssuedTokens tokens) {
        LoginResponse response = new LoginResponse();
        response.setAccessToken(tokens.getAccessToken());
        response.setTokenType(AuthenticationConstants.BEARER_SCHEME);
        response.setExpiresAt(tokens.getExpiresAt());
        response.setRole(tokens.getRole());
        response.setTenantId(tokens.getTenantId());
        response.setOutletId(tokens.getOutletId());
        response.setNextStep(tokens.getNextStep());
        response.setRefreshToken(tokens.getRefreshToken());
        return response;
    }
}
