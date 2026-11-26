package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.dto.response.identity.OnboardingStatusResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OnboardingIntent;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.TenantRepository;
import org.lite.project.cashiersaas.service.OnboardingIntentService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class OnboardingIntentServiceTest {
    @Mock private StaffUserRepository staffUserRepository;
    @Mock private TenantRepository tenantRepository;
    @InjectMocks private OnboardingIntentService service;

    private final UUID userId = UUID.randomUUID();
    private final AccountPrincipal principal = new AccountPrincipal(userId, "user@example.com");

    @ParameterizedTest
    @CsvSource({"OWNER,CASHIER_ONLY,CLAIM_INVITATION", "CASHIER_ONLY,OWNER,START_FREETIER"})
    void switchesIntentWhilePendingWithoutBusiness(OnboardingIntent current, OnboardingIntent target,
                                                   String nextStep) {
        StaffUserEntity user = pendingUser(current);
        when(staffUserRepository.findById(userId)).thenReturn(Optional.of(user));

        OnboardingStatusResponse response = service.choose(principal, target);

        assertEquals(target, user.getOnboardingIntent());
        assertEquals(target, response.getIntent());
        assertEquals(nextStep, response.getNextStep());
    }

    @Test
    void ownerWithPendingBusinessCannotSwitchToCashier() {
        StaffUserEntity user = pendingUser(OnboardingIntent.OWNER);
        when(staffUserRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tenantRepository.findLatestPendingOwnedBy(userId)).thenReturn(Optional.of(pendingTenant()));

        assertThrows(OperationConflictException.class, () -> service.choose(principal, OnboardingIntent.CASHIER_ONLY));

        assertEquals(OnboardingIntent.OWNER, user.getOnboardingIntent());
    }

    @Test
    void pendingBusinessAcceptsOwnerIntent() {
        StaffUserEntity user = pendingUser(OnboardingIntent.CASHIER_ONLY);
        TenantEntity tenant = pendingTenant();
        when(staffUserRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tenantRepository.findLatestPendingOwnedBy(userId)).thenReturn(Optional.of(tenant));

        OnboardingStatusResponse response = service.choose(principal, OnboardingIntent.OWNER);

        assertEquals(OnboardingIntent.OWNER, user.getOnboardingIntent());
        assertEquals("COMPLETE_BUSINESS_PROFILE", response.getNextStep());
        assertEquals(tenant.getId(), response.getPendingTenantId());
    }

    @Test
    void activeAccountCannotChangeIntent() {
        StaffUserEntity cashier = StaffUserEntity.createCashier("user@example.com", "hash", "User", "6281234567890");
        ReflectionTestUtils.setField(cashier, "id", userId);
        when(staffUserRepository.findById(userId)).thenReturn(Optional.of(cashier));

        assertThrows(OperationConflictException.class, () -> service.choose(principal, OnboardingIntent.OWNER));
    }

    private StaffUserEntity pendingUser(OnboardingIntent intent) {
        StaffUserEntity user = StaffUserEntity.registerWithPassword("user@example.com", "hash", "User",
                "6281234567890");
        ReflectionTestUtils.setField(user, "id", userId);
        user.chooseOnboardingIntent(intent);
        return user;
    }

    private static TenantEntity pendingTenant() {
        TenantEntity tenant = new TenantEntity();
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }
}
