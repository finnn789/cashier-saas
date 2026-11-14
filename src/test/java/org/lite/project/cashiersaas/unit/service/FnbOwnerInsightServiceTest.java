package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.dto.response.FnbCashierAnomaly;
import org.lite.project.cashiersaas.dto.response.FnbOwnerSalesInsight;
import org.lite.project.cashiersaas.dto.response.FnbRestockSuggestion;
import org.lite.project.cashiersaas.dto.response.FnbSalesReportResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbCashExpenseRepository;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbShiftRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.service.FnbOwnerInsightService;
import org.lite.project.cashiersaas.service.FnbSalesReportService;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** TASK-080: restock formula, cashier anomaly rule D3 and the sales insight extras of {@code FnbOwnerInsightService}. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbOwnerInsightServiceTest {
    @Mock private FnbSalesReportService salesReports;
    @Mock private FnbShiftRepository shifts;
    @Mock private FnbCashExpenseRepository expenses;
    @Mock private FnbInventoryRepository inventory;
    @Mock private AuditLogRepository audits;
    @Mock private FnbOrderRepository orders;
    @Mock private TenantMembershipRepository memberships;
    @InjectMocks private FnbOwnerInsightService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final StaffUserEntity ownerUser = withId(StaffUserEntity.createCashier("o@example.com", "hash", "Owner", null), UUID.randomUUID());
    private final TenantMembershipEntity ownerMembership = TenantMembershipEntity.grantOwner(ownerUser, tenant);
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, ownerMembership);
    private final LocalDate date = LocalDate.of(2026, 11, 14);
    private final Instant dayStart = Instant.parse("2026-11-13T17:00:00Z");
    private final Instant dayEnd = Instant.parse("2026-11-14T17:00:00Z");
    private final Instant baseStart = Instant.parse("2026-10-14T17:00:00Z");

    private final Map<UUID, Long> cancelToday = new HashMap<>();
    private final Map<UUID, Long> cancelBase = new HashMap<>();
    private final Map<UUID, Long> discountToday = new HashMap<>();
    private final Map<UUID, Long> discountBase = new HashMap<>();
    private final Map<UUID, BigDecimal> cashToday = new HashMap<>();
    private final Map<UUID, BigDecimal> cashBase = new HashMap<>();
    private final List<TenantMembershipEntity> cashiers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(memberships.listCashiersForOutlet(tenantId, outletId)).thenReturn(cashiers);
        lenient().when(audits.countByActor(tenantId, outletId, AuditLogEntity.Action.FNB_ORDER_CANCELLED, dayStart, dayEnd)).thenReturn(cancelToday);
        lenient().when(audits.countByActor(tenantId, outletId, AuditLogEntity.Action.FNB_ORDER_CANCELLED, baseStart, dayStart)).thenReturn(cancelBase);
        lenient().when(orders.discountedPaidOrdersByReceiver(tenantId, outletId, dayStart, dayEnd)).thenReturn(discountToday);
        lenient().when(orders.discountedPaidOrdersByReceiver(tenantId, outletId, baseStart, dayStart)).thenReturn(discountBase);
        lenient().when(shifts.absCashDifferenceByUser(tenantId, outletId, dayStart, dayEnd)).thenReturn(cashToday);
        lenient().when(shifts.absCashDifferenceByUser(tenantId, outletId, baseStart, dayStart)).thenReturn(cashBase);
    }

    // ---- restock ----

    private InventoryItemEntity item(String name, String reorderLevel) {
        return withId(InventoryItemEntity.create(tenant, outlet, null, name, "kg", new BigDecimal(reorderLevel)), UUID.randomUUID());
    }

    private InventoryBalanceEntity balance(InventoryItemEntity item, String onHand) {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item);
        balance.apply(new BigDecimal(onHand));
        return balance;
    }

    private void stockOf(List<InventoryItemEntity> items, List<InventoryBalanceEntity> balances, Map<UUID, BigDecimal> netDelta) {
        when(inventory.items(tenantId, outletId)).thenReturn(items);
        when(inventory.balances(eq(tenantId), eq(outletId), anyList())).thenReturn(balances);
        when(inventory.netDeltaByItem(eq(tenantId), eq(outletId), any(Instant.class), anyList())).thenReturn(netDelta);
    }

    @Test
    void restockSuggestsSevenDaysOfAverageUsagePlusReorderLevelMinusOnHand() {
        InventoryItemEntity milk = item("Susu", "10.000");
        stockOf(List.of(milk), List.of(balance(milk, "5.000")), Map.of(milk.getId(), new BigDecimal("-28.000")));

        List<FnbRestockSuggestion> result = service.restock(context);

        assertEquals(1, result.size());
        FnbRestockSuggestion suggestion = result.get(0);
        assertEquals(milk.getId(), suggestion.getInventoryItemId());
        assertEquals("Susu", suggestion.getName());
        assertEquals("kg", suggestion.getUnit());
        assertEquals(new BigDecimal("5.000"), suggestion.getQuantityOnHand());
        assertEquals(new BigDecimal("10.000"), suggestion.getReorderLevel());
        assertEquals(new BigDecimal("2.000"), suggestion.getAverageDailyUsage()); // 28 / 14
        assertEquals(new BigDecimal("19.000"), suggestion.getSuggestedQuantity()); // 2 * 7 + 10 - 5
        assertEquals(InventoryBalanceEntity.Status.LOW, suggestion.getStockStatus());
    }

    @Test
    void restockQueriesUsageOnceForSaleReversalAndWasteOverTheLastFourteenDays() {
        InventoryItemEntity milk = item("Susu", "1.000");
        stockOf(List.of(milk), List.of(balance(milk, "0.500")), Map.of());
        Instant before = Instant.now();

        service.restock(context);

        Instant after = Instant.now();
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(inventory, times(1)).netDeltaByItem(eq(tenantId), eq(outletId), since.capture(),
                eq(List.of(InventoryMovementEntity.Type.SALE, InventoryMovementEntity.Type.SALE_REVERSAL,
                        InventoryMovementEntity.Type.WASTE)));
        assertFalse(since.getValue().isBefore(before.minus(Duration.ofDays(14))));
        assertFalse(since.getValue().isAfter(after.minus(Duration.ofDays(14))));
    }

    @Test
    void restockClampsNegativeUsageToZeroAndDropsAnOkItemWithNothingToOrder() {
        InventoryItemEntity rice = item("Beras", "10.000");
        // net movement is positive (reversals outweigh sales): usage is clamped to 0, 20 on hand covers the reorder level
        stockOf(List.of(rice), List.of(balance(rice, "20.000")), Map.of(rice.getId(), new BigDecimal("5.000")));

        assertTrue(service.restock(context).isEmpty());
    }

    @Test
    void restockClampsASuggestionBelowZeroAndKeepsALowItemWithAZeroSuggestion() {
        InventoryItemEntity sugar = item("Gula", "10.000"); // on hand == reorder level: LOW, suggestion 0 + 10 - 10 = 0
        InventoryItemEntity salt = item("Garam", "10.000"); // 0 + 10 - 30 < 0 is clamped to 0
        stockOf(List.of(sugar, salt), List.of(balance(sugar, "10.000"), balance(salt, "30.000")), Map.of());

        List<FnbRestockSuggestion> result = service.restock(context);

        assertEquals(1, result.size()); // salt: OK and clamped to 0 => dropped
        assertEquals("Gula", result.get(0).getName());
        assertEquals(InventoryBalanceEntity.Status.LOW, result.get(0).getStockStatus()); // on hand == reorder level is LOW
        assertEquals(new BigDecimal("0.000"), result.get(0).getSuggestedQuantity());
    }

    @Test
    void restockRoundsAverageAndSuggestionToThreeDecimalsHalfUp() {
        InventoryItemEntity oil = item("Minyak", "0.000");
        // 1 / 14 = 0.0714285... -> 0.071; 0.071 * 7 = 0.497
        stockOf(List.of(oil), List.of(balance(oil, "0.000")), Map.of(oil.getId(), new BigDecimal("-1.000")));

        FnbRestockSuggestion suggestion = service.restock(context).get(0);

        assertEquals(new BigDecimal("0.071"), suggestion.getAverageDailyUsage());
        assertEquals(new BigDecimal("0.497"), suggestion.getSuggestedQuantity());
        assertEquals(3, suggestion.getSuggestedQuantity().scale());
        assertEquals(InventoryBalanceEntity.Status.OUT, suggestion.getStockStatus());
    }

    @Test
    void restockRoundsHalfUpAtTheThirdDecimal() {
        InventoryItemEntity flour = item("Tepung", "0.000");
        // 0.007 / 14 = 0.0005 -> 0.001 (HALF_UP)
        stockOf(List.of(flour), List.of(balance(flour, "0.000")), Map.of(flour.getId(), new BigDecimal("-0.007")));

        assertEquals(new BigDecimal("0.001"), service.restock(context).get(0).getAverageDailyUsage());
    }

    @Test
    void restockTreatsAnItemWithoutABalanceRowAsOutWithZeroOnHand() {
        InventoryItemEntity tea = item("Teh", "0.000");
        stockOf(List.of(tea), List.of(), Map.of());

        List<FnbRestockSuggestion> result = service.restock(context);

        assertEquals(1, result.size()); // OUT status keeps it even though the suggestion is 0
        assertEquals(0, result.get(0).getQuantityOnHand().signum());
        assertEquals(InventoryBalanceEntity.Status.OUT, result.get(0).getStockStatus());
        assertEquals(0, result.get(0).getSuggestedQuantity().signum());
    }

    @Test
    void restockOrdersNegativeThenOutThenLowThenOkAndByNameWithinAStatus() {
        InventoryItemEntity negative = item("Teh", "5.000");
        InventoryItemEntity out = item("Susu", "5.000");
        InventoryItemEntity lowKopi = item("Kopi", "5.000");
        InventoryItemEntity lowAlmond = item("Almond", "5.000");
        InventoryItemEntity okButNeeded = item("Gula", "5.000");
        InventoryItemEntity okNothing = item("Air", "1.000");
        stockOf(List.of(okNothing, lowKopi, okButNeeded, out, negative, lowAlmond),
                List.of(balance(okNothing, "50.000"), balance(lowKopi, "2.000"), balance(okButNeeded, "10.000"),
                        balance(out, "0.000"), balance(negative, "-3.000"), balance(lowAlmond, "5.000")),
                Map.of(okButNeeded.getId(), new BigDecimal("-140.000")));

        List<String> names = service.restock(context).stream().map(FnbRestockSuggestion::getName).toList();

        assertEquals(List.of("Teh", "Susu", "Almond", "Kopi", "Gula"), names);
    }

    @Test
    void restockUsageOfOneItemDoesNotLeakIntoAnother() {
        InventoryItemEntity busy = item("Busy", "0.000");
        InventoryItemEntity idle = item("Idle", "0.000");
        stockOf(List.of(busy, idle), List.of(balance(busy, "1.000"), balance(idle, "1.000")),
                Map.of(busy.getId(), new BigDecimal("-14.000")));

        List<FnbRestockSuggestion> result = service.restock(context);

        assertEquals(1, result.size());
        assertEquals("Busy", result.get(0).getName());
        assertEquals(new BigDecimal("6.000"), result.get(0).getSuggestedQuantity()); // 1 * 7 + 0 - 1
    }

    // ---- anomalies (rule D3) ----

    private UUID cashier(String name) {
        StaffUserEntity user = withId(StaffUserEntity.createCashier(name + "@example.com", "hash", name, null), UUID.randomUUID());
        TenantMembershipEntity membership = withId(TenantMembershipEntity.grantCashier(user, tenant), UUID.randomUUID());
        cashiers.add(membership);
        return user.getId();
    }

    private List<FnbCashierAnomaly> anomalies() {
        return service.anomalies(context, date);
    }

    @Test
    void cancellationsAtTheMinimumAndAboveTwiceTheBaselineAreFlagged() {
        UUID kasir = cashier("Kasir");
        cancelToday.put(kasir, 3L);
        cancelBase.put(kasir, 30L); // 1.00 per day

        List<FnbCashierAnomaly> result = anomalies();

        assertEquals(1, result.size());
        FnbCashierAnomaly anomaly = result.get(0);
        assertEquals(kasir, anomaly.getStaffUserId());
        assertEquals("Kasir", anomaly.getFullName());
        assertEquals("CANCELLATIONS", anomaly.getKind());
        assertEquals(0, new BigDecimal("3").compareTo(anomaly.getValue()));
        assertEquals(new BigDecimal("1.00"), anomaly.getBaselineDailyAverage());
    }

    @Test
    void cancellationsBelowTheMinimumOfThreeAreNotFlaggedEvenWithAZeroBaseline() {
        UUID kasir = cashier("Kasir");
        cancelToday.put(kasir, 2L);

        assertTrue(anomalies().isEmpty());
    }

    @Test
    void valueExactlyTwiceTheBaselineIsNotFlaggedButAboveIsFlagged() {
        UUID exact = cashier("Exact");
        UUID above = cashier("Above");
        cancelToday.put(exact, 3L);
        cancelBase.put(exact, 45L); // average 1.50, twice = 3.00, 3 is not above
        cancelToday.put(above, 4L);
        cancelBase.put(above, 45L); // 4 > 3.00

        List<FnbCashierAnomaly> result = anomalies();

        assertEquals(1, result.size());
        assertEquals("Above", result.get(0).getFullName());
        assertEquals(new BigDecimal("1.50"), result.get(0).getBaselineDailyAverage());
    }

    @Test
    void baselineIsTheThirtyDayTotalDividedByThirtyWithTwoDecimals() {
        UUID kasir = cashier("Kasir");
        discountToday.put(kasir, 5L);
        discountBase.put(kasir, 10L); // 0.333... -> 0.33

        List<FnbCashierAnomaly> result = anomalies();

        assertEquals(1, result.size());
        assertEquals("DISCOUNTS", result.get(0).getKind());
        assertEquals(new BigDecimal("0.33"), result.get(0).getBaselineDailyAverage());
    }

    @Test
    void discountedOrdersBelowTheMinimumOfThreeAreNotFlagged() {
        UUID kasir = cashier("Kasir");
        discountToday.put(kasir, 2L);

        assertTrue(anomalies().isEmpty());
    }

    @Test
    void cashDifferenceNeedsAtLeastTwentyThousandAndMoreThanTwiceTheBaseline() {
        UUID small = cashier("Small");
        UUID atMinimum = cashier("AtMinimum");
        UUID usual = cashier("Usual");
        UUID spike = cashier("Spike");
        cashToday.put(small, new BigDecimal("19999.00"));
        cashToday.put(atMinimum, new BigDecimal("20000.00"));
        cashToday.put(usual, new BigDecimal("25000.00"));
        cashBase.put(usual, new BigDecimal("450000.00")); // 15000.00 per day, twice = 30000 > 25000
        cashToday.put(spike, new BigDecimal("50000.00"));
        cashBase.put(spike, new BigDecimal("300000.00")); // 10000.00 per day

        List<FnbCashierAnomaly> result = anomalies();

        assertEquals(List.of("AtMinimum", "Spike"), result.stream().map(FnbCashierAnomaly::getFullName).toList());
        assertTrue(result.stream().allMatch(anomaly -> "CASH_DIFFERENCE".equals(anomaly.getKind())));
        assertEquals(new BigDecimal("0.00"), result.get(0).getBaselineDailyAverage());
        assertEquals(new BigDecimal("10000.00"), result.get(1).getBaselineDailyAverage());
        assertEquals(new BigDecimal("50000.00"), result.get(1).getValue());
    }

    @Test
    void oneCashierCanHaveSeveralAnomaliesInKindOrder() {
        UUID kasir = cashier("Kasir");
        cancelToday.put(kasir, 3L);
        discountToday.put(kasir, 4L);
        cashToday.put(kasir, new BigDecimal("20000"));

        assertEquals(List.of("CANCELLATIONS", "DISCOUNTS", "CASH_DIFFERENCE"),
                anomalies().stream().map(FnbCashierAnomaly::getKind).toList());
    }

    @Test
    void ownersAreExcludedBecauseOnlyCashierMembershipsAreEvaluated() {
        cashier("Kasir");
        UUID ownerId = ownerUser.getId();
        cancelToday.put(ownerId, 10L);
        discountToday.put(ownerId, 10L);
        cashToday.put(ownerId, new BigDecimal("999999"));

        List<FnbCashierAnomaly> result = anomalies();

        assertTrue(result.isEmpty());
        verify(memberships, times(1)).listCashiersForOutlet(tenantId, outletId);
        assertFalse(result.stream().anyMatch(anomaly -> anomaly.getStaffUserId().equals(ownerId)));
        assertEquals(1, cashiers.size()); // only the cashier is evaluated; the owner is never in the list
    }

    @Test
    void aCashierWithNoActivityProducesNoAnomaly() {
        cashier("Quiet");

        assertTrue(anomalies().isEmpty());
    }

    @Test
    void anomalyWindowsUseTheOutletLocalDayAndThirtyPreviousDays() {
        cashier("Kasir");

        anomalies();

        verify(audits).countByActor(tenantId, outletId, AuditLogEntity.Action.FNB_ORDER_CANCELLED, dayStart, dayEnd);
        verify(audits).countByActor(tenantId, outletId, AuditLogEntity.Action.FNB_ORDER_CANCELLED, baseStart, dayStart);
        verify(shifts).absCashDifferenceByUser(tenantId, outletId, dayStart, dayEnd);
        verify(shifts).absCashDifferenceByUser(tenantId, outletId, baseStart, dayStart);
        verify(orders).discountedPaidOrdersByReceiver(tenantId, outletId, dayStart, dayEnd);
        verify(orders).discountedPaidOrdersByReceiver(tenantId, outletId, baseStart, dayStart);
    }

    @Test
    void anInvalidOutletTimeZoneIsAnInvalidOperationAndNothingIsQueried() {
        OutletEntity broken = withId(OutletEntity.open(tenant, "Broken", "Not/AZone", null, null), UUID.randomUUID());

        assertThrows(InvalidOperationException.class,
                () -> service.anomalies(new MerchantAccessContext(tenant, broken, ownerMembership), date));
        verifyNoInteractions(audits, orders, shifts, memberships);
    }

    // ---- sales insight ----

    @Test
    void salesInsightCombinesTheReportWithDiscountExpenseAndShiftTotals() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 7);
        Instant from = Instant.parse("2026-10-31T17:00:00Z");
        Instant to = Instant.parse("2026-11-07T17:00:00Z");
        FnbSalesReportResponse report = new FnbSalesReportResponse();
        report.setTimeZone("Asia/Jakarta");
        when(salesReports.summarize(context, start, end, 5)).thenReturn(report);
        when(orders.discountedPaidOrdersByReceiver(tenantId, outletId, from, to))
                .thenReturn(Map.of(UUID.randomUUID(), 2L, UUID.randomUUID(), 3L));
        when(expenses.totalForOutlet(tenantId, outletId, from, to)).thenReturn(new BigDecimal("75000.00"));
        when(shifts.closedTotals(tenantId, outletId, from, to)).thenReturn(new FnbShiftRepository.ClosedTotals(4, new BigDecimal("-12000.00")));

        FnbOwnerSalesInsight insight = service.salesInsight(context, start, end);

        assertSame(report, insight.getReport());
        assertEquals(5, insight.getDiscountedOrderCount());
        assertEquals(new BigDecimal("75000.00"), insight.getExpensesTotal());
        assertEquals(new BigDecimal("-12000.00"), insight.getCashDifferenceTotal());
        assertEquals(4, insight.getClosedShiftCount());
    }

    @Test
    void salesInsightValidatesTheRangeBeforeAnyExtraQuery() {
        LocalDate start = LocalDate.of(2026, 11, 7);
        LocalDate end = LocalDate.of(2026, 11, 1);
        when(salesReports.summarize(context, start, end, 5)).thenThrow(new InvalidOperationException("Rentang tanggal tidak valid."));

        assertThrows(InvalidOperationException.class, () -> service.salesInsight(context, start, end));
        verifyNoInteractions(orders, expenses, shifts);
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
