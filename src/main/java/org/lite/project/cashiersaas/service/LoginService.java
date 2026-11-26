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
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.shared.AuthenticationConstants;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginService {

    private final StaffUserRepository staffUserRepository;
    private final MembershipOutletAccessRepository accessRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;
    private final MerchantSelectionRepository merchantSelectionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanEntitlementService planEntitlementService;

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
        LoginResponse response = toLoginResponse(authSessionService.start(staffUser));
        response.setPlatformAdmin(staffUser.isPlatformAdmin());
        return response;
    }

    /** SUPERADMIN entry: an active account with the platform flag; anyone else gets the same 404 as an unknown resource. */
    @Transactional(readOnly = true)
    public LoginResponse startPlatformSession(AccountPrincipal principal) {
        StaffUserEntity user = staffUserRepository.findById(principal.getUserId())
                .filter(value -> value.isActive() && value.isPlatformAdmin())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorMessage.RESOURCE_UNAVAILABLE.getMessage()));
        LoginResponse response = toLoginResponse(authSessionService.issuePlatform(principal, user));
        response.setPlatformAdmin(true);
        return response;
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
        planEntitlementService.requireOutletUsable(tenantId, outletId, Instant.now());
        // AuthSessionService checks the role-based entitlement and refuses with SUBSCRIPTION_INACTIVE.
        return toLoginResponse(authSessionService.selectMerchant(principal, membership, outlet,
                subscriptionRepository.findActiveAt(tenantId, Instant.now())));
    }

    @Transactional(readOnly = true)
    public List<MerchantOutletChoiceResponse> listChoices(AccountPrincipal principal) {
        StaffUserEntity user = requireActiveAccount(principal);
        Instant now = Instant.now();
        Map<UUID, Set<UUID>> usableByTenant = new HashMap<>();
        return accessRepository.listActiveChoices(user.getId(), now).stream()
                .filter(choice -> usableByTenant.computeIfAbsent(choice.getTenantId(),
                        tenantId -> Set.copyOf(planEntitlementService.usableOutletIds(tenantId, now)))
                        .contains(choice.getOutletId()))
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
