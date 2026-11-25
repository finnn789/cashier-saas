package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.identity.MerchantStaffPasswordResetResponse;
import org.lite.project.cashiersaas.dto.response.identity.MerchantStaffResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.AuthSessionRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.service.MemberSyncPublisher;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.lite.project.cashiersaas.service.MerchantStaffService;
import org.lite.project.cashiersaas.service.PlanEntitlementService;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MerchantStaffServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private TenantMembershipRepository memberships;
    @Mock private AuthSessionRepository sessions;
    @Mock private AuditLogRepository audit;
    @Mock private MemberSyncPublisher publisher;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PlanEntitlementService planEntitlement;
    @InjectMocks private MerchantStaffService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID ownerUserId = UUID.randomUUID();
    private final UUID cashierUserId = UUID.randomUUID();
    private final UUID membershipId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final StaffUserEntity ownerUser = withId(
            StaffUserEntity.createCashier("owner@example.com", "hash", "Owner", "6281"), ownerUserId);
    private final StaffUserEntity cashierUser = withId(
            StaffUserEntity.createCashier("kasir@example.com", "old-hash", "Kasir Satu", "6282"), cashierUserId);
    private final TenantMembershipEntity ownerMembership = withId(TenantMembershipEntity.grantOwner(ownerUser, tenant),
            UUID.randomUUID());
    private final TenantMembershipEntity cashierMembership = withId(
            TenantMembershipEntity.grantCashier(cashierUser, tenant), membershipId);
    private final TenantPrincipal principal = new TenantPrincipal(ownerUserId, tenantId, outletId,
            ownerMembership.getId(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));

    @BeforeEach
    void setUp() {
        when(guard.requireOwner(principal, outletId))
                .thenReturn(new MerchantAccessContext(tenant, outlet, ownerMembership));
    }

    private void cashierFound() {
        when(memberships.findCashierForOutlet(tenantId, outletId, cashierUserId))
                .thenReturn(Optional.of(cashierMembership));
    }

    @Test
    void listMapsEveryCashierIncludingInactiveOnes() {
        TenantMembershipEntity inactive = withId(TenantMembershipEntity.grantCashier(
                withId(StaffUserEntity.createCashier("b@example.com", "h", "Budi", null), UUID.randomUUID()), tenant),
                UUID.randomUUID());
        inactive.deactivate();
        when(memberships.listCashiersForOutlet(tenantId, outletId)).thenReturn(List.of(cashierMembership, inactive));

        List<MerchantStaffResponse> result = service.list(principal, outletId);

        assertEquals(2, result.size());
        assertEquals(cashierUserId, result.get(0).getUserId());
        assertEquals("Kasir Satu", result.get(0).getFullName());
        assertEquals("kasir@example.com", result.get(0).getEmail());
        assertTrue(result.get(0).isActive());
        assertFalse(result.get(1).isActive());
    }

    @Test
    void deactivateRevokesSessionsAuditsAndPublishesDisabled() {
        cashierFound();

        MerchantStaffResponse response = service.deactivate(principal, outletId, cashierUserId);

        assertFalse(response.isActive());
        assertFalse(cashierMembership.isActive());
        verify(sessions).revokeForMembership(eq(membershipId), any());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("STAFF_DEACTIVATED", log.getValue().getAction());
        assertEquals("TENANT_MEMBERSHIP", log.getValue().getEntityType());
        assertEquals(cashierUserId, log.getValue().getEntityId());
        verify(publisher).memberChanged(tenant, cashierUser, Role.CASHIER, "DISABLED");
    }

    @Test
    void deactivatingAnInactiveMemberIsIdempotent() {
        cashierMembership.deactivate();
        cashierFound();

        MerchantStaffResponse response = service.deactivate(principal, outletId, cashierUserId);

        assertFalse(response.isActive());
        verify(sessions, never()).revokeForMembership(any(), any());
        verifyNoInteractions(audit, publisher);
    }

    @Test
    void reactivateAuditsAndPublishesActive() {
        cashierMembership.deactivate();
        cashierFound();

        MerchantStaffResponse response = service.reactivate(principal, outletId, cashierUserId);

        assertTrue(response.isActive());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("STAFF_REACTIVATED", log.getValue().getAction());
        verify(publisher).memberChanged(tenant, cashierUser, Role.CASHIER, "ACTIVE");
    }

    @Test
    void reactivatingAnActiveMemberIsIdempotent() {
        cashierFound();

        assertTrue(service.reactivate(principal, outletId, cashierUserId).isActive());

        verifyNoInteractions(audit, publisher);
    }

    @Test
    void ownerOrOtherTenantTargetIsNotFound() {
        // The repository query only returns CASHIER memberships of the principal's tenant with outlet access.
        UUID otherUser = UUID.randomUUID();
        when(memberships.findCashierForOutlet(tenantId, outletId, otherUser)).thenReturn(Optional.empty());
        when(memberships.findCashierForOutlet(tenantId, outletId, ownerUserId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deactivate(principal, outletId, otherUser));
        assertThrows(ResourceNotFoundException.class, () -> service.reactivate(principal, outletId, ownerUserId));
        assertThrows(ResourceNotFoundException.class, () -> service.resetPassword(principal, outletId, otherUser));
        verifyNoInteractions(sessions, audit, publisher);
    }

    @Test
    void resetIsRejectedWhenTheUserHasAnotherActiveMembership() {
        cashierFound();
        when(memberships.hasActiveMembershipOutsideTenant(cashierUserId, tenantId)).thenReturn(true);

        OperationConflictException ex = assertThrows(OperationConflictException.class,
                () -> service.resetPassword(principal, outletId, cashierUserId));

        assertEquals("Akun kasir ini terhubung ke bisnis lain; minta kasir mengganti password sendiri.",
                ex.getMessage());
        assertEquals("old-hash", cashierUser.getPasswordHash());
        verifyNoInteractions(passwordEncoder, sessions, audit);
    }

    @Test
    void resetStoresTheEncodedPasswordRevokesAllSessionsAndNeverAuditsTheSecret() {
        cashierFound();
        when(memberships.hasActiveMembershipOutsideTenant(cashierUserId, tenantId)).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenAnswer(call -> "encoded:" + call.getArgument(0));

        MerchantStaffPasswordResetResponse response = service.resetPassword(principal, outletId, cashierUserId);

        assertEquals(cashierUserId, response.getUserId());
        assertEquals("encoded:" + response.getTemporaryPassword(), cashierUser.getPasswordHash());
        verify(sessions).revokeForUserExcept(eq(cashierUserId), isNull(), any());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("STAFF_PASSWORD_RESET", log.getValue().getAction());
        assertEquals("STAFF_USER", log.getValue().getEntityType());
        assertNull(log.getValue().getDetails());
        assertNull(log.getValue().getReason());
    }

    @Test
    void temporaryPasswordHasTwelveUnambiguousCharactersWithALetterAndADigit() {
        cashierFound();
        when(memberships.hasActiveMembershipOutsideTenant(cashierUserId, tenantId)).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            String password = service.resetPassword(principal, outletId, cashierUserId).getTemporaryPassword();
            assertEquals(12, password.length(), password);
            assertTrue(password.chars().anyMatch(Character::isLetter), password);
            assertTrue(password.chars().anyMatch(Character::isDigit), password);
            assertTrue(password.matches("[A-Za-z2-9]{12}"), password);
            assertTrue(password.chars().noneMatch(c -> "0O1lI".indexOf(c) >= 0), password);
            seen.add(password);
        }
        assertTrue(seen.size() > 190, "passwords must be random");
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
