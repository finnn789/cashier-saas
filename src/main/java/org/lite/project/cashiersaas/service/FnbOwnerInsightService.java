package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.dto.response.FnbCashierAnomaly;
import org.lite.project.cashiersaas.dto.response.FnbOwnerSalesInsight;
import org.lite.project.cashiersaas.dto.response.FnbRestockSuggestion;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbCashExpenseRepository;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbShiftRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only owner insights; callers pass an already authorised context (no guard here). */
@Service
@RequiredArgsConstructor
public class FnbOwnerInsightService {
    private static final int TOP_PRODUCTS = 5;
    private static final int USAGE_DAYS = 14;
    private static final int COVER_DAYS = 7;
    private static final int BASELINE_DAYS = 30;
    private static final BigDecimal MIN_COUNT = BigDecimal.valueOf(3);
    private static final BigDecimal MIN_CASH_DIFFERENCE = BigDecimal.valueOf(20000);
    private static final List<InventoryMovementEntity.Type> USAGE_TYPES = List.of(InventoryMovementEntity.Type.SALE,
            InventoryMovementEntity.Type.SALE_REVERSAL, InventoryMovementEntity.Type.WASTE);
    private static final List<InventoryBalanceEntity.Status> STATUS_ORDER = List.of(InventoryBalanceEntity.Status.NEGATIVE,
            InventoryBalanceEntity.Status.OUT, InventoryBalanceEntity.Status.LOW, InventoryBalanceEntity.Status.OK);
    private final FnbSalesReportService salesReports;
    private final FnbShiftRepository shifts;
    private final FnbCashExpenseRepository expenses;
    private final FnbInventoryRepository inventory;
    private final AuditLogRepository audits;
    private final FnbOrderRepository orders;
    private final TenantMembershipRepository memberships;

    @Transactional(readOnly = true)
    public FnbOwnerSalesInsight salesInsight(MerchantAccessContext context, LocalDate start, LocalDate end) {
        UUID tenantId = context.getTenant().getId();
        UUID outletId = context.getOutlet().getId();
        FnbOwnerSalesInsight value = new FnbOwnerSalesInsight();
        value.setReport(salesReports.summarize(context, start, end, TOP_PRODUCTS)); // validates the range and zone
        ZoneId zone = ZoneId.of(value.getReport().getTimeZone());
        Instant from = start.atStartOfDay(zone).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(zone).toInstant();
        value.setDiscountedOrderCount(orders.discountedPaidOrdersByReceiver(tenantId, outletId, from, to)
                .values().stream().mapToLong(Long::longValue).sum());
        value.setExpensesTotal(expenses.totalForOutlet(tenantId, outletId, from, to));
        FnbShiftRepository.ClosedTotals closed = shifts.closedTotals(tenantId, outletId, from, to);
        value.setCashDifferenceTotal(closed.getCashDifference());
        value.setClosedShiftCount(closed.getCount());
        return value;
    }

    @Transactional(readOnly = true)
    public List<FnbRestockSuggestion> restock(MerchantAccessContext context) {
        UUID tenantId = context.getTenant().getId();
        UUID outletId = context.getOutlet().getId();
        List<InventoryItemEntity> items = inventory.items(tenantId, outletId);
        Map<UUID, InventoryBalanceEntity> balances = new HashMap<>();
        for (InventoryBalanceEntity balance : inventory.balances(tenantId, outletId, items.stream().map(InventoryItemEntity::getId).toList()))
            balances.put(balance.getInventoryItem().getId(), balance);
        Map<UUID, BigDecimal> netDelta = inventory.netDeltaByItem(tenantId, outletId,
                Instant.now().minus(Duration.ofDays(USAGE_DAYS)), USAGE_TYPES);
        List<FnbRestockSuggestion> result = new ArrayList<>();
        for (InventoryItemEntity item : items) {
            InventoryBalanceEntity balance = balances.get(item.getId());
            BigDecimal onHand = balance == null ? BigDecimal.ZERO : balance.getQuantityOnHand();
            InventoryBalanceEntity.Status status = balance == null ? InventoryBalanceEntity.Status.OUT : balance.status();
            BigDecimal usage = netDelta.getOrDefault(item.getId(), BigDecimal.ZERO).negate().max(BigDecimal.ZERO);
            BigDecimal daily = usage.divide(BigDecimal.valueOf(USAGE_DAYS), 3, RoundingMode.HALF_UP);
            BigDecimal suggested = daily.multiply(BigDecimal.valueOf(COVER_DAYS)).add(item.getReorderLevel()).subtract(onHand)
                    .max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
            if (suggested.signum() == 0 && status == InventoryBalanceEntity.Status.OK) continue;
            FnbRestockSuggestion suggestion = new FnbRestockSuggestion();
            suggestion.setInventoryItemId(item.getId());
            suggestion.setName(item.getName());
            suggestion.setUnit(item.getUnit());
            suggestion.setQuantityOnHand(onHand);
            suggestion.setReorderLevel(item.getReorderLevel());
            suggestion.setAverageDailyUsage(daily);
            suggestion.setSuggestedQuantity(suggested);
            suggestion.setStockStatus(status);
            result.add(suggestion);
        }
        result.sort(Comparator.comparingInt((FnbRestockSuggestion suggestion) -> STATUS_ORDER.indexOf(suggestion.getStockStatus()))
                .thenComparing(FnbRestockSuggestion::getName));
        return result;
    }

