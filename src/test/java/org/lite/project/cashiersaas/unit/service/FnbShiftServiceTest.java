package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbCashExpenseRequest;
import org.lite.project.cashiersaas.dto.request.FnbShiftCloseRequest;
import org.lite.project.cashiersaas.dto.request.FnbShiftOpenRequest;
import org.lite.project.cashiersaas.dto.response.FnbCashExpenseResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftOpeningSuggestionResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftResponse;
import org.lite.project.cashiersaas.entity.fnb.CashExpenseEntity;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbCashExpenseRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbShiftRepository;
import org.lite.project.cashiersaas.service.DailySummaryService;
import org.lite.project.cashiersaas.service.DigestService;
import org.lite.project.cashiersaas.service.FnbShiftService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbShiftServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbShiftRepository shifts;
    @Mock private FnbOrderRepository orders;
    @Mock private AuditLogRepository audit;
    @Mock private FnbCashExpenseRepository expenses;
    @Mock private DigestService digest;
    @Mock private DailySummaryService dailySummary;
    @InjectMocks private FnbShiftService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID membershipId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final StaffUserEntity cashier = StaffUserEntity.createCashier("k@example.com", "hash", "Kasir", null);
    private final StaffUserEntity owner = StaffUserEntity.createCashier("o@example.com", "hash", "Owner", null);
    private final TenantMembershipEntity cashierMembership = withId(TenantMembershipEntity.grantCashier(cashier, tenant), membershipId);
    private final TenantMembershipEntity ownerMembership = withId(TenantMembershipEntity.grantOwner(owner, tenant), UUID.randomUUID());
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenantId, Set.of(Role.CASHIER));
    private CashShiftEntity shift;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cashier, "id", UUID.randomUUID());
        shift = withId(CashShiftEntity.open(tenant, outlet, cashierMembership, new BigDecimal("100.00")), UUID.randomUUID());
        lenient().when(expenses.totalForShift(any())).thenReturn(new BigDecimal("0.00"));
        lenient().when(expenses.forShift(any())).thenReturn(List.of());
        lenient().when(digest.digest(anyString())).thenAnswer(call -> "fp:" + call.getArgument(0));
    }

    private void actingAsCashier() {
        when(guard.requireOutlet(principal, outletId)).thenReturn(new MerchantAccessContext(tenant, outlet, cashierMembership));
    }

    private void summary(String cashIn, String cashRefunds) {
        when(shifts.cashSummary(shift.getId())).thenReturn(new FnbShiftRepository.CashSummary(
                new BigDecimal(cashIn), new BigDecimal(cashRefunds), 2,
                List.of(new FnbShiftRepository.MethodTotal(PaymentMethod.CASH, null, 1, new BigDecimal(cashIn)),
                        new FnbShiftRepository.MethodTotal(PaymentMethod.MANUAL_NON_CASH, "QRIS", 1, new BigDecimal("40.00")))));
    }

    private static FnbShiftOpenRequest openRequest(String cash) {
        FnbShiftOpenRequest request = new FnbShiftOpenRequest();
        request.setOpeningCash(new BigDecimal(cash));
        return request;
    }

    private static FnbShiftCloseRequest closeRequest(String counted, String note) {
        FnbShiftCloseRequest request = new FnbShiftCloseRequest();
        request.setCountedCash(new BigDecimal(counted));
        request.setNote(note);
        return request;
    }

    @Test
    void openWhileAnotherShiftIsOpenIsAConflictAndPersistsNothing() {
        actingAsCashier();
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.of(shift));

        OperationConflictException ex = assertThrows(OperationConflictException.class,
                () -> service.open(principal, outletId, openRequest("50.00")));

        assertEquals("Anda masih memiliki shift terbuka.", ex.getMessage());
        verify(shifts, never()).persist(any(CashShiftEntity.class));
        verify(audit, never()).persist(any(AuditLogEntity.class));
    }

    @Test
    void openPersistsTheShiftUnderTheTenantLockAndAuditsIt() {
        actingAsCashier();
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.empty());
        when(shifts.cashSummary(any())).thenReturn(new FnbShiftRepository.CashSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of()));

        FnbShiftResponse response = service.open(principal, outletId, openRequest("50"));

        InOrder inOrder = inOrder(orders, shifts);
        inOrder.verify(orders).lockTenant(tenantId);
        inOrder.verify(shifts).persist(any(CashShiftEntity.class));
        assertEquals(CashShiftEntity.Status.OPEN, response.getStatus());
        assertEquals(new BigDecimal("50.00"), response.getOpeningCash());
        assertEquals(new BigDecimal("50.00"), response.getExpectedCash());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("SHIFT_OPENED", log.getValue().getAction());
        assertEquals("CASH_SHIFT", log.getValue().getEntityType());
        assertEquals("openingCash=50.00", log.getValue().getDetails());
    }

    @Test
    void requireOpenShiftReturnsTheCallersShiftOrConflicts() {
        MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, cashierMembership);
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.of(shift), Optional.empty());

        assertSame(shift, service.requireOpenShift(context));
        OperationConflictException ex = assertThrows(OperationConflictException.class,
                () -> service.requireOpenShift(context));

        assertEquals("Buka shift terlebih dahulu sebelum menerima pembayaran.", ex.getMessage());
        assertTrue(service.findOpenShift(context).isEmpty());
    }

    @Test
    void liveExpectedCashIsOpeningPlusCashInMinusCashRefunds() {
        actingAsCashier();
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.of(shift));
        summary("250.00", "30.00");

        FnbShiftResponse response = service.current(principal, outletId);

        assertEquals(new BigDecimal("320.00"), response.getExpectedCash(), "100 + 250 - 30");
        assertEquals(new BigDecimal("250.00"), response.getCashIn());
        assertEquals(new BigDecimal("30.00"), response.getCashRefunds());
        assertEquals(2, response.getPaymentCount());
        assertEquals(2, response.getTotalsByMethod().size());
        assertEquals("QRIS", response.getTotalsByMethod().get(1).getMethodLabel());
        assertEquals(new BigDecimal("0.00"), response.getExpensesTotal());
    }

    @Test
    void expectedCashSubtractsPettyCashExpensesLiveAndAtClose() {
        actingAsCashier();
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.of(shift));
        summary("250.00", "30.00");
        when(expenses.totalForShift(shift.getId())).thenReturn(new BigDecimal("45.50"));

        FnbShiftResponse live = service.current(principal, outletId);
        assertEquals(new BigDecimal("274.50"), live.getExpectedCash(), "100 + 250 - 30 - 45.50");
        assertEquals(new BigDecimal("45.50"), live.getExpensesTotal());

        FnbShiftResponse closed = service.closeCurrent(principal, outletId, closeRequest("274.50", null));
        assertEquals(new BigDecimal("274.50"), closed.getExpectedCash());
        assertEquals(new BigDecimal("0.00"), closed.getCashDifference());
        assertEquals(new BigDecimal("274.50"), shift.getExpectedCash(), "stored expected includes expenses");
    }

    @Test
    void openOrderCountComesFromTheExactCountQuery() {
        actingAsCashier();
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.of(shift));
        summary("0.00", "0.00");
        when(orders.countOpenUnpaid(tenantId, outletId)).thenReturn(137);

        assertEquals(137, service.current(principal, outletId).getOpenOrderCount());
    }

    private static FnbCashExpenseRequest expenseRequest(String amount, String description) {
        FnbCashExpenseRequest request = new FnbCashExpenseRequest();
        request.setAmount(new BigDecimal(amount));
        request.setDescription(description);
        return request;
    }

    @Test
    void recordExpensePersistsUnderTheTenantLockAuditsAndTrimsDescription() {
        actingAsCashier();
        when(expenses.byKey(tenantId, "expense-key-0000001")).thenReturn(Optional.empty());
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.of(shift));

        FnbCashExpenseResponse response = service.recordExpense(principal, outletId, "expense-key-0000001",
                expenseRequest("12500", "  Beli es batu  "));

        InOrder inOrder = inOrder(orders, expenses);
        inOrder.verify(orders).lockTenant(tenantId);
        inOrder.verify(expenses).persist(any(CashExpenseEntity.class));
        assertEquals(new BigDecimal("12500.00"), response.getAmount());
        assertEquals("Beli es batu", response.getDescription());
        assertEquals("Kasir", response.getRecordedBy());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("CASH_EXPENSE_RECORDED", log.getValue().getAction());
        assertEquals("CASH_EXPENSE", log.getValue().getEntityType());
        assertEquals("amount=12500.00", log.getValue().getDetails());
    }

    @Test
    void recordExpenseWithoutAnOpenShiftConflictsWithThePettyCashMessage() {
        actingAsCashier();
        when(expenses.byKey(tenantId, "expense-key-0000001")).thenReturn(Optional.empty());
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.empty());

        OperationConflictException ex = assertThrows(OperationConflictException.class, () -> service.recordExpense(
                principal, outletId, "expense-key-0000001", expenseRequest("100", "Parkir")));

        assertEquals("Buka shift terlebih dahulu sebelum mencatat pengeluaran.", ex.getMessage());
        verify(expenses, never()).persist(any(CashExpenseEntity.class));
        verify(audit, never()).persist(any(AuditLogEntity.class));
    }

    @Test
    void recordExpenseReplayReturnsTheStoredExpenseWithoutAShiftOrANewRow() {
        actingAsCashier();
        CashExpenseEntity stored = storedExpense("fp:100.00|Parkir");
        when(expenses.byKey(tenantId, "expense-key-0000001")).thenReturn(Optional.of(stored));

        FnbCashExpenseResponse replay = service.recordExpense(principal, outletId, "expense-key-0000001",
                expenseRequest("100", "Parkir"));

        assertEquals(new BigDecimal("100.00"), replay.getAmount());
        verify(expenses, never()).persist(any(CashExpenseEntity.class));
        verify(audit, never()).persist(any(AuditLogEntity.class));
        verify(shifts, never()).openFor(any(), any(), any());
    }

    @Test
    void recordExpenseWithTheSameKeyButADifferentBodyConflicts() {
        actingAsCashier();
        when(expenses.byKey(tenantId, "expense-key-0000001")).thenReturn(Optional.of(storedExpense("fp:100.00|Parkir")));

        assertThrows(OperationConflictException.class, () -> service.recordExpense(principal, outletId,
                "expense-key-0000001", expenseRequest("200", "Parkir")));
        assertThrows(OperationConflictException.class, () -> service.recordExpense(principal, outletId,
                "expense-key-0000001", expenseRequest("100", "Bensin")));
        verify(expenses, never()).persist(any(CashExpenseEntity.class));
    }

    @Test
    void recordExpenseRejectsAMalformedIdempotencyKeyBeforeAnyWork() {
        assertThrows(InvalidOperationException.class, () -> service.recordExpense(principal, outletId, "short",
                expenseRequest("100", "Parkir")));
        verify(orders, never()).lockTenant(any());
    }

    @Test
    void currentExpensesListsTheCallersOpenShiftOrIsNotFound() {
        actingAsCashier();
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.of(shift), Optional.empty());
        when(expenses.forShift(shift.getId())).thenReturn(List.of(storedExpense("fp")));

        assertEquals(1, service.currentExpenses(principal, outletId).size());
        assertThrows(ResourceNotFoundException.class, () -> service.currentExpenses(principal, outletId));
    }

    @Test
    void openingSuggestionIsZeroWithoutHistoryElseTheCountedCashOfTheLatestClosedShift() {
        actingAsCashier();
        when(shifts.latestClosed(tenantId, outletId)).thenReturn(Optional.empty());
        FnbShiftOpeningSuggestionResponse none = service.openingSuggestion(principal, outletId);
        assertEquals(new BigDecimal("0.00"), none.getOpeningCash());
        assertNull(none.getSourceShiftId());

        shift.close(cashier, new BigDecimal("300.00"), new BigDecimal("290.00"), null);
        when(shifts.latestClosed(tenantId, outletId)).thenReturn(Optional.of(shift));
        FnbShiftOpeningSuggestionResponse last = service.openingSuggestion(principal, outletId);
        assertEquals(new BigDecimal("290.00"), last.getOpeningCash());
        assertEquals(shift.getId(), last.getSourceShiftId());
    }

    private CashExpenseEntity storedExpense(String fingerprint) {
        return CashExpenseEntity.record(tenant, outlet, shift, new BigDecimal("100.00"), "Parkir", cashier,
                "expense-key-0000001", fingerprint);
    }

    @Test
    void currentWithoutAnOpenShiftIsNotFound() {
        actingAsCashier();
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.current(principal, outletId));
    }

    @Test
    void closeCurrentStoresExpectedCountedDifferenceAndAudits() {
        actingAsCashier();
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.of(shift));
        summary("250.00", "30.00");

        FnbShiftResponse response = service.closeCurrent(principal, outletId, closeRequest("300.00", "kurang 20"));

        InOrder inOrder = inOrder(orders, shifts);
        inOrder.verify(orders).lockTenant(tenantId);
        inOrder.verify(shifts).lock(shift);
        assertEquals(CashShiftEntity.Status.CLOSED, response.getStatus());
        assertEquals(new BigDecimal("320.00"), response.getExpectedCash());
        assertEquals(new BigDecimal("300.00"), response.getCountedCash());
        assertEquals(new BigDecimal("-20.00"), response.getCashDifference());
        assertEquals("kurang 20", response.getClosingNote());
        assertSame(cashier, shift.getClosedBy());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("SHIFT_CLOSED", log.getValue().getAction());
        assertEquals("expected=320.00 counted=300.00 difference=-20.00", log.getValue().getDetails());
        verify(dailySummary).onShiftClosed(any(MerchantAccessContext.class), same(shift));
    }

    @Test
    void closeCurrentWithoutAnOpenShiftIsNotFound() {
        actingAsCashier();
        when(shifts.openFor(tenantId, outletId, membershipId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.closeCurrent(principal, outletId, closeRequest("0.00", null)));
        verify(audit, never()).persist(any(AuditLogEntity.class));
    }

    @Test
    void ownerForceCloseClosesAnotherMembersShiftWithTheOwnerAsCloser() {
        when(guard.requireOwner(principal, outletId)).thenReturn(new MerchantAccessContext(tenant, outlet, ownerMembership));
        when(shifts.byId(tenantId, outletId, shift.getId())).thenReturn(Optional.of(shift));
        summary("100.00", "0.00");

        FnbShiftResponse response = service.forceClose(principal, outletId, shift.getId(), closeRequest("200.00", null));

        assertSame(owner, shift.getClosedBy());
        assertEquals(new BigDecimal("200.00"), response.getExpectedCash());
        assertEquals(new BigDecimal("0.00"), response.getCashDifference());
        verify(orders).lockTenant(tenantId);
        verify(dailySummary).onShiftClosed(any(MerchantAccessContext.class), same(shift));
    }

    @Test
    void forceCloseOfAnAlreadyClosedShiftConflictsAndUnknownShiftIsNotFound() {
        when(guard.requireOwner(principal, outletId)).thenReturn(new MerchantAccessContext(tenant, outlet, ownerMembership));
        shift.close(cashier, new BigDecimal("100.00"), new BigDecimal("100.00"), null);
        when(shifts.byId(tenantId, outletId, shift.getId())).thenReturn(Optional.of(shift));
        UUID unknown = UUID.randomUUID();
        when(shifts.byId(tenantId, outletId, unknown)).thenReturn(Optional.empty());
        when(shifts.cashSummary(shift.getId())).thenReturn(new FnbShiftRepository.CashSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of()));

        OperationConflictException ex = assertThrows(OperationConflictException.class,
                () -> service.forceClose(principal, outletId, shift.getId(), closeRequest("1.00", null)));
        assertEquals("Shift sudah ditutup.", ex.getMessage());
        assertThrows(ResourceNotFoundException.class,
                () -> service.forceClose(principal, outletId, unknown, closeRequest("1.00", null)));
        verify(audit, never()).persist(any(AuditLogEntity.class));
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
