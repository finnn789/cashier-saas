package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.dto.request.identity.ChangePasswordRequest;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidCredentialsException;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.AuthSessionRepository;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.service.AccountPasswordService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class AccountPasswordServiceTest {
    @Mock private StaffUserRepository users;
    @Mock private AuthSessionRepository sessions;
    @Mock private PasswordEncoder passwordEncoder;
    @InjectMocks private AccountPasswordService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final StaffUserEntity user = StaffUserEntity.createCashier("kasir@example.com", "stored-hash", "Kasir", null);

    private ChangePasswordRequest request(String current, String next) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword(current);
        request.setNewPassword(next);
        return request;
    }

    private void userFound() {
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void wrongCurrentPasswordIsRejectedWithoutChanges() {
        userFound();
        when(passwordEncoder.matches("wrong-password", "stored-hash")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> service.change(userId, sessionId, request("wrong-password", "new-password-1")));

        assertEquals("stored-hash", user.getPasswordHash());
        verifyNoInteractions(sessions);
    }

    @Test
    void samePasswordIsRejected() {
        userFound();
        when(passwordEncoder.matches("same-password", "stored-hash")).thenReturn(true);

        InvalidOperationException ex = assertThrows(InvalidOperationException.class,
                () -> service.change(userId, sessionId, request("same-password", "same-password")));

        assertEquals("Password baru harus berbeda.", ex.getMessage());
        verify(passwordEncoder, never()).encode(any());
        verifyNoInteractions(sessions);
    }

    @Test
    void successStoresTheNewHashAndRevokesOtherSessionsKeepingTheCallersOwn() {
        userFound();
        when(passwordEncoder.matches("old-password", "stored-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password-1")).thenReturn("new-hash");

        service.change(userId, sessionId, request("old-password", "new-password-1"));

        assertEquals("new-hash", user.getPasswordHash());
        verify(sessions).revokeForUserExcept(eq(userId), eq(sessionId), any());
    }
}
