package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.dto.request.identity.LoginRequest;
import org.lite.project.cashiersaas.dto.response.identity.LoginResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.MerchantSelectionRepository;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.service.AuthSessionService;
import org.lite.project.cashiersaas.service.AuthSessionService.IssuedTokens;
import org.lite.project.cashiersaas.service.LoginService;
import org.lite.project.cashiersaas.service.PlanEntitlementService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Only the platform additions of LoginService: the SUPERADMIN entry and the platformAdmin flag on login. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class LoginServicePlatformTest {
    private final AccountPrincipal principal = new AccountPrincipal(UUID.randomUUID(), "admin@example.com", UUID.randomUUID());

    @Mock
    private StaffUserRepository staffUserRepository;
    @Mock
    private MembershipOutletAccessRepository accessRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthSessionService authSessionService;
    @Mock
    private MerchantSelectionRepository merchantSelectionRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private PlanEntitlementService planEntitlementService;
    @InjectMocks
    private LoginService service;

    @Test
    void startPlatformSessionIssuesAPlatformTokenForAnActiveAdmin() {
        StaffUserEntity admin = user(true, true);
        IssuedTokens tokens = new IssuedTokens("platform-token", Instant.now().plusSeconds(600), null, "PLATFORM", null, null,
                Role.SUPERADMIN);
        when(staffUserRepository.findById(principal.getUserId())).thenReturn(Optional.of(admin));
        when(authSessionService.issuePlatform(principal, admin)).thenReturn(tokens);

        LoginResponse response = service.startPlatformSession(principal);

        assertEquals("platform-token", response.getAccessToken());
        assertEquals("PLATFORM", response.getNextStep());
        assertEquals(Role.SUPERADMIN, response.getRole());
        assertTrue(response.isPlatformAdmin());
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,false", "false,false"})
    void startPlatformSessionReturns404ForAnInactiveOrNonAdminAccountAndIssuesNothing(boolean active, boolean platformAdmin) {
        when(staffUserRepository.findById(principal.getUserId())).thenReturn(Optional.of(user(active, platformAdmin)));

        assertThrows(ResourceNotFoundException.class, () -> service.startPlatformSession(principal));

        verifyNoInteractions(authSessionService);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,false"})
    void loginReportsThePlatformAdminFlagOfTheUser(boolean platformAdmin, boolean expected) {
        StaffUserEntity user = user(true, platformAdmin);
        LoginRequest request = new LoginRequest();
        request.setEmail(" Admin@Example.com ");
        request.setPassword("secret");
        when(staffUserRepository.findByNormalizedEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        when(authSessionService.start(user)).thenReturn(new IssuedTokens("access", Instant.now(), "refresh", "SELECT_MERCHANT",
                null, null, null));

        LoginResponse response = service.login(request);

        assertEquals(expected, response.isPlatformAdmin());
    }

    private StaffUserEntity user(boolean active, boolean platformAdmin) {
        StaffUserEntity user = active
                ? StaffUserEntity.createCashier("admin@example.com", "hash", "Admin", null)
                : StaffUserEntity.registerWithPassword("admin@example.com", "hash", "Admin", null);
        ReflectionTestUtils.setField(user, "platformAdmin", platformAdmin);
        return user;
    }
}
