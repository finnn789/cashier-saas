package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.JwtTokenService;
import org.lite.project.cashiersaas.configuration.JwtProperties;
import org.lite.project.cashiersaas.dto.request.identity.RegisterUserRequest;
import org.lite.project.cashiersaas.dto.response.identity.RegisterUserResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.exception.runtime.DataIntegrityViolationRuntimeException;
import org.lite.project.cashiersaas.exception.runtime.EmailAlreadyRegisteredException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RegisterService {

    private final StaffUserRepository staffUserRepository;
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

    private void persistStaffUserOrTranslateIntegrityViolation(StaffUserEntity staffUser) {
        try {
            staffUserRepository.persistAndFlush(staffUser);
        } catch (DataIntegrityViolationException exception) {
            throw new DataIntegrityViolationRuntimeException(exception);
        }
    }
}
