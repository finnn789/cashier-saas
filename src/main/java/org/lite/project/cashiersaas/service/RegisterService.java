package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.component.JwtTokenService;
import org.lite.project.cashiersaas.configuration.JwtProperties;
import org.lite.project.cashiersaas.dto.request.identity.BusinessProfileRequest;
import org.lite.project.cashiersaas.dto.request.identity.RegisterUserRequest;
import org.lite.project.cashiersaas.dto.response.identity.RegisterOwnerResponse;
import org.lite.project.cashiersaas.dto.response.identity.RegisterUserResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.BusinessProfileAlreadyCompletedException;
import org.lite.project.cashiersaas.exception.runtime.DataIntegrityViolationRuntimeException;
import org.lite.project.cashiersaas.exception.runtime.EmailAlreadyRegisteredException;
import org.lite.project.cashiersaas.exception.runtime.InvalidCredentialsException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.repository.TenantRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RegisterService {

    private final StaffUserRepository staffUserRepository;
    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final JwtTokenService jwtTokenService;

    @Transactional
    public RegisterUserResponse register(RegisterUserRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (staffUserRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
            throw new EmailAlreadyRegisteredException();
        }

        StaffUserEntity staffUser = StaffUserEntity.registerWithPassword(
                normalizedEmail,
                passwordEncoder.encode(request.getPassword()),
                request.getFullName().trim());
        persistStaffUserOrTranslateIntegrityViolation(staffUser);

        Instant expiresAt = Instant.now().plus(jwtProperties.getAccessTokenTtl());
        String accessToken = jwtTokenService.generateAccountAccessToken(staffUser.getId(), staffUser.getEmail());
        return RegisterUserResponse.fromAccountRegistration(accessToken, expiresAt);
    }

    @Transactional
    public RegisterOwnerResponse completeBusinessProfile(AccountPrincipal accountPrincipal, BusinessProfileRequest request) {
        StaffUserEntity staffUser = staffUserRepository.findById(accountPrincipal.getUserId())
                .orElseThrow(InvalidCredentialsException::new);
        if (!staffUser.isPendingProfile()) {
            throw new BusinessProfileAlreadyCompletedException();
        }

        TenantEntity tenant = TenantEntity.createFromBusinessProfile(request.getBusinessName().trim());
        tenantRepository.persist(tenant);

        TenantMembershipEntity membership = TenantMembershipEntity.grantOwner(staffUser, tenant);
        tenantMembershipRepository.persist(membership);
        staffUser.completeBusinessProfile();

        Instant expiresAt = Instant.now().plus(jwtProperties.getAccessTokenTtl());
        String accessToken = jwtTokenService.generateAccessToken(
                staffUser.getId(), tenant.getId(), staffUser.getEmail(), List.of(membership.getRole()));
        return RegisterOwnerResponse.fromBusinessProfile(
                accessToken,
                expiresAt,
                tenant.getId(),
                membership.getRole().name());
    }

    private void persistStaffUserOrTranslateIntegrityViolation(StaffUserEntity staffUser) {
        try {
            staffUserRepository.persistAndFlush(staffUser);
        } catch (DataIntegrityViolationException exception) {
            throw new DataIntegrityViolationRuntimeException(exception);
        }
    }
}
