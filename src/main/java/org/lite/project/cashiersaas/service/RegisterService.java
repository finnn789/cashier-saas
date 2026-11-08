package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.dto.request.identity.RegisterUserRequest;
import org.lite.project.cashiersaas.dto.response.identity.RegisterUserResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.exception.runtime.DataIntegrityViolationRuntimeException;
import org.lite.project.cashiersaas.exception.runtime.EmailAlreadyRegisteredException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.shared.PhoneNumberNormalizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RegisterService {

    private final StaffUserRepository staffUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;

    @Transactional
    public RegisterUserResponse register(RegisterUserRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String normalizedPhoneNumber = PhoneNumberNormalizer.normalize(request.getPhoneNumber());
        if (staffUserRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
            throw new EmailAlreadyRegisteredException();
        }

        StaffUserEntity staffUser = StaffUserEntity.registerWithPassword(
                normalizedEmail,
                passwordEncoder.encode(request.getPassword()),
                request.getFullName().trim(),
                normalizedPhoneNumber);
        persistStaffUserOrTranslateIntegrityViolation(staffUser);

        AuthSessionService.IssuedTokens tokens = authSessionService.start(staffUser);
        RegisterUserResponse response = RegisterUserResponse.fromAccountRegistration(
                tokens.getAccessToken(), tokens.getExpiresAt());
        response.setRefreshToken(tokens.getRefreshToken());
        return response;
    }

    private void persistStaffUserOrTranslateIntegrityViolation(StaffUserEntity staffUser) {
        try {
            staffUserRepository.persistAndFlush(staffUser);
        } catch (DataIntegrityViolationException exception) {
            throw new DataIntegrityViolationRuntimeException(exception);
        }
    }
}