    @Transactional(readOnly = true)
    public List<FnbCashierAnomaly> anomalies(MerchantAccessContext context, LocalDate date) {
        UUID tenantId = context.getTenant().getId();
        UUID outletId = context.getOutlet().getId();
        ZoneId zone;
        try { zone = ZoneId.of(context.getOutlet().getTimeZone()); } catch (DateTimeException exception) { throw new InvalidOperationException("Zona waktu outlet tidak valid."); }
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        Instant baseStart = date.minusDays(BASELINE_DAYS).atStartOfDay(zone).toInstant();
        Map<UUID, Long> cancelToday = audits.countByActor(tenantId, outletId, AuditLogEntity.Action.FNB_ORDER_CANCELLED, dayStart, dayEnd);
        Map<UUID, Long> cancelBase = audits.countByActor(tenantId, outletId, AuditLogEntity.Action.FNB_ORDER_CANCELLED, baseStart, dayStart);
        Map<UUID, Long> discountToday = orders.discountedPaidOrdersByReceiver(tenantId, outletId, dayStart, dayEnd);
        Map<UUID, Long> discountBase = orders.discountedPaidOrdersByReceiver(tenantId, outletId, baseStart, dayStart);
        Map<UUID, BigDecimal> cashToday = shifts.absCashDifferenceByUser(tenantId, outletId, dayStart, dayEnd);
        Map<UUID, BigDecimal> cashBase = shifts.absCashDifferenceByUser(tenantId, outletId, baseStart, dayStart);
        List<FnbCashierAnomaly> result = new ArrayList<>();
        for (TenantMembershipEntity cashier : memberships.listCashiersForOutlet(tenantId, outletId)) { // owners are never listed
            UUID userId = cashier.getUser().getId();
            String name = cashier.getUser().getFullName();
            flag(result, userId, name, "CANCELLATIONS", count(cancelToday, userId), count(cancelBase, userId), MIN_COUNT);
            flag(result, userId, name, "DISCOUNTS", count(discountToday, userId), count(discountBase, userId), MIN_COUNT);
            flag(result, userId, name, "CASH_DIFFERENCE", cashToday.getOrDefault(userId, BigDecimal.ZERO),
                    cashBase.getOrDefault(userId, BigDecimal.ZERO), MIN_CASH_DIFFERENCE);
        }
        return result;
    }

    private static BigDecimal count(Map<UUID, Long> counts, UUID userId) {
        return BigDecimal.valueOf(counts.getOrDefault(userId, 0L));
    }

    // Anomaly = value above twice the 30-day daily average and at least the minimum.
    private static void flag(List<FnbCashierAnomaly> result, UUID userId, String name, String kind,
                             BigDecimal value, BigDecimal baselineTotal, BigDecimal minimum) {
        BigDecimal average = baselineTotal.divide(BigDecimal.valueOf(BASELINE_DAYS), 2, RoundingMode.HALF_UP);
        if (value.compareTo(minimum) < 0 || value.compareTo(average.multiply(BigDecimal.valueOf(2))) <= 0) return;
        FnbCashierAnomaly anomaly = new FnbCashierAnomaly();
        anomaly.setStaffUserId(userId);
        anomaly.setFullName(name);
        anomaly.setKind(kind);
        anomaly.setValue(value);
        anomaly.setBaselineDailyAverage(average);
        result.add(anomaly);
    }
}
