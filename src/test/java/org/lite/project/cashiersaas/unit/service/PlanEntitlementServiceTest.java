package org.lite.project.cashiersaas.unit.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.PlanUpgradeRequiredException;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.service.PlanEntitlementService;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan rules of {@code PlanEntitlementService}. The repository returns the rows that are active at {@code now}
 * (the time filter itself is exercised against PostgreSQL in PlanEntitlementIntegrationTest).
 */
@Tag("unit")
class PlanEntitlementServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    private final UUID tenantId = UUID.randomUUID();
    private final TenantEntity tenant = new TenantEntity();
    private SubscriptionRepository subscriptions;
    private EntityManager entityManager;
    private PlanEntitlementService service;

    @BeforeEach
    void setUp() {
        subscriptions = mock(SubscriptionRepository.class);
        entityManager = mock(EntityManager.class);
        when(entityManager.find(TenantEntity.class, tenantId)).thenReturn(tenant);
        service = new PlanEntitlementService(subscriptions, entityManager);
    }

    private void activeRows(String... planCodes) {
        List<SubscriptionEntity> rows = new ArrayList<>();
        for (String planCode : planCodes) {
            SubscriptionEntity row = SubscriptionEntity.activateFreeTier(tenant);
            ReflectionTestUtils.setField(row, "planCode", planCode);
            rows.add(row);
        }
        when(subscriptions.findActiveAt(eq(tenantId), any(Instant.class))).thenReturn(rows);
    }

    private void trialStartedDaysAgo(long days) {
        ReflectionTestUtils.setField(tenant, "premiumTrialStartedAt", NOW.minus(Duration.ofDays(days)));
    }

    @ParameterizedTest
    @CsvSource({"FREETIER, FREETIER", "FREETIER;PRO, PRO", "PRO;FREETIER;PREMIUM, PREMIUM", "'', FREETIER"})
    void theEffectivePlanIsTheHighestActiveRowAndFreetierWithoutAny(String rows, PlanCode expected) {
        activeRows(rows.isEmpty() ? new String[0] : rows.split(";"));

        assertEquals(expected, service.effectivePlan(tenantId, NOW));
    }

    @ParameterizedTest
    @CsvSource({"1, PREMIUM", "13, PREMIUM", "14, FREETIER", "30, FREETIER"})
    void aTrialCountsAsPremiumForFourteenDaysOnly(long daysAgo, PlanCode expected) {
        activeRows("FREETIER");
        trialStartedDaysAgo(daysAgo);

        assertEquals(expected, service.effectivePlan(tenantId, NOW));
    }

    @Test
    void requireRefusesBelowTheMinimumPlanNamingTheRequiredOne() {
        activeRows("FREETIER", "PRO");

        assertDoesNotThrow(() -> service.require(tenantId, PlanCode.PRO));
        PlanUpgradeRequiredException refused = assertThrows(PlanUpgradeRequiredException.class,
                () -> service.require(tenantId, PlanCode.PREMIUM));
        assertEquals("Fitur ini tersedia di paket Premium.", refused.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"PRO, -1", "FREETIER, 30"})
    void theTrialIsNotCountedAgainForAPaidTenantOrOneThatAlreadyHadIt(String plan, long trialDaysAgo) {
        activeRows("FREETIER", plan);
        if (trialDaysAgo > 0) {
            trialStartedDaysAgo(trialDaysAgo);
        }
        Instant before = tenant.getPremiumTrialStartedAt();

        service.startTrialIfEligible(tenantId, NOW);

        verify(entityManager, never()).createQuery(anyString(), eq(Long.class));
        assertEquals(before, tenant.getPremiumTrialStartedAt());
    }
}
