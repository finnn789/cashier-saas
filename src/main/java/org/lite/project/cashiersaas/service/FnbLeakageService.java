package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbLeakageConsolidatedResponse;
import org.lite.project.cashiersaas.dto.response.FnbLeakageLineResponse;
import org.lite.project.cashiersaas.dto.response.FnbLeakageReportResponse;
import org.lite.project.cashiersaas.dto.response.FnbOutletLeakageResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountSummaryResponse;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.StockCountEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.StockCountLineEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.MerchantAccessRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ingredient leakage from stock counts (PRD v3 FR-AI-02). The ledger balance before a count already equals
 * start + restock - sales(recipe) - waste +/- adjustments, so the shortage of an item is previous - counted.
 * Read-only; the REST entry points authorise the owner and the PRO plan, {@link #outletsLeakage} expects an authorised caller.
 */
@Service
@RequiredArgsConstructor
public class FnbLeakageService {
    private static final BigDecimal FLAG_THRESHOLD_PCT = new BigDecimal("5.00");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int TOP_LINES = 10;
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_SIZE = 50;
    private static final int MAX_PAGE = 1_000_000;
    private static final Comparator<BigDecimal> LOSS_DESC_NULLS_LAST = Comparator.nullsLast(Comparator.reverseOrder());
    private static final Comparator<FnbLeakageLineResponse> BY_LOSS = Comparator
            .comparing(FnbLeakageLineResponse::getLossAmount, LOSS_DESC_NULLS_LAST).thenComparing(FnbLeakageLineResponse::getName);
    private static final Comparator<FnbLeakageLineResponse> FLAGGED_FIRST = Comparator
            .comparing(FnbLeakageLineResponse::isFlagged, Comparator.reverseOrder()).thenComparing(BY_LOSS);
    private static final Comparator<FnbOutletLeakageResponse> BY_OUTLET_LOSS = Comparator
            .comparing(FnbOutletLeakageResponse::getTotalLoss, Comparator.<BigDecimal>reverseOrder()).thenComparing(FnbOutletLeakageResponse::getOutletName);
    private final MerchantAccessGuard guard;
    private final PlanEntitlementService planEntitlementService;
    private final FnbInventoryRepository inventory;
    private final MerchantAccessRepository accessRepository;
    private final OutletRepository outlets;
    private final FnbSalesReportService salesReports;

    /** Owner report of one count (the latest when {@code stockCountId} is null); 404 when there is none or it is not of this outlet. */
    @Transactional(readOnly = true)
    public FnbLeakageReportResponse report(TenantPrincipal principal, UUID outletId, UUID stockCountId) {
        guard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        UUID tenantId = principal.getTenantId();
        StockCountEntity count = stockCountId == null
                ? inventory.stockCounts(tenantId, outletId, 0, 1).stream().findFirst().orElseThrow(ResourceNotFoundException::new)
                : inventory.stockCount(tenantId, outletId, stockCountId).orElseThrow(ResourceNotFoundException::new);
        List<FnbLeakageLineResponse> lines = lines(tenantId, count);
        FnbLeakageReportResponse report = new FnbLeakageReportResponse();
        report.setStockCountId(count.getId()); report.setOutletId(outletId);
        report.setCountedAt(count.getCountedAt()); report.setNote(count.getNote());
        report.setTotalLoss(totalLoss(lines)); report.setFlaggedCount(flaggedCount(lines));
        report.setLines(lines.stream().sorted(FLAGGED_FIRST).toList());
        return report;
    }

    @Transactional(readOnly = true)
    public List<FnbStockCountSummaryResponse> stockCounts(TenantPrincipal principal, UUID outletId, int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE)
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        guard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        List<FnbStockCountSummaryResponse> result = new ArrayList<>();
        for (StockCountEntity count : inventory.stockCounts(principal.getTenantId(), outletId, page, size)) {
            List<FnbLeakageLineResponse> lines = lines(principal.getTenantId(), count);
            FnbStockCountSummaryResponse summary = new FnbStockCountSummaryResponse();
            summary.setStockCountId(count.getId()); summary.setCountedAt(count.getCountedAt()); summary.setNote(count.getNote());
            summary.setLineCount(lines.size()); summary.setFlaggedCount(flaggedCount(lines)); summary.setTotalLoss(totalLoss(lines));
            result.add(summary);
        }
        return result;
    }

    /**
     * Owner-only ranking over every usable outlet the owner can access, for the calendar days {@code from..to}
     * (default: the last 30 days ending today in the caller's outlet zone); each outlet reads the days in its own zone.
     */
    @Transactional(readOnly = true)
    public FnbLeakageConsolidatedResponse consolidated(TenantPrincipal principal, LocalDate from, LocalDate to) {
        MerchantAccessContext context = guard.requireOwnerIgnoringAllowance(principal, principal.getOutletId());
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        LocalDate end = to == null ? LocalDate.now(zone(context.getOutlet())) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_RANGE_DAYS - 1L) : from;
        salesReports.requireValidRange(start, end);
        Instant now = Instant.now();
        List<FnbOutletLeakageResponse> result = new ArrayList<>();
        for (UUID outletId : planEntitlementService.usableOutletIds(principal.getTenantId(), now)) {
            MerchantAccessRepository.OutletAccess access = accessRepository.findOutlet(principal.getUserId(),
                    principal.getTenantId(), outletId, principal.getRoles(), now).orElse(null);
            if (access == null || access.getMembership().getRole() != Role.OWNERMERCHANT) continue;
            ZoneId zone = zone(access.getOutlet());
            FnbOutletLeakageResponse outlet = outletLeakage(principal.getTenantId(), access.getOutlet(),
                    start.atStartOfDay(zone).toInstant(), end.plusDays(1).atStartOfDay(zone).toInstant(), TOP_LINES);
            if (outlet != null) result.add(outlet);
        }
        result.sort(BY_OUTLET_LOSS);
        FnbLeakageConsolidatedResponse value = new FnbLeakageConsolidatedResponse();
        value.setFrom(start); value.setTo(end); value.setOutlets(result);
        value.setTotalLoss(result.stream().map(FnbOutletLeakageResponse::getTotalLoss).reduce(zeroMoney(), BigDecimal::add));
        return value;
    }

    /**
     * One entry per outlet of {@code outletIds} with at least one stock count whose counted_at is in {@code [from, to)},
     * sorted by total loss descending; {@code lines} = up to {@code topLines} flagged lines by loss descending (nulls last).
     * The caller has already authorised tenant and outlets.
     */
    @Transactional(readOnly = true)
    public List<FnbOutletLeakageResponse> outletsLeakage(UUID tenantId, List<UUID> outletIds, Instant from, Instant to, int topLines) {
        List<FnbOutletLeakageResponse> result = new ArrayList<>();
        for (UUID outletId : outletIds) {
            OutletEntity outlet = outlets.findInTenant(tenantId, outletId).orElse(null);
            FnbOutletLeakageResponse value = outlet == null ? null : outletLeakage(tenantId, outlet, from, to, topLines);
            if (value != null) result.add(value);
        }
        result.sort(BY_OUTLET_LOSS);
        return result;
    }

    private FnbOutletLeakageResponse outletLeakage(UUID tenantId, OutletEntity outlet, Instant from, Instant to, int topLines) {
        List<StockCountEntity> counts = inventory.stockCountsBetween(tenantId, outlet.getId(), from, to);
        if (counts.isEmpty()) return null;
        List<FnbLeakageLineResponse> flagged = new ArrayList<>();
        BigDecimal totalLoss = zeroMoney();
        int flaggedCount = 0;
        for (StockCountEntity count : counts) {
            List<FnbLeakageLineResponse> lines = lines(tenantId, count);
            totalLoss = totalLoss.add(totalLoss(lines));
            for (FnbLeakageLineResponse line : lines) {
                if (!line.isFlagged()) continue;
                flaggedCount++;
                flagged.add(line);
            }
        }
        FnbOutletLeakageResponse value = new FnbOutletLeakageResponse();
        value.setOutletId(outlet.getId()); value.setOutletName(outlet.getName());
        value.setStockCountCount(counts.size()); value.setTotalLoss(totalLoss); value.setFlaggedCount(flaggedCount);
        value.setLines(flagged.stream().sorted(BY_LOSS).limit(topLines).toList());
        return value;
    }

    /** Leakage of every item of one count; each item's period runs from its previous count (exclusive) to this one (inclusive). */
    private List<FnbLeakageLineResponse> lines(UUID tenantId, StockCountEntity count) {
        List<StockCountLineEntity> stored = inventory.stockCountLines(tenantId, count.getId());
        List<UUID> itemIds = stored.stream().map(line -> line.getInventoryItem().getId()).toList();
        Map<UUID, Instant> previousCounts = inventory.previousCountTimes(tenantId, count.getOutletId(), itemIds, count.getCountedAt());
        // Items sharing a period start share one grouped movement query (a full count is a single group).
        Map<Instant, List<UUID>> byStart = new HashMap<>();
        for (UUID itemId : itemIds) byStart.computeIfAbsent(previousCounts.getOrDefault(itemId, Instant.EPOCH), start -> new ArrayList<>()).add(itemId);
        Map<UUID, Map<InventoryMovementEntity.Type, BigDecimal>> totals = new HashMap<>();
        for (Map.Entry<Instant, List<UUID>> group : byStart.entrySet())
            for (FnbInventoryRepository.MovementTotal total : inventory.movementTotals(tenantId, count.getOutletId(),
                    group.getValue(), group.getKey(), count.getCountedAt()))
                totals.computeIfAbsent(total.itemId, id -> new EnumMap<>(InventoryMovementEntity.Type.class)).put(total.type, total.total);
        List<FnbLeakageLineResponse> result = new ArrayList<>();
        for (StockCountLineEntity line : stored) {
            Map<InventoryMovementEntity.Type, BigDecimal> sums = totals.getOrDefault(line.getInventoryItem().getId(), Map.of());
            BigDecimal theoretical = sum(sums, InventoryMovementEntity.Type.SALE).add(sum(sums, InventoryMovementEntity.Type.SALE_REVERSAL)).negate();
            BigDecimal shortage = line.getPreviousQuantity().subtract(line.getCountedQuantity());
            // No net usage in the period: a percentage is undefined, so any shortage is flagged.
            boolean noUsage = theoretical.signum() <= 0;
            BigDecimal variancePct = noUsage ? null : shortage.multiply(HUNDRED).divide(theoretical, 2, RoundingMode.HALF_UP);
            FnbLeakageLineResponse value = new FnbLeakageLineResponse();
            value.setInventoryItemId(line.getInventoryItem().getId()); value.setName(line.getInventoryItem().getName());
            value.setUnit(line.getInventoryItem().getUnit());
            value.setPeriodStart(previousCounts.get(line.getInventoryItem().getId())); value.setPeriodEnd(count.getCountedAt());
            value.setTheoreticalUsage(theoretical); value.setWaste(sum(sums, InventoryMovementEntity.Type.WASTE).negate());
            value.setRestock(sum(sums, InventoryMovementEntity.Type.RESTOCK));
            value.setManualAdjustment(sum(sums, InventoryMovementEntity.Type.ADJUSTMENT));
            value.setPreviousQuantity(line.getPreviousQuantity()); value.setCountedQuantity(line.getCountedQuantity());
            value.setShortage(shortage); value.setVariancePct(variancePct); value.setUnitCost(line.getUnitCost());
            value.setLossAmount(line.getUnitCost() == null ? null : shortage.multiply(line.getUnitCost()).setScale(2, RoundingMode.HALF_UP));
            value.setFlagged(shortage.signum() > 0 && (noUsage || variancePct.compareTo(FLAG_THRESHOLD_PCT) > 0));
            result.add(value);
        }
        return result;
    }

    private static BigDecimal sum(Map<InventoryMovementEntity.Type, BigDecimal> sums, InventoryMovementEntity.Type type) {
        return sums.getOrDefault(type, BigDecimal.ZERO).setScale(3);
    }

    // Loss of the surplus lines (negative) is not netted against the shortages.
    private static BigDecimal totalLoss(List<FnbLeakageLineResponse> lines) {
        return lines.stream().map(FnbLeakageLineResponse::getLossAmount).filter(loss -> loss != null && loss.signum() > 0)
                .reduce(zeroMoney(), BigDecimal::add);
    }

    private static int flaggedCount(List<FnbLeakageLineResponse> lines) {
        return (int) lines.stream().filter(FnbLeakageLineResponse::isFlagged).count();
    }

    private static BigDecimal zeroMoney() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static ZoneId zone(OutletEntity outlet) {
        try { return ZoneId.of(outlet.getTimeZone()); } catch (DateTimeException exception) { throw new InvalidOperationException("Zona waktu outlet tidak valid."); }
    }
}
