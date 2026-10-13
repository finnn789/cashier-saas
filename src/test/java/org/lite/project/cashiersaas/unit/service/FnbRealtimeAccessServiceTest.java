package org.lite.project.cashiersaas.unit.service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.entity.shared.identity.AuthSessionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.repository.AuthSessionRepository;
import org.lite.project.cashiersaas.service.FnbRealtimeAccessService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class FnbRealtimeAccessServiceTest {
    private final MerchantAccessGuard guard = mock(MerchantAccessGuard.class);
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final Instant future = Instant.parse("2100-01-01T00:00:00Z");
    private TenantPrincipal principal;
    private TenantEntity tenant;
    private OutletEntity outlet;
    private TenantMembershipEntity membership;
    private AuthSessionEntity session;
    private FnbRealtimeAccessService service;

    @BeforeEach
    void setUp() {
        fixture(Role.CASHIER);
        service = new FnbRealtimeAccessService(guard, sessions);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CASHIER", "OWNERMERCHANT"})
    void liveSessionWithMatchingCurrentMembershipIsAllowedAndLookupIsScoped(Role role) {
        fixture(role);
        assertTrue(service.isCurrent(principal, future));
        verify(sessions).findById(principal.getSessionId(), principal.getUserId());
        verifyNoMoreInteractions(sessions);
        verify(guard).requireOutlet(principal, principal.getOutletId());
    }

    @Test
    void expiredOrMissingAccessExpiryAndIncompletePrincipalAvoidRepositoryLookup() {
        assertFalse(service.isCurrent(principal, Instant.EPOCH));
        assertFalse(service.isCurrent(principal, null));
        assertFalse(service.isCurrent(null, future));
        assertFalse(service.isCurrent(new TenantPrincipal(principal.getUserId(), principal.getTenantId(), Set.of(Role.CASHIER)), future));
        assertFalse(service.isCurrent(new TenantPrincipal(principal.getUserId(), principal.getTenantId(),
                principal.getOutletId(), null, principal.getSessionId(), principal.getRoles()), future));
        assertFalse(service.isCurrent(new TenantPrincipal(principal.getUserId(), principal.getTenantId(),
                principal.getOutletId(), principal.getMembershipId(), null, principal.getRoles()), future));
        verifyNoInteractions(sessions, guard);
    }

    @Test
    void missingCurrentSessionIsDeniedWithoutGuardLookup() {
        when(sessions.findById(principal.getSessionId(), principal.getUserId())).thenReturn(Optional.empty());
        assertFalse(service.isCurrent(principal, future));
        verifyNoInteractions(guard);
    }

    @Test
    void revokedSessionIsDeniedWithoutGuardLookup() {
        session.revoke(Instant.now());
        assertFalse(service.isCurrent(principal, future));
        verifyNoInteractions(guard);
    }

    @Test
    void expiredSessionIsDeniedEvenWhenJwtIsLive() {
        ReflectionTestUtils.setField(session, "expiresAt", Instant.EPOCH);
        assertFalse(service.isCurrent(principal, future));
        verifyNoInteractions(guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "outlet", "membership", "role"})
    void incompleteSelectedMerchantSessionIsDenied(String field) {
        ReflectionTestUtils.setField(session, field, null);
        assertFalse(service.isCurrent(principal, future));
        verifyNoInteractions(guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "outlet", "membership", "role"})
    void selectedMerchantScopeOrRoleMismatchIsDeniedBeforeGuard(String field) {
        switch (field) {
            case "tenant" -> {
                TenantEntity different = new TenantEntity();
                ReflectionTestUtils.setField(different, "id", UUID.randomUUID());
                ReflectionTestUtils.setField(session, field, different);
            }
            case "outlet" -> {
                OutletEntity different = new OutletEntity();
                ReflectionTestUtils.setField(different, "id", UUID.randomUUID());
                ReflectionTestUtils.setField(session, field, different);
            }
            case "membership" -> {
                TenantMembershipEntity different = TenantMembershipEntity.grantCashier(null, tenant);
                ReflectionTestUtils.setField(different, "id", UUID.randomUUID());
                ReflectionTestUtils.setField(session, field, different);
            }
            case "role" -> ReflectionTestUtils.setField(session, field, Role.OWNERMERCHANT);
            default -> fail("Unknown fixture");
        }
        assertFalse(service.isCurrent(principal, future));
        verifyNoInteractions(guard);
    }

    @Test
    void guardMembershipIdentityMismatchDeniesAccess() {
        TenantMembershipEntity other = TenantMembershipEntity.grantCashier(null, tenant);
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        when(guard.requireOutlet(principal, principal.getOutletId()))
                .thenReturn(new MerchantAccessContext(tenant, outlet, other));
        assertFalse(service.isCurrent(principal, future));
    }

    @Test
    void guardRoleMismatchDeniesAccessEvenWhenMembershipIdMatches() {
        TenantMembershipEntity owner = TenantMembershipEntity.grantOwner(null, tenant);
        ReflectionTestUtils.setField(owner, "id", principal.getMembershipId());
        when(guard.requireOutlet(principal, principal.getOutletId()))
                .thenReturn(new MerchantAccessContext(tenant, outlet, owner));
        assertFalse(service.isCurrent(principal, future));
    }

    @Test
    void guardFailureIsContainedAsDenial() {
        when(guard.requireOutlet(principal, principal.getOutletId())).thenThrow(new IllegalStateException("revoked"));
        assertFalse(service.isCurrent(principal, future));
    }

    @Test
    void repositoryFailureIsContainedAsDenial() {
        when(sessions.findById(principal.getSessionId(), principal.getUserId())).thenThrow(new IllegalStateException("unavailable"));
        assertFalse(service.isCurrent(principal, future));
        verifyNoInteractions(guard);
    }

    private void fixture(Role role) {
        tenant = new TenantEntity();
        UUID tenantId = UUID.randomUUID(), outletId = UUID.randomUUID(), membershipId = UUID.randomUUID();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        ReflectionTestUtils.setField(outlet, "id", outletId);
        membership = role == Role.CASHIER ? TenantMembershipEntity.grantCashier(null, tenant)
                : TenantMembershipEntity.grantOwner(null, tenant);
        ReflectionTestUtils.setField(membership, "id", membershipId);
        principal = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId, membershipId, UUID.randomUUID(), Set.of(role));
        session = AuthSessionEntity.start(null, future);
        session.selectMerchant(membership, outlet);
        when(sessions.findById(principal.getSessionId(), principal.getUserId())).thenReturn(Optional.of(session));
        when(guard.requireOutlet(principal, outletId)).thenReturn(new MerchantAccessContext(tenant, outlet, membership));
    }
}
