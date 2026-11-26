package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.PlatformPrincipal;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.service.PlatformAccessGuard;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlatformAccessGuardTest {
    private final PlatformPrincipal principal = new PlatformPrincipal(UUID.randomUUID(), "admin@example.com", UUID.randomUUID());

    @Mock
    private StaffUserRepository staffUsers;
    @InjectMocks
    private PlatformAccessGuard guard;

    @Test
    void returnsTheActiveAccountWithThePlatformFlag() {
        StaffUserEntity admin = user(true, true);
        when(staffUsers.findById(principal.getUserId())).thenReturn(Optional.of(admin));

        assertSame(admin, guard.require(principal));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN_USER", "INACTIVE_ADMIN", "ACTIVE_WITHOUT_FLAG"})
    void hidesThePanelBehindA404ForEveryOtherAccount(String account) {
        when(staffUsers.findById(principal.getUserId())).thenReturn(switch (account) {
            case "UNKNOWN_USER" -> Optional.empty();
            case "INACTIVE_ADMIN" -> Optional.of(user(false, true));
            default -> Optional.of(user(true, false));
        });

        assertThrows(ResourceNotFoundException.class, () -> guard.require(principal));
    }

    @Test
    void anonymousCallerGets404WithoutTouchingTheRepository() {
        assertThrows(ResourceNotFoundException.class, () -> guard.require(null));

        verifyNoInteractions(staffUsers);
    }

    private StaffUserEntity user(boolean active, boolean platformAdmin) {
        StaffUserEntity user = active
                ? StaffUserEntity.createCashier("admin@example.com", "hash", "Admin", null)
                : StaffUserEntity.registerWithPassword("admin@example.com", "hash", "Admin", null);
        ReflectionTestUtils.setField(user, "platformAdmin", platformAdmin);
        return user;
    }
}
