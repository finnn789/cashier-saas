package org.lite.project.cashiersaas.unit.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.dto.request.identity.RegisterUserRequest;
import org.lite.project.cashiersaas.dto.response.identity.RegisterUserResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.exception.runtime.EmailAlreadyRegisteredException;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.service.AuthSessionService;
import org.lite.project.cashiersaas.service.RegisterService;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {
    @Mock private StaffUserRepository staffUserRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthSessionService authSessionService;
    @InjectMocks private RegisterService service;

    @Test
    void registerStoresTheNormalizedPhoneNumber() {
        stubSuccessfulRegistration();

        RegisterUserResponse response = service.register(request("0812-3456-7890"));

        ArgumentCaptor<StaffUserEntity> captor = ArgumentCaptor.forClass(StaffUserEntity.class);
        verify(staffUserRepository).persistAndFlush(captor.capture());
        StaffUserEntity saved = captor.getValue();
        assertEquals("6281234567890", saved.getPhoneNumber());
        assertEquals("new.user@example.com", saved.getEmail());
        assertEquals("New User", saved.getFullName());
        assertEquals("access-token", response.getAccessToken());
    }

    @Test
    void registerNormalizesInternationalFormats() {
        stubSuccessfulRegistration();

        service.register(request("+62 812-3456-7890"));

        ArgumentCaptor<StaffUserEntity> captor = ArgumentCaptor.forClass(StaffUserEntity.class);
        verify(staffUserRepository).persistAndFlush(captor.capture());
        assertEquals("6281234567890", captor.getValue().getPhoneNumber());
    }

    @Test
    void registerRejectsAnInvalidPhoneNumberBeforeTouchingPersistence() {
        InvalidOperationException exception = assertThrows(InvalidOperationException.class,
                () -> service.register(request("0812-345-678a")));

        assertEquals("Nomor telepon tidak valid.", exception.getMessage());
        assertFalse(exception.getMessage().contains("678a"), "The rejected phone must not be echoed");
        verifyNoInteractions(staffUserRepository, passwordEncoder, authSessionService);
    }

    @Test
    void registerRejectsAMissingPhoneNumber() {
        assertThrows(InvalidOperationException.class, () -> service.register(request(null)));
        assertThrows(InvalidOperationException.class, () -> service.register(request("   ")));

        verifyNoInteractions(staffUserRepository, passwordEncoder, authSessionService);
    }

    @Test
    void registerWithAValidPhoneStillRejectsAnAlreadyRegisteredEmail() {
        when(staffUserRepository.findByNormalizedEmail("new.user@example.com"))
                .thenReturn(Optional.of(StaffUserEntity.registerWithPassword(
                        "new.user@example.com", "hash", "Existing", "6281234567890")));

        assertThrows(EmailAlreadyRegisteredException.class, () -> service.register(request("081234567890")));

        verify(staffUserRepository, never()).persistAndFlush(any(StaffUserEntity.class));
    }

    @Test
    void requestValidationRequiresPhoneNumber() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertEquals(Set.of(), phoneViolations(validator, cleanRequest("081234567890")));
            assertEquals(Set.of("Nomor telepon wajib diisi."), phoneViolations(validator, cleanRequest(null)));
            assertEquals(Set.of("Nomor telepon wajib diisi."), phoneViolations(validator, cleanRequest("  ")));
            assertEquals(Set.of("Nomor telepon maksimal 32 karakter."),
                    phoneViolations(validator, cleanRequest("0".repeat(33))));
        }
    }

    private static Set<String> phoneViolations(Validator validator, RegisterUserRequest request) {
        Set<String> messages = new HashSet<>();
        for (ConstraintViolation<RegisterUserRequest> violation : validator.validate(request)) {
            assertEquals("phoneNumber", violation.getPropertyPath().toString(),
                    "Only the phone number should be invalid");
            messages.add(violation.getMessage());
        }
        return messages;
    }

    private void stubSuccessfulRegistration() {
        when(staffUserRepository.findByNormalizedEmail("new.user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Passw0rd!23")).thenReturn("encoded-hash");
        when(authSessionService.start(any(StaffUserEntity.class))).thenReturn(new AuthSessionService.IssuedTokens(
                "access-token", Instant.parse("2030-01-01T00:00:00Z"), "refresh-token",
                "COMPLETE_ONBOARDING", null, null, null));
    }

    /** Request whose other fields already satisfy Bean Validation (no padding), for validation-only tests. */
    private static RegisterUserRequest cleanRequest(String phoneNumber) {
        RegisterUserRequest request = request(phoneNumber);
        request.setFullName("New User");
        request.setEmail("new.user@example.com");
        return request;
    }

    private static RegisterUserRequest request(String phoneNumber) {
        RegisterUserRequest request = new RegisterUserRequest();
        request.setFullName("  New User ");
        request.setEmail(" New.User@Example.com ");
        request.setPhoneNumber(phoneNumber);
        request.setPassword("Passw0rd!23");
        return request;
    }
}
