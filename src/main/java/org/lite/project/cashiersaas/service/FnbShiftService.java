package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbShiftMapper;
import org.lite.project.cashiersaas.dto.request.FnbCashExpenseRequest;
import org.lite.project.cashiersaas.dto.request.FnbShiftCloseRequest;
import org.lite.project.cashiersaas.dto.request.FnbShiftOpenRequest;
import org.lite.project.cashiersaas.dto.response.FnbCashExpenseResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftOpeningSuggestionResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftPageResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftResponse;
import org.lite.project.cashiersaas.entity.fnb.CashExpenseEntity;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbCashExpenseRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FnbShiftService {
    private static final int MAX_DAYS = 366;
    private static final int MAX_PAGE = 1_000_000;
    private static final int MAX_SIZE = 50;
    private final MerchantAccessGuard guard;
    private final FnbShiftRepository shifts;
    private final FnbOrderRepository orders;
    private final AuditLogRepository audit;
    private final FnbCashExpenseRepository expenses;
    private final DigestService digest;
    private final DailySummaryService dailySummary;

    /** The caller's own open shift in the context outlet; callers hold the tenant lock when the result drives a write. */
    public Optional<CashShiftEntity> findOpenShift(MerchantAccessContext context) {
        return shifts.openFor(context.getTenant().getId(), context.getOutlet().getId(), context.getMembership().getId());
    }

    public CashShiftEntity requireOpenShift(MerchantAccessContext context) {
        return requireOpenShift(context, "Buka shift terlebih dahulu sebelum menerima pembayaran.");
    }

    public CashShiftEntity requireOpenShift(MerchantAccessContext context, String message) {
        return findOpenShift(context).orElseThrow(() -> new OperationConflictException(message));
    }

    @Transactional
    public FnbShiftResponse open(TenantPrincipal principal, UUID outletId, FnbShiftOpenRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        if (findOpenShift(context).isPresent()) throw new OperationConflictException("Anda masih memiliki shift terbuka.");
        CashShiftEntity shift = CashShiftEntity.open(context.getTenant(), context.getOutlet(), context.getMembership(),
                request.getOpeningCash());
        shifts.persist(shift);
        orders.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(),
                context.getMembership().getUser(), AuditLogEntity.Action.SHIFT_OPENED, shift.getId(), null,
                "openingCash=" + shift.getOpeningCash().toPlainString()));
        return response(shift, orders.countOpenUnpaid(principal.getTenantId(), outletId), true);
    }

    @Transactional(readOnly = true)
    public FnbShiftResponse current(TenantPrincipal principal, UUID outletId) {
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        CashShiftEntity shift = findOpenShift(context).orElseThrow(ResourceNotFoundException::new);
        return response(shift, orders.countOpenUnpaid(principal.getTenantId(), outletId), true);
    }

    @Transactional
    public FnbShiftResponse closeCurrent(TenantPrincipal principal, UUID outletId, FnbShiftCloseRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        CashShiftEntity shift = findOpenShift(context).orElseThrow(ResourceNotFoundException::new);
        return close(context, shift, request);
    }

    @Transactional
    public FnbShiftResponse forceClose(TenantPrincipal principal, UUID outletId, UUID shiftId, FnbShiftCloseRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        CashShiftEntity shift = shifts.byId(principal.getTenantId(), outletId, shiftId).orElseThrow(ResourceNotFoundException::new);
        return close(context, shift, request);
    }

    @Transactional(readOnly = true)
    public FnbShiftPageResponse list(TenantPrincipal principal, UUID outletId, LocalDate from, LocalDate to, int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE)
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        ZoneId zone;
        try { zone = ZoneId.of(context.getOutlet().getTimeZone()); }
        catch (DateTimeException exception) { throw new InvalidOperationException("Zona waktu outlet tidak valid."); }
        LocalDate today = LocalDate.now(zone);
        LocalDate startDate = from == null ? today : from;
        LocalDate endDate = to == null ? today : to;
        if (endDate.isBefore(startDate) || ChronoUnit.DAYS.between(startDate, endDate) >= MAX_DAYS)
            throw new InvalidOperationException("Rentang tanggal shift tidak valid.");
        Instant start;
        Instant end;
        try {
            start = startDate.atStartOfDay(zone).toInstant();
            end = endDate.plusDays(1).atStartOfDay(zone).toInstant();
        } catch (DateTimeException exception) {
            throw new InvalidOperationException("Rentang tanggal shift tidak valid.");
        }
        List<CashShiftEntity> probe = shifts.page(principal.getTenantId(), outletId, start, end, page, size);
        boolean hasNext = probe.size() > size;
        List<CashShiftEntity> pageShifts = hasNext ? probe.subList(0, size) : probe;
        int openOrders = orders.countOpenUnpaid(principal.getTenantId(), outletId);
        FnbShiftPageResponse response = new FnbShiftPageResponse();
        response.setShifts(pageShifts.stream().map(shift -> response(shift, openOrders, false)).toList());
        response.setPage(page);
        response.setSize(size);
        response.setHasNext(hasNext);
        return response;
    }

    @Transactional(readOnly = true)
    public FnbShiftResponse detail(TenantPrincipal principal, UUID outletId, UUID shiftId) {
        guard.requireOwner(principal, outletId);
        CashShiftEntity shift = shifts.byId(principal.getTenantId(), outletId, shiftId).orElseThrow(ResourceNotFoundException::new);
        return response(shift, orders.countOpenUnpaid(principal.getTenantId(), outletId), true);
    }

    // The callers already hold the tenant lock; the shift lock refreshes the row so a concurrent close is seen.
    private FnbShiftResponse close(MerchantAccessContext context, CashShiftEntity shift, FnbShiftCloseRequest request) {
        shifts.lock(shift);
        FnbShiftRepository.CashSummary summary = shifts.cashSummary(shift.getId());
        shift.close(context.getMembership().getUser(), expected(shift, summary, expenses.totalForShift(shift.getId())), request.getCountedCash(), request.getNote());
        orders.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(),
                context.getMembership().getUser(), AuditLogEntity.Action.SHIFT_CLOSED, shift.getId(), null,
                "expected=" + shift.getExpectedCash().toPlainString() + " counted=" + shift.getCountedCash().toPlainString()
                        + " difference=" + shift.getCashDifference().toPlainString()));
        dailySummary.onShiftClosed(context, shift); // shared by closeCurrent and forceClose
        return response(shift, orders.countOpenUnpaid(context.getTenant().getId(), context.getOutlet().getId()), true);
    }

    private FnbShiftResponse response(CashShiftEntity shift, int openOrderCount, boolean withExpenses) {
        FnbShiftRepository.CashSummary summary = shifts.cashSummary(shift.getId());
        BigDecimal expensesTotal = expenses.totalForShift(shift.getId());
        BigDecimal expected = shift.isOpen() ? expected(shift, summary, expensesTotal) : shift.getExpectedCash();
        return FnbShiftMapper.shift(shift, summary, expected, openOrderCount, expensesTotal,
                withExpenses ? expenses.forShift(shift.getId()) : null);
    }

    private static BigDecimal expected(CashShiftEntity shift, FnbShiftRepository.CashSummary summary, BigDecimal expensesTotal) {
        return shift.getOpeningCash().add(summary.getCashIn()).subtract(summary.getCashRefunds()).subtract(expensesTotal);
    }

    @Transactional(readOnly = true)
    public FnbShiftOpeningSuggestionResponse openingSuggestion(TenantPrincipal principal, UUID outletId) {
        guard.requireOutlet(principal, outletId);
        FnbShiftOpeningSuggestionResponse response = new FnbShiftOpeningSuggestionResponse();
        CashShiftEntity last = shifts.latestClosed(principal.getTenantId(), outletId).orElse(null);
        response.setOpeningCash(last == null ? new BigDecimal("0.00") : last.getCountedCash());
        response.setSourceShiftId(last == null ? null : last.getId());
        return response;
    }

    @Transactional
    public FnbCashExpenseResponse recordExpense(TenantPrincipal principal, UUID outletId, String key,
                                                FnbCashExpenseRequest request) {
        String idempotencyKey = FnbPosOrderService.validatedKey(key);
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        BigDecimal amount = request.getAmount().setScale(2, RoundingMode.UNNECESSARY);
        String description = request.getDescription().trim();
        String fingerprint = digest.digest(amount.toPlainString() + "|" + description);
        CashExpenseEntity existing = expenses.byKey(principal.getTenantId(), idempotencyKey).orElse(null);
        if (existing != null) {
            if (!fingerprint.equals(existing.getRequestFingerprint()) || !existing.getOutlet().getId().equals(outletId)
                    || !existing.getRecordedBy().getId().equals(context.getMembership().getUser().getId()))
                throw new OperationConflictException("Idempotency-Key telah digunakan untuk pengeluaran berbeda.");
            return FnbShiftMapper.expense(existing);
        }
        CashShiftEntity shift = requireOpenShift(context, "Buka shift terlebih dahulu sebelum mencatat pengeluaran.");
        CashExpenseEntity expense = CashExpenseEntity.record(context.getTenant(), context.getOutlet(), shift, amount,
                description, context.getMembership().getUser(), idempotencyKey, fingerprint);
        expenses.persist(expense);
        orders.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(),
                context.getMembership().getUser(), AuditLogEntity.Action.CASH_EXPENSE_RECORDED, expense.getId(), null,
                "amount=" + amount.toPlainString()));
        return FnbShiftMapper.expense(expense);
    }

    @Transactional(readOnly = true)
    public List<FnbCashExpenseResponse> currentExpenses(TenantPrincipal principal, UUID outletId) {
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        CashShiftEntity shift = findOpenShift(context).orElseThrow(ResourceNotFoundException::new);
        return expenses.forShift(shift.getId()).stream().map(FnbShiftMapper::expense).toList();
    }
}
