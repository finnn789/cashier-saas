package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.component.JwtTokenService;
import org.lite.project.cashiersaas.configuration.JwtProperties;
import org.lite.project.cashiersaas.dto.response.identity.FreeTierStartResponse;
import org.lite.project.cashiersaas.entity.shared.billing.BusinessTypeEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.BusinessTypeRepository;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.repository.TenantRepository;
import org.lite.project.cashiersaas.service.FreeTierOnboardingService;
import org.lite.project.cashiersaas.enums.OnboardingIntent;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FreeTierOnboardingServiceTest {
    @Mock private StaffUserRepository staffUserRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private TenantMembershipRepository membershipRepository;
    @Mock private BusinessTypeRepository businessTypeRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private OutletRepository outletRepository;
    @Mock private MembershipOutletAccessRepository accessRepository;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private JwtProperties jwtProperties;
    @InjectMocks private FreeTierOnboardingService service;

    private final UUID userId = UUID.randomUUID();
    private final AccountPrincipal principal = new AccountPrincipal(userId, "owner@example.com");

    @Test
    void startCreatesFnbTenantOwnerMembershipAndActiveFreeSubscription() {
        StaffUserEntity owner = StaffUserEntity.registerWithPassword("owner@example.com", "hash", "Owner");
        owner.chooseOnboardingIntent(OnboardingIntent.OWNER);
        when(staffUserRepository.findById(userId)).thenReturn(Optional.of(owner));
        BusinessTypeEntity fnb = new BusinessTypeEntity();
        ReflectionTestUtils.setField(fnb, "code", "FNB");
        when(businessTypeRepository.findActiveByCode("FNB")).thenReturn(Optional.of(fnb));

        FreeTierStartResponse response = service.startFreeTier(principal, " onboarding-1 ", "FNB");

        ArgumentCaptor<TenantEntity> tenantCaptor = ArgumentCaptor.forClass(TenantEntity.class);
        ArgumentCaptor<TenantMembershipEntity> membershipCaptor = ArgumentCaptor.forClass(TenantMembershipEntity.class);
        ArgumentCaptor<SubscriptionEntity> subscriptionCaptor = ArgumentCaptor.forClass(SubscriptionEntity.class);
        verify(tenantRepository).findByOnboardingKey(userId, "onboarding-1");
        verify(tenantRepository).persist(tenantCaptor.capture());
        verify(membershipRepository).persist(membershipCaptor.capture());
        verify(subscriptionRepository).persist(subscriptionCaptor.capture());
        TenantEntity tenant = tenantCaptor.getValue();
        assertEquals("PENDING_PROFILE", response.getTenantStatus());
        assertEquals("FNB", response.getBusinessType());
        assertEquals("FREETIER", response.getPlanCode());
        assertEquals(owner, tenant.getCreatedByUser());
        assertEquals(fnb, tenant.getBusinessType());
        assertEquals(tenant, membershipCaptor.getValue().getTenant());
        assertEquals(tenant, subscriptionCaptor.getValue().getTenant());
        assertEquals("FREETIER", subscriptionCaptor.getValue().getPlanCode());
        assertTrue(subscriptionCaptor.getValue().isActiveAt(Instant.now()));
    }

    @Test
    void retryReturnsExistingOnboardingWithoutCreatingMoreRecords() {
        StaffUserEntity owner = StaffUserEntity.registerWithPassword("owner@example.com", "hash", "Owner");
        owner.chooseOnboardingIntent(OnboardingIntent.OWNER);
        when(staffUserRepository.findById(userId)).thenReturn(Optional.of(owner));
        BusinessTypeEntity fnb = new BusinessTypeEntity();
        ReflectionTestUtils.setField(fnb, "code", "FNB");
        when(businessTypeRepository.findActiveByCode("FNB")).thenReturn(Optional.of(fnb));
        service.startFreeTier(principal, "onboarding-1", "FNB");
        ArgumentCaptor<TenantEntity> tenantCaptor = ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).persist(tenantCaptor.capture());
        TenantEntity existing = tenantCaptor.getValue();
        UUID tenantId = UUID.randomUUID();
        ReflectionTestUtils.setField(existing, "id", tenantId);
        when(tenantRepository.findByOnboardingKey(userId, "onboarding-1")).thenReturn(Optional.of(existing));

        FreeTierStartResponse response = service.startFreeTier(principal, "onboarding-1", "FNB");

        assertEquals(tenantId, response.getTenantId());
        verify(tenantRepository, org.mockito.Mockito.times(1)).persist(any(TenantEntity.class));
        verify(subscriptionRepository, org.mockito.Mockito.times(1)).persist(any(SubscriptionEntity.class));
    }

    @Test
    void blankIdempotencyKeyIsRejectedBeforeDatabaseAccess() {
        assertThrows(InvalidOperationException.class, () -> service.startFreeTier(principal, "  ", "FNB"));
        verify(tenantRepository, never()).findByOnboardingKey(any(UUID.class), any(String.class));
    }

    @Test
    void laundryOnboardingIsUnavailable() {
        assertThrows(InvalidOperationException.class,
                () -> service.startFreeTier(principal, "onboarding-laundry", "LAUNDRY"));
        verify(tenantRepository, never()).findByOnboardingKey(any(UUID.class), any(String.class));
    }

}
