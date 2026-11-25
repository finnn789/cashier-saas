package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbLeakageConsolidatedResponse;
import org.lite.project.cashiersaas.dto.response.FnbLeakageLineResponse;
import org.lite.project.cashiersaas.dto.response.FnbLeakageReportResponse;
import org.lite.project.cashiersaas.dto.response.FnbOutletLeakageResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountSummaryResponse;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.StockCountEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.StockCountLineEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.PlanUpgradeRequiredException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.MerchantAccessRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.lite.project.cashiersaas.service.FnbLeakageService;
import org.lite.project.cashiersaas.service.FnbSalesReportService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.lite.project.cashiersaas.service.PlanEntitlementService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** TASK-084: ingredient leakage from stock counts (shortage, variance, loss, flags, ranking). */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbLeakageServiceTest {
    private static final Instant COUNTED_AT = Instant.parse("2026-11-20T10:00:00Z");

    @Mock private MerchantAccessGuard guard;
    @Mock private PlanEntitlementService planEntitlementService;
    @Mock private FnbInventoryRepository inventory;
    @Mock private MerchantAccessRepository accessRepository;
    @Mock private OutletRepository outlets;
    @Mock private FnbSalesReportService salesReports;
    @InjectMocks private FnbLeakageService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(userId, tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = outlet(outletId, "Outlet", "Asia/Jakarta");

    // ---------------------------------------------------------------- line math

    @ParameterizedTest
    @CsvSource({
            // previous, counted, netUsage, unitCost, shortage, variancePct, loss, flagged
            "100, 90, 200, 3, 10, 5.00, 30.00, false",       // exactly 5.00 is not flagged
            "100, 89, 200, 3, 11, 5.50, 33.00, true",        // above the threshold
            "100, 99, 800, 2, 1, 0.13, 2.00, false",         // 0.125 rounds HALF_UP
            "100, 98, 3, 2, 2, 66.67, 4.00, true",
            "5, 0, 0, 4, 5, , 20.00, true",                  // no usage: no percentage, any shortage is flagged
            "5, 5, 0, 4, 0, , 0.00, false",
            "100, 110, 200, 3, -10, -5.00, -30.00, false",   // surplus: negative loss, never flagged
            "100, 80, 100, , 20, 20.00, , true"              // unknown unit cost: unknown loss
    })
    void lineMathFollowsShortageVarianceAndLossRules(String previous, String counted, String usage, String unitCost,
                                                     String shortage, String variancePct, String loss, boolean flagged) {
        InventoryItemEntity susu = item("Susu");
        StockCountEntity count = count(outletId, null);
        stubCount(count, List.of(line(count, susu, previous, counted, unitCost)), Map.of(), total(susu, InventoryMovementEntity.Type.SALE, "-" + usage));
        when(inventory.stockCount(tenantId, outletId, count.getId())).thenReturn(Optional.of(count));

        FnbLeakageLineResponse result = service.report(owner, outletId, count.getId()).getLines().get(0);

        assertDecimal(shortage, result.getShortage());
        if (variancePct == null) assertNull(result.getVariancePct()); else assertEquals(new BigDecimal(variancePct), result.getVariancePct());
        if (loss == null) assertNull(result.getLossAmount()); else assertEquals(new BigDecimal(loss), result.getLossAmount());
        assertEquals(flagged, result.isFlagged());
    }

    @Test
    void salesReversalsBeyondTheSalesLeaveNoUsageSoAShortageIsFlaggedWithoutAPercentage() {
        InventoryItemEntity susu = item("Susu");
        StockCountEntity count = count(outletId, null);
        stubCount(count, List.of(line(count, susu, "100", "95", "2")), Map.of(),
                total(susu, InventoryMovementEntity.Type.SALE, "-10"), total(susu, InventoryMovementEntity.Type.SALE_REVERSAL, "15"));
        when(inventory.stockCount(tenantId, outletId, count.getId())).thenReturn(Optional.of(count));

        FnbLeakageLineResponse result = service.report(owner, outletId, count.getId()).getLines().get(0);

        assertDecimal("-5", result.getTheoreticalUsage());
        assertNull(result.getVariancePct());
        assertTrue(result.isFlagged());
    }

    @Test
    void reportOfAnExplicitCountFillsTheMovementBreakdownAndEachItemsPeriodStartsAtItsPreviousCount() {
        InventoryItemEntity susu = item("Susu");
        InventoryItemEntity gula = item("Gula");
        InventoryItemEntity kopi = item("Kopi");
        StockCountEntity count = count(outletId, "Akhir bulan");
        Instant previousCount = Instant.parse("2026-11-10T10:00:00Z");
        when(inventory.stockCount(tenantId, outletId, count.getId())).thenReturn(Optional.of(count));
        when(inventory.stockCountLines(tenantId, count.getId())).thenReturn(List.of(
                line(count, susu, "100", "100", null), line(count, gula, "5", "5", null), line(count, kopi, "7", "7", null)));
        when(inventory.previousCountTimes(eq(tenantId), eq(outletId), anyList(), eq(COUNTED_AT))).thenReturn(Map.of(susu.getId(), previousCount));
        // items sharing a period start share one grouped query; an item never counted starts at the epoch
        when(inventory.movementTotals(tenantId, outletId, List.of(susu.getId()), previousCount, COUNTED_AT)).thenReturn(List.of(
                total(susu, InventoryMovementEntity.Type.SALE, "-50"), total(susu, InventoryMovementEntity.Type.SALE_REVERSAL, "5"),
                total(susu, InventoryMovementEntity.Type.WASTE, "-3"), total(susu, InventoryMovementEntity.Type.RESTOCK, "20"),
                total(susu, InventoryMovementEntity.Type.ADJUSTMENT, "2")));
        when(inventory.movementTotals(tenantId, outletId, List.of(gula.getId(), kopi.getId()), Instant.EPOCH, COUNTED_AT))
                .thenReturn(List.of(total(kopi, InventoryMovementEntity.Type.SALE, "-10")));

        FnbLeakageReportResponse report = service.report(owner, outletId, count.getId());

        assertEquals(count.getId(), report.getStockCountId());
        assertEquals(outletId, report.getOutletId());
        assertEquals(COUNTED_AT, report.getCountedAt());
        assertEquals("Akhir bulan", report.getNote());
        Map<String, FnbLeakageLineResponse> byName = report.getLines().stream()
                .collect(Collectors.toMap(FnbLeakageLineResponse::getName, Function.identity()));
        FnbLeakageLineResponse susuLine = byName.get("Susu");
        assertEquals(previousCount, susuLine.getPeriodStart());
        assertEquals(COUNTED_AT, susuLine.getPeriodEnd());
        assertDecimal("45", susuLine.getTheoreticalUsage()); // 50 sold - 5 reversed
        assertDecimal("3", susuLine.getWaste());
        assertDecimal("20", susuLine.getRestock());
        assertDecimal("2", susuLine.getManualAdjustment());
        assertDecimal("100", susuLine.getPreviousQuantity());
        assertDecimal("100", susuLine.getCountedQuantity());
        assertNull(byName.get("Gula").getPeriodStart());
        assertDecimal("0", byName.get("Gula").getTheoreticalUsage());
        assertDecimal("0", byName.get("Gula").getWaste());
        assertNull(byName.get("Kopi").getPeriodStart());
        assertDecimal("10", byName.get("Kopi").getTheoreticalUsage());
    }

    // ---------------------------------------------------------------- report

    @Test
    void reportOfTheLatestCountListsFlaggedFirstThenByLossAndTheTotalIgnoresSurplusAndUnknownCost() {
        InventoryItemEntity v = item("V");
        InventoryItemEntity w = item("W");
        InventoryItemEntity x = item("X");
        InventoryItemEntity y = item("Y");
        InventoryItemEntity z = item("Z");
        StockCountEntity count = count(outletId, null);
        when(inventory.stockCounts(tenantId, outletId, 0, 1)).thenReturn(List.of(count));
        stubCount(count, List.of(
                line(count, v, "100", "90", "5"),      // flagged, loss 50
                line(count, w, "100", "110", "2"),     // surplus, loss -20
                line(count, x, "100", "90", "3"),      // flagged, loss 30
                line(count, y, "1000", "900", "1"),    // 0.1% of usage: not flagged, loss 100
                line(count, z, "100", "90", null)),    // flagged, unknown loss
                Map.of(),
                total(v, InventoryMovementEntity.Type.SALE, "-100"), total(w, InventoryMovementEntity.Type.SALE, "-100"),
                total(x, InventoryMovementEntity.Type.SALE, "-100"), total(y, InventoryMovementEntity.Type.SALE, "-100000"),
                total(z, InventoryMovementEntity.Type.SALE, "-100"));

        FnbLeakageReportResponse report = service.report(owner, outletId, null);

        assertEquals(List.of("V", "X", "Z", "Y", "W"), report.getLines().stream().map(FnbLeakageLineResponse::getName).toList());
        assertEquals(3, report.getFlaggedCount());
        assertEquals(new BigDecimal("180.00"), report.getTotalLoss()); // 50 + 30 + 100
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportIsNotFoundWhenThereIsNoCountOrTheIdIsNotOfThisOutlet(boolean byId) {
        UUID id = UUID.randomUUID();
        if (byId) when(inventory.stockCount(tenantId, outletId, id)).thenReturn(Optional.empty());
        else when(inventory.stockCounts(tenantId, outletId, 0, 1)).thenReturn(List.of());

        assertThrows(ResourceNotFoundException.class, () -> service.report(owner, outletId, byId ? id : null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reportNeedsTheOwnerAndThePlanBeforeReadingAnything(boolean notOwner) {
        if (notOwner) when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());
        else doThrow(new PlanUpgradeRequiredException(PlanCode.PRO)).when(planEntitlementService).require(tenantId, PlanCode.PRO);

        assertThrows(RuntimeException.class, () -> service.report(owner, outletId, null));

        verifyNoInteractions(inventory);
        if (notOwner) verifyNoInteractions(planEntitlementService);
    }

    // ---------------------------------------------------------------- count history

    @ParameterizedTest
    @CsvSource({"-1, 10", "0, 0", "0, 51", "1000001, 10"})
    void stockCountHistoryRejectsAnInvalidPageBeforeAnyAccessCheck(int page, int size) {
        assertThrows(InvalidOperationException.class, () -> service.stockCounts(owner, outletId, page, size));

        verifyNoInteractions(guard, planEntitlementService, inventory);
    }

    @Test
    void stockCountHistorySummarisesEveryCountWithItsLineFlagAndLossTotals() {
        InventoryItemEntity susu = item("Susu");
        InventoryItemEntity gula = item("Gula");
        StockCountEntity count = count(outletId, "Mingguan");
        when(inventory.stockCounts(tenantId, outletId, 1, 5)).thenReturn(List.of(count));
        stubCount(count, List.of(line(count, susu, "100", "90", "3"), line(count, gula, "5", "6", "2")), Map.of(),
                total(susu, InventoryMovementEntity.Type.SALE, "-100"), total(gula, InventoryMovementEntity.Type.SALE, "-100"));

        FnbStockCountSummaryResponse summary = service.stockCounts(owner, outletId, 1, 5).get(0);

        assertEquals(count.getId(), summary.getStockCountId());
        assertEquals("Mingguan", summary.getNote());
        assertEquals(COUNTED_AT, summary.getCountedAt());
        assertEquals(2, summary.getLineCount());
        assertEquals(1, summary.getFlaggedCount());
        assertEquals(new BigDecimal("30.00"), summary.getTotalLoss());
    }

    // ---------------------------------------------------------------- consolidated

    @Test
    void consolidatedRejectsAnInvalidRangeFromTheSalesReportServiceBeforeReadingAnyOutlet() {
        LocalDate from = LocalDate.of(2026, 11, 7);
        LocalDate to = LocalDate.of(2026, 11, 1);
        doThrow(new InvalidOperationException("Rentang tanggal laporan tidak valid.")).when(salesReports).requireValidRange(from, to);

        assertThrows(InvalidOperationException.class, () -> service.consolidated(owner, from, to));

        verify(planEntitlementService, never()).usableOutletIds(any(), any());
        verifyNoInteractions(accessRepository, inventory);
    }

    @Test
    void consolidatedRanksOwnedOutletsByLossReadingTheDaysInEachOutletZoneAndSkipsOthers() {
        OutletEntity kopiA = outlet(UUID.randomUUID(), "Kopi A", "Asia/Jakarta");
        OutletEntity kopiB = outlet(UUID.randomUUID(), "Kopi B", "Asia/Makassar");
        OutletEntity kopiC = outlet(UUID.randomUUID(), "Kopi C", "Asia/Jakarta");        // owner, no counts: omitted
        UUID noAccess = UUID.randomUUID();                                                // no access row: skipped
        OutletEntity kopiE = outlet(UUID.randomUUID(), "Kopi E", "Asia/Jakarta");        // cashier there: skipped
        when(planEntitlementService.usableOutletIds(eq(tenantId), any(Instant.class)))
                .thenReturn(List.of(kopiA.getId(), kopiB.getId(), kopiC.getId(), noAccess, kopiE.getId()));
        for (OutletEntity ownedOutlet : List.of(kopiA, kopiB, kopiC))
            when(accessRepository.findOutlet(eq(userId), eq(tenantId), eq(ownedOutlet.getId()), eq(Set.of(Role.OWNERMERCHANT)), any(Instant.class)))
                    .thenReturn(Optional.of(new MerchantAccessRepository.OutletAccess(TenantMembershipEntity.grantOwner(null, tenant), ownedOutlet, true)));
        when(accessRepository.findOutlet(eq(userId), eq(tenantId), eq(noAccess), any(), any(Instant.class))).thenReturn(Optional.empty());
        when(accessRepository.findOutlet(eq(userId), eq(tenantId), eq(kopiE.getId()), any(), any(Instant.class)))
                .thenReturn(Optional.of(new MerchantAccessRepository.OutletAccess(TenantMembershipEntity.grantCashier(null, tenant), kopiE, true)));
        // 2026-11-01..2026-11-30 (30 days ending at `to`): Jakarta is UTC+7, Makassar UTC+8
        Instant jakartaStart = Instant.parse("2026-10-31T17:00:00Z");
        Instant jakartaEnd = Instant.parse("2026-11-30T17:00:00Z");
        Instant makassarStart = Instant.parse("2026-10-31T16:00:00Z");
        Instant makassarEnd = Instant.parse("2026-11-30T16:00:00Z");
        StockCountEntity countA = count(kopiA.getId(), null);
        StockCountEntity countB = count(kopiB.getId(), null);
        InventoryItemEntity susu = item("Susu");
        InventoryItemEntity gula = item("Gula");
        when(inventory.stockCountsBetween(tenantId, kopiA.getId(), jakartaStart, jakartaEnd)).thenReturn(List.of(countA));
        when(inventory.stockCountsBetween(tenantId, kopiB.getId(), makassarStart, makassarEnd)).thenReturn(List.of(countB));
        when(inventory.stockCountsBetween(tenantId, kopiC.getId(), jakartaStart, jakartaEnd)).thenReturn(List.of());
        stubCount(countA, List.of(line(countA, susu, "100", "90", "3")), Map.of());          // loss 30.00
        stubCount(countB, List.of(line(countB, gula, "100", "90", "10")), Map.of());         // loss 100.00

        FnbLeakageConsolidatedResponse result = service.consolidated(owner, null, LocalDate.of(2026, 11, 30));

        verify(salesReports).requireValidRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        assertEquals(LocalDate.of(2026, 11, 1), result.getFrom());
        assertEquals(LocalDate.of(2026, 11, 30), result.getTo());
        assertEquals(List.of("Kopi B", "Kopi A"), result.getOutlets().stream().map(FnbOutletLeakageResponse::getOutletName).toList());
        assertEquals(new BigDecimal("130.00"), result.getTotalLoss());
        FnbOutletLeakageResponse top = result.getOutlets().get(0);
        assertEquals(1, top.getStockCountCount());
        assertEquals(1, top.getFlaggedCount());
        assertEquals(new BigDecimal("100.00"), top.getTotalLoss());
    }

    // ---------------------------------------------------------------- outletsLeakage (gRPC)

    @Test
    void outletsLeakageRanksByLossOmitsOutletsWithoutCountsAndKeepsOnlyTheTopFlaggedLines() {
        OutletEntity kopiA = outlet(UUID.randomUUID(), "Kopi A", "Asia/Jakarta");
        OutletEntity kopiB = outlet(UUID.randomUUID(), "Kopi B", "Asia/Jakarta");
        OutletEntity kopiC = outlet(UUID.randomUUID(), "Kopi C", "Asia/Jakarta");
        UUID foreign = UUID.randomUUID();
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-01T00:00:00Z");
        when(outlets.findInTenant(tenantId, kopiA.getId())).thenReturn(Optional.of(kopiA));
        when(outlets.findInTenant(tenantId, kopiB.getId())).thenReturn(Optional.of(kopiB));
        when(outlets.findInTenant(tenantId, kopiC.getId())).thenReturn(Optional.of(kopiC));
        when(outlets.findInTenant(tenantId, foreign)).thenReturn(Optional.empty());
        StockCountEntity countA = count(kopiA.getId(), null);
        StockCountEntity countB = count(kopiB.getId(), null);
        when(inventory.stockCountsBetween(tenantId, kopiA.getId(), from, to)).thenReturn(List.of(countA));
        when(inventory.stockCountsBetween(tenantId, kopiB.getId(), from, to)).thenReturn(List.of(countB));
        when(inventory.stockCountsBetween(tenantId, kopiC.getId(), from, to)).thenReturn(List.of());
        // no usage in the period, so every shortage is flagged; A has 4 flagged lines (one with unknown loss) and a surplus
        stubCount(countA, List.of(line(countA, item("L1"), "10", "0", "1"), line(countA, item("L2"), "10", "0", "2"),
                line(countA, item("L3"), "10", "0", "3"), line(countA, item("L4"), "10", "0", null),
                line(countA, item("S"), "10", "20", "1")), Map.of());
        stubCount(countB, List.of(line(countB, item("M1"), "10", "0", "10")), Map.of());

        List<FnbOutletLeakageResponse> result = service.outletsLeakage(tenantId,
                List.of(kopiA.getId(), kopiB.getId(), kopiC.getId(), foreign), from, to, 3);

        assertEquals(List.of("Kopi B", "Kopi A"), result.stream().map(FnbOutletLeakageResponse::getOutletName).toList());
        FnbOutletLeakageResponse a = result.get(1);
        assertEquals(1, a.getStockCountCount());
        assertEquals(4, a.getFlaggedCount());
        assertEquals(new BigDecimal("60.00"), a.getTotalLoss());
        assertEquals(List.of("L3", "L2", "L1"), a.getLines().stream().map(FnbLeakageLineResponse::getName).toList());
        assertEquals(new BigDecimal("100.00"), result.get(0).getTotalLoss());
        assertNotNull(result.get(0).getOutletId());
    }

    // ---------------------------------------------------------------- helpers

    private OutletEntity outlet(UUID id, String name, String zone) {
        OutletEntity value = OutletEntity.open(tenant, name, zone, null, null);
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private InventoryItemEntity item(String name) {
        InventoryItemEntity item = InventoryItemEntity.create(tenant, outlet, null, name, "ml", BigDecimal.ONE);
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        return item;
    }

    private StockCountEntity count(UUID forOutlet, String note) {
        StockCountEntity count = StockCountEntity.record(tenantId, forOutlet, note, "k-" + UUID.randomUUID(), "fp", UUID.randomUUID());
        ReflectionTestUtils.setField(count, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(count, "countedAt", COUNTED_AT);
        return count;
    }

    private StockCountLineEntity line(StockCountEntity count, InventoryItemEntity item, String previous, String counted, String unitCost) {
        BigDecimal previousQuantity = new BigDecimal(previous);
        BigDecimal countedQuantity = new BigDecimal(counted);
        return StockCountLineEntity.record(tenantId, count, item, previousQuantity, countedQuantity,
                countedQuantity.subtract(previousQuantity), unitCost == null ? null : new BigDecimal(unitCost), null);
    }

    /** Stubs the three lookups behind a count's lines: stored lines, previous count times, and the movement totals of any item list. */
    private void stubCount(StockCountEntity count, List<StockCountLineEntity> lines, Map<UUID, Instant> previousCounts,
                           FnbInventoryRepository.MovementTotal... totals) {
        when(inventory.stockCountLines(tenantId, count.getId())).thenReturn(lines);
        when(inventory.previousCountTimes(eq(tenantId), eq(count.getOutletId()), anyList(), eq(count.getCountedAt()))).thenReturn(previousCounts);
        when(inventory.movementTotals(eq(tenantId), eq(count.getOutletId()), anyList(), any(Instant.class), eq(count.getCountedAt())))
                .thenReturn(List.of(totals));
    }

    // MovementTotal has a package-private constructor (it is a repository projection), so it is built reflectively.
    private static FnbInventoryRepository.MovementTotal total(InventoryItemEntity item, InventoryMovementEntity.Type type, String quantity) {
        try {
            Constructor<FnbInventoryRepository.MovementTotal> constructor = FnbInventoryRepository.MovementTotal.class
                    .getDeclaredConstructor(UUID.class, InventoryMovementEntity.Type.class, BigDecimal.class);
            constructor.setAccessible(true);
            return constructor.newInstance(item.getId(), type, new BigDecimal(quantity));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }
}
