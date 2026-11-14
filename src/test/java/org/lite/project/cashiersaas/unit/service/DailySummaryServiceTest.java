package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.lite.project.cashiersaas.component.DailySummaryRequestedEvent;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.fnb.DailySummaryDispatchEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.repository.DailySummaryDispatchRepository;
import org.lite.project.cashiersaas.service.DailySummaryClient;
import org.lite.project.cashiersaas.service.DailySummaryService;
import org.lite.project.cashiersaas.service.PlanEntitlementService;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** TASK-080 D1: once per outlet per business day, last-shift trigger, 23:00 outlet-time fallback, retry and dispatch state. */
@Tag("unit")
class DailySummaryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-14T16:00:00Z"); // 23:00 in Asia/Jakarta

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final TenantMembershipEntity membership = TenantMembershipEntity.grantCashier(
            StaffUserEntity.createCashier("k@example.com", "hash", "Kasir", null), tenant);
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, membership);

    private DailySummaryDispatchRepository dispatches;
    private DailySummaryClient client;
    private ApplicationEventPublisher events;
    private PlanEntitlementService plans;
    private DailySummaryService service;

    @BeforeEach
    void setUp() {
        dispatches = mock(DailySummaryDispatchRepository.class);
        client = mock(DailySummaryClient.class);
        events = mock(ApplicationEventPublisher.class);
        plans = mock(PlanEntitlementService.class);
        when(plans.effectivePlan(eq(tenantId), any(Instant.class))).thenReturn(PlanCode.PREMIUM);
        when(client.isEnabled()).thenReturn(true);
        service = new DailySummaryService(dispatches, client, events, new TransactionTemplate(mock(PlatformTransactionManager.class)), plans);
    }

    private CashShiftEntity shiftOpenedAt(String instant) {
        CashShiftEntity shift = CashShiftEntity.open(tenant, outlet, membership, new BigDecimal("100.00"));
        ReflectionTestUtils.setField(shift, "openedAt", Instant.parse(instant));
        return shift;
    }

    private DailySummaryDispatchEntity pendingRow(LocalDate businessDate) {
        DailySummaryDispatchEntity row = new DailySummaryDispatchEntity();
        ReflectionTestUtils.setField(row, "tenant", tenant);
        ReflectionTestUtils.setField(row, "outlet", outlet);
        ReflectionTestUtils.setField(row, "businessDate", businessDate);
        return row;
    }

    private void runFallback(Instant now) {
        ReflectionTestUtils.invokeMethod(service, "runFallback", now); // package-private test seam
    }

    // ---- onShiftClosed ----

    @Test
    void closingTheLastOpenShiftInsertsTheRowAndPublishesTheEventOnce() {
        UUID dispatchId = UUID.randomUUID();
        when(dispatches.countOpenShifts(tenantId, outletId)).thenReturn(0L);
        when(dispatches.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.of(dispatchId));

        service.onShiftClosed(context, shiftOpenedAt("2026-11-14T03:00:00Z"));

        verify(dispatches).insertIfAbsent(tenantId, outletId, LocalDate.of(2026, 11, 14), DailySummaryDispatchEntity.Trigger.SHIFT_CLOSED);
        ArgumentCaptor<DailySummaryRequestedEvent> event = ArgumentCaptor.forClass(DailySummaryRequestedEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals(dispatchId, event.getValue().getDispatchId());
    }

    @Test
    void anotherOpenShiftOfTheOutletMeansNoRowAndNoEvent() {
        when(dispatches.countOpenShifts(tenantId, outletId)).thenReturn(1L);

        service.onShiftClosed(context, shiftOpenedAt("2026-11-14T03:00:00Z"));

        verify(dispatches, never()).insertIfAbsent(any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @EnumSource(value = PlanCode.class, names = {"FREETIER", "PRO"})
    void closingTheLastShiftBelowPremiumInsertsNoRowAndPublishesNoEvent(PlanCode plan) {
        when(dispatches.countOpenShifts(tenantId, outletId)).thenReturn(0L);
        when(plans.effectivePlan(eq(tenantId), any(Instant.class))).thenReturn(plan);

        service.onShiftClosed(context, shiftOpenedAt("2026-11-14T03:00:00Z"));

        verify(dispatches, never()).insertIfAbsent(any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void aSecondCloseOnTheSameDayInsertsNothingNewAndPublishesNoEvent() {
        when(dispatches.countOpenShifts(tenantId, outletId)).thenReturn(0L);
        when(dispatches.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());

        service.onShiftClosed(context, shiftOpenedAt("2026-11-14T03:00:00Z"));

        verify(dispatches).insertIfAbsent(tenantId, outletId, LocalDate.of(2026, 11, 14), DailySummaryDispatchEntity.Trigger.SHIFT_CLOSED);
        verifyNoInteractions(events);
    }

    @Test
    void theBusinessDateIsTheOutletLocalDateOfTheShiftOpening() {
        when(dispatches.countOpenShifts(tenantId, outletId)).thenReturn(0L);
        when(dispatches.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());

        // 18:00Z on the 13th is 01:00 on the 14th in Asia/Jakarta
        service.onShiftClosed(context, shiftOpenedAt("2026-11-13T18:00:00Z"));

        verify(dispatches).insertIfAbsent(eq(tenantId), eq(outletId), eq(LocalDate.of(2026, 11, 14)), any());
    }

    @Test
    void anInvalidStoredZoneFallsBackToUtcInsteadOfBreakingShiftClose() {
        OutletEntity broken = withId(OutletEntity.open(tenant, "Broken", "Not/AZone", null, null), outletId);
        when(dispatches.countOpenShifts(tenantId, outletId)).thenReturn(0L);
        when(dispatches.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.onShiftClosed(new MerchantAccessContext(tenant, broken, membership),
                shiftOpenedAt("2026-11-13T18:00:00Z")));

        verify(dispatches).insertIfAbsent(eq(tenantId), eq(outletId), eq(LocalDate.of(2026, 11, 13)), any());
    }

    // ---- runFallback ----

    @Test
    void theFallbackSkipsAnOutletBeforeTwentyThreeHundredLocalTime() {
        when(dispatches.activeFnbOutlets(any(Instant.class))).thenReturn(List.of(new DailySummaryDispatchRepository.OutletRef(tenantId, outletId, "Asia/Jakarta")));

        runFallback(Instant.parse("2026-11-14T15:59:00Z")); // 22:59 Jakarta

        verify(dispatches, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    void theFallbackInsertsForTheLocalDateFromTwentyThreeHundredAndDispatchesTheNewRow() {
        UUID dispatchId = UUID.randomUUID();
        when(dispatches.activeFnbOutlets(any(Instant.class))).thenReturn(List.of(new DailySummaryDispatchRepository.OutletRef(tenantId, outletId, "Asia/Jakarta")));
        when(dispatches.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.of(dispatchId));
        DailySummaryDispatchEntity row = pendingRow(LocalDate.of(2026, 11, 14));
        when(dispatches.byId(dispatchId)).thenReturn(Optional.of(row));
        when(client.send(tenantId, outletId, LocalDate.of(2026, 11, 14))).thenReturn(true);

        runFallback(NOW); // exactly 23:00 Jakarta

        verify(dispatches).insertIfAbsent(tenantId, outletId, LocalDate.of(2026, 11, 14), DailySummaryDispatchEntity.Trigger.FALLBACK);
        verify(client).send(tenantId, outletId, LocalDate.of(2026, 11, 14));
        assertEquals(DailySummaryDispatchEntity.Status.SENT, row.getStatus());
    }

    @Test
    void theFallbackUsesEachOutletsOwnZoneAndLocalDate() {
        UUID jakartaOutlet = UUID.randomUUID();
        UUID newYorkOutlet = UUID.randomUUID();
        UUID tokyoOutlet = UUID.randomUUID();
        UUID honoluluOutlet = UUID.randomUUID();
        when(dispatches.activeFnbOutlets(any(Instant.class))).thenReturn(List.of(
                new DailySummaryDispatchRepository.OutletRef(tenantId, jakartaOutlet, "Asia/Jakarta"), // 23:30 on the 14th
                new DailySummaryDispatchRepository.OutletRef(tenantId, newYorkOutlet, "America/New_York"), // 11:30
                new DailySummaryDispatchRepository.OutletRef(tenantId, tokyoOutlet, "Asia/Tokyo"), // 01:30 on the 15th
                new DailySummaryDispatchRepository.OutletRef(tenantId, honoluluOutlet, "Pacific/Honolulu"))); // 06:30
        when(dispatches.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());

        runFallback(Instant.parse("2026-11-14T16:30:00Z"));

        verify(dispatches).insertIfAbsent(tenantId, jakartaOutlet, LocalDate.of(2026, 11, 14), DailySummaryDispatchEntity.Trigger.FALLBACK);
        verify(dispatches, never()).insertIfAbsent(eq(tenantId), eq(newYorkOutlet), any(), any());
        verify(dispatches, never()).insertIfAbsent(eq(tenantId), eq(tokyoOutlet), any(), any());
        verify(dispatches, never()).insertIfAbsent(eq(tenantId), eq(honoluluOutlet), any(), any());
    }

    @Test
    void anOutletThatAlreadyHasARowForTheDayIsNotDispatchedAgainByTheFallbackInsert() {
        when(dispatches.activeFnbOutlets(any(Instant.class))).thenReturn(List.of(new DailySummaryDispatchRepository.OutletRef(tenantId, outletId, "Asia/Jakarta")));
        when(dispatches.insertIfAbsent(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(dispatches.retryable(anyInt(), any(), any(), anyInt(), any(Instant.class))).thenReturn(List.of());

        runFallback(NOW);

        verify(dispatches, never()).byId(any());
        verify(client, never()).send(any(), any(), any());
    }

    @Test
    void theFallbackRetriesPendingAndFailedRowsWithTheAttemptCapCutoffMinimumDateAndLimit() {
        UUID retryId = UUID.randomUUID();
        UUID otherRetryId = UUID.randomUUID();
        when(dispatches.activeFnbOutlets(any(Instant.class))).thenReturn(List.of());
        when(dispatches.retryable(anyInt(), any(), any(), anyInt(), any(Instant.class))).thenReturn(List.of(retryId, otherRetryId));
        DailySummaryDispatchEntity failedRow = pendingRow(LocalDate.of(2026, 11, 14));
        failedRow.markFailed();
        when(dispatches.byId(retryId)).thenReturn(Optional.of(failedRow));
        when(dispatches.byId(otherRetryId)).thenReturn(Optional.empty());
        when(client.send(tenantId, outletId, LocalDate.of(2026, 11, 14))).thenReturn(true);

        runFallback(NOW);

        // attempts < 3, last attempt older than 10 minutes, business date within the last 2 days (UTC), at most 50 rows
        verify(dispatches).retryable(3, Instant.parse("2026-11-14T15:50:00Z"), LocalDate.of(2026, 11, 12), 50, NOW);
        verify(client).send(tenantId, outletId, LocalDate.of(2026, 11, 14));
        assertEquals(DailySummaryDispatchEntity.Status.SENT, failedRow.getStatus());
        assertEquals(2, failedRow.getAttempts());
    }

    @Test
    void aDisabledClientMakesTheFallbackANoOp() {
        when(client.isEnabled()).thenReturn(false);

        runFallback(NOW);

        verifyNoInteractions(dispatches);
        verifyNoMoreInteractions(events);
    }

    // ---- dispatch ----

    @Test
    void aSuccessfulSendMarksTheRowSentAndCountsTheAttempt() {
        UUID id = UUID.randomUUID();
        DailySummaryDispatchEntity row = pendingRow(LocalDate.of(2026, 11, 14));
        when(dispatches.byId(id)).thenReturn(Optional.of(row));
        when(client.send(tenantId, outletId, LocalDate.of(2026, 11, 14))).thenReturn(true);

        service.dispatch(id);

        assertEquals(DailySummaryDispatchEntity.Status.SENT, row.getStatus());
        assertEquals(1, row.getAttempts());
        assertNotNull(row.getLastAttemptAt());
    }

    @Test
    void aFailedSendMarksTheRowFailedAndCountsTheAttempt() {
        UUID id = UUID.randomUUID();
        DailySummaryDispatchEntity row = pendingRow(LocalDate.of(2026, 11, 14));
        when(dispatches.byId(id)).thenReturn(Optional.of(row));
        when(client.send(any(), any(), any())).thenReturn(false);

        service.dispatch(id);

        assertEquals(DailySummaryDispatchEntity.Status.FAILED, row.getStatus());
        assertEquals(1, row.getAttempts());
        assertNotNull(row.getLastAttemptAt());
    }

    @Test
    void aSentRowIsNeverSentAgain() {
        UUID id = UUID.randomUUID();
        DailySummaryDispatchEntity row = pendingRow(LocalDate.of(2026, 11, 14));
        row.markSent();
        when(dispatches.byId(id)).thenReturn(Optional.of(row));

        service.dispatch(id);

        verify(client, never()).send(any(), any(), any());
        assertEquals(1, row.getAttempts());
    }

    @Test
    void aMissingRowIsIgnored() {
        UUID id = UUID.randomUUID();
        when(dispatches.byId(id)).thenReturn(Optional.empty());

        service.dispatch(id);

        verify(client, never()).send(any(), any(), any());
    }

    @Test
    void dispatchNeverThrowsAndLeavesTheRowUntouchedWhenTheClientBlowsUp() {
        UUID id = UUID.randomUUID();
        DailySummaryDispatchEntity row = pendingRow(LocalDate.of(2026, 11, 14));
        when(dispatches.byId(id)).thenReturn(Optional.of(row));
        when(client.send(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> service.dispatch(id));

        assertEquals(DailySummaryDispatchEntity.Status.PENDING, row.getStatus());
        assertEquals(0, row.getAttempts());
        assertNull(row.getLastAttemptAt());
    }

    @Test
    void dispatchNeverThrowsWhenTheRepositoryFails() {
        UUID id = UUID.randomUUID();
        when(dispatches.byId(id)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.dispatch(id));

        verify(client, never()).send(any(), any(), any());
    }

    @Test
    void aDisabledClientMakesDispatchANoOp() {
        when(client.isEnabled()).thenReturn(false);

        service.dispatch(UUID.randomUUID());

        verifyNoInteractions(dispatches);
        verify(client, never()).send(any(), any(), any());
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
