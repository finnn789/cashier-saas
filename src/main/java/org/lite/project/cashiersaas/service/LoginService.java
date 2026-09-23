package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.JwtTokenService;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.configuration.JwtProperties;
import org.lite.project.cashiersaas.dto.request.identity.LoginRequest;
import org.lite.project.cashiersaas.dto.response.identity.LoginResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidCredentialsException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
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
    private final TenantMembershipRepository tenantMembershipRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final JwtTokenService jwtTokenService;

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
        StaffUserEntity staffUser = staffUserRepository.findByNormalizedEmail(normalizedEmail)
                .orElseThrow(InvalidCredentialsException::new);

        if (staffUser.isDisabled() || !passwordEncoder.matches(request.getPassword(), staffUser.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (staffUser.isPendingProfile()) {
            return accountLoginResponse(staffUser);
        }

        if (!staffUser.isActive()) {
            throw new InvalidCredentialsException();
        }

        List<TenantMembershipEntity> memberships = tenantMembershipRepository.findActiveByStaffUserId(staffUser.getId());
        if (memberships.size() != 1) {
            return accountLoginResponse(staffUser);
        }

        TenantMembershipEntity membership = memberships.get(0);
        if (!membership.getTenant().isActive() || !subscriptionRepository.findActiveForTenant(membership.getTenant().getId())
                .map(subscription -> subscription.isActiveAt(Instant.now())).orElse(false)) {
            return accountLoginResponse(staffUser);
        }
        return merchantLoginResponse(staffUser, membership);
    }

    @Transactional(readOnly = true)
    public LoginResponse selectMerchant(AccountPrincipal principal, UUID tenantId) {
        StaffUserEntity user = staffUserRepository.findById(principal.getUserId())
                .filter(StaffUserEntity::isActive)
                .orElseThrow(InvalidCredentialsException::new);
        TenantMembershipEntity membership = tenantMembershipRepository.findActive(user.getId(), tenantId)
                .filter(value -> value.getTenant().isActive())
                .filter(value -> subscriptionRepository.findActiveForTenant(tenantId)
                        .map(subscription -> subscription.isActiveAt(Instant.now())).orElse(false))
                .orElseThrow(InvalidCredentialsException::new);
        return merchantLoginResponse(user, membership);
    }

    private LoginResponse merchantLoginResponse(StaffUserEntity staffUser, TenantMembershipEntity membership) {
        Instant expiresAt = Instant.now().plus(jwtProperties.getAccessTokenTtl());
        LoginResponse response = new LoginResponse();
        response.setAccessToken(jwtTokenService.generateAccessToken(staffUser.getId(), membership.getTenant().getId(), staffUser.getEmail(), List.of(membership.getRole())));
        response.setTokenType("Bearer");
        response.setExpiresAt(expiresAt);
        response.setRole(membership.getRole());
        response.setTenantId(membership.getTenant().getId());
        return response;
    }

    private LoginResponse accountLoginResponse(StaffUserEntity staffUser) {
        Instant expiresAt = Instant.now().plus(jwtProperties.getAccessTokenTtl());
        LoginResponse response = new LoginResponse();
        response.setAccessToken(jwtTokenService.generateAccountAccessToken(staffUser.getId(), staffUser.getEmail()));
        response.setTokenType("Bearer");
        response.setExpiresAt(expiresAt);
        return response;
    }
}
