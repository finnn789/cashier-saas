package org.lite.project.cashiersaas.service.unit;

import org.junit.jupiter.api.Test;
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
    void startCreatesLaundryTenantOwnerMembershipAndActiveFreeSubscription() {
        StaffUserEntity owner = StaffUserEntity.registerWithPassword("owner@example.com", "hash", "Owner");
        when(staffUserRepository.findById(userId)).thenReturn(Optional.of(owner));
        BusinessTypeEntity laundry = new BusinessTypeEntity();
        when(businessTypeRepository.findActiveLaundry()).thenReturn(Optional.of(laundry));

        FreeTierStartResponse response = service.start(principal, " onboarding-1 ");

        ArgumentCaptor<TenantEntity> tenantCaptor = ArgumentCaptor.forClass(TenantEntity.class);
        ArgumentCaptor<TenantMembershipEntity> membershipCaptor = ArgumentCaptor.forClass(TenantMembershipEntity.class);
        ArgumentCaptor<SubscriptionEntity> subscriptionCaptor = ArgumentCaptor.forClass(SubscriptionEntity.class);
        verify(tenantRepository).findByOnboardingKey(userId, "onboarding-1");
        verify(tenantRepository).persist(tenantCaptor.capture());
        verify(membershipRepository).persist(membershipCaptor.capture());
        verify(subscriptionRepository).persist(subscriptionCaptor.capture());
        TenantEntity tenant = tenantCaptor.getValue();
        assertEquals("PENDING_PROFILE", response.getTenantStatus());
        assertEquals("LAUNDRY", response.getBusinessType());
        assertEquals("FREETIER", response.getPlanCode());
        assertEquals(owner, tenant.getCreatedByUser());
        assertEquals(laundry, tenant.getBusinessType());
        assertEquals(tenant, membershipCaptor.getValue().getTenant());
        assertEquals(tenant, subscriptionCaptor.getValue().getTenant());
        assertEquals("FREETIER", subscriptionCaptor.getValue().getPlanCode());
        assertTrue(subscriptionCaptor.getValue().isActiveAt(Instant.now()));
    }

    @Test
    void retryReturnsExistingOnboardingWithoutCreatingMoreRecords() {
        when(staffUserRepository.findById(userId)).thenReturn(Optional.of(
                StaffUserEntity.registerWithPassword("owner@example.com", "hash", "Owner")));
        when(businessTypeRepository.findActiveLaundry()).thenReturn(Optional.of(new BusinessTypeEntity()));
        service.start(principal, "onboarding-1");
        ArgumentCaptor<TenantEntity> tenantCaptor = ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).persist(tenantCaptor.capture());
        TenantEntity existing = tenantCaptor.getValue();
        UUID tenantId = UUID.randomUUID();
        ReflectionTestUtils.setField(existing, "id", tenantId);
        when(tenantRepository.findByOnboardingKey(userId, "onboarding-1")).thenReturn(Optional.of(existing));

        FreeTierStartResponse response = service.start(principal, "onboarding-1");

        assertEquals(tenantId, response.getTenantId());
        verify(tenantRepository, org.mockito.Mockito.times(1)).persist(any(TenantEntity.class));
        verify(subscriptionRepository, org.mockito.Mockito.times(1)).persist(any(SubscriptionEntity.class));
    }

    @Test
    void blankIdempotencyKeyIsRejectedBeforeDatabaseAccess() {
        assertThrows(InvalidOperationException.class, () -> service.start(principal, "  "));
        verify(tenantRepository, never()).findByOnboardingKey(any(UUID.class), any(String.class));
    }

}
