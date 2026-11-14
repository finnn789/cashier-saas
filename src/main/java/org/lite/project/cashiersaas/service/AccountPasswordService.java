package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.dto.request.identity.ChangePasswordRequest;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.exception.runtime.AuthenticationRequiredException;
import org.lite.project.cashiersaas.exception.runtime.InvalidCredentialsException;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.AuthSessionRepository;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountPasswordService {
    private final StaffUserRepository staffUserRepository;
    private final AuthSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;

    /** Changes the caller's own password and revokes every other session; the caller's session stays valid. */
    @Transactional
    public void change(UUID userId, UUID keepSessionId, ChangePasswordRequest request) {
        StaffUserEntity user = staffUserRepository.findById(userId).orElseThrow(AuthenticationRequiredException::new);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (request.getNewPassword().equals(request.getCurrentPassword())) {
            throw new InvalidOperationException("Password baru harus berbeda.");
        }
        user.changePasswordHash(passwordEncoder.encode(request.getNewPassword()));
        sessionRepository.revokeForUserExcept(userId, keepSessionId, Instant.now());
    }
}
