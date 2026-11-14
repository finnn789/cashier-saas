package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbRefundRequest;
import org.lite.project.cashiersaas.dto.response.FnbRefundResponse;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentRefundEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentRepository;
import org.lite.project.cashiersaas.repository.FnbRefundRepository;
import org.lite.project.cashiersaas.service.DigestService;
import org.lite.project.cashiersaas.service.FnbRefundService;
import org.lite.project.cashiersaas.service.FnbShiftService;
import org.lite.project.cashiersaas.service.FnbStockService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.lite.project.cashiersaas.service.PlanEntitlementService;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbRefundServiceTest {
    private static final String KEY = "refund-key-0000001";

    @Mock private MerchantAccessGuard guard;
    @Mock private FnbOrderRepository orders;
    @Mock private FnbPaymentRepository payments;
    @Mock private FnbRefundRepository refunds;
    @Mock private FnbShiftService shifts;
    @Mock private FnbStockService stock;
    @Mock private AuditLogRepository audit;
    @Mock private PlanEntitlementService planEntitlement;
    @Spy private DigestService digests = new DigestService();
    @InjectMocks private FnbRefundService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final StaffUserEntity cashier = StaffUserEntity.createCashier("k@example.com", "hash", "Kasir", null);
    private final StaffUserEntity owner = StaffUserEntity.createCashier("o@example.com", "hash", "Pemilik", null);
    private final TenantMembershipEntity ownerMembership = withId(TenantMembershipEntity.grantOwner(owner, tenant), UUID.randomUUID());
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, ownerMembership);
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenantId, Set.of(Role.OWNERMERCHANT));
    private SalesOrderEntity order;
    private CashShiftEntity ownerShift;

    @BeforeEach
    void setUp() {
        order = openOrder();
        ownerShift = CashShiftEntity.open(tenant, outlet, ownerMembership, new BigDecimal("1000.00"));
    }

    private SalesOrderEntity openOrder() {
        return withId(SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0001", "fp", "POS-1", ServiceType.DINE_IN, null, null),
                new SalesOrderEntity.Charges(new BigDecimal("50000.00"), BigDecimal.ZERO, BigDecimal.ZERO)), orderId);
    }

    private PaymentEntity payment(PaymentMethod method, String paid, String change) {
        return withId(PaymentEntity.settle(tenant, outlet, order, method, new BigDecimal(paid), new BigDecimal(change),
                "R-1", "pay-key-0000000001", "pfp", null, cashier, null), paymentId);
    }

    private void paidOrderWith(PaymentEntity payment) {
        order.confirmPaid();
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
        when(payments.byOrder(tenantId, outletId, orderId)).thenReturn(Optional.of(payment));
    }

    private void ownerCalls() {
        when(guard.requireOwner(principal, outletId)).thenReturn(context);
    }

    private static FnbRefundRequest request(String reason) {
        FnbRefundRequest request = new FnbRefundRequest();
        request.setReason(reason);
        return request;
    }

    private String fingerprint(String reason) {
        return new DigestService().digest(orderId + "|" + reason);
    }

    @Test
    void cashRefundUsesTheOwnersOpenShiftRecordsAmountMinusChangeReversesStockAndAudits() {
        ownerCalls();
        PaymentEntity payment = payment(PaymentMethod.CASH, "60000.00", "10000.00");
        paidOrderWith(payment);
        when(shifts.requireOpenShift(context)).thenReturn(ownerShift);
        when(stock.reverseForRefundedOrder(context, order)).thenReturn(List.of("Susu: sisa 5 ml (menipis)"));

        FnbRefundResponse response = service.refund(principal, outletId, orderId, KEY, request("  Salah pesanan  "));

        InOrder lockFirst = inOrder(orders, guard);
        lockFirst.verify(orders).lockTenant(tenantId);
        lockFirst.verify(guard).requireOwner(principal, outletId);
        lockFirst.verify(orders).byId(tenantId, outletId, orderId);
        assertEquals(PaymentStatus.REFUNDED, order.getPaymentStatus());
        assertEquals(PaymentStatus.REFUNDED, response.getPaymentStatus());
        assertEquals(orderId, response.getOrderId());
        assertEquals("POS-1", response.getOrderNumber());
        assertEquals(new BigDecimal("50000.00"), response.getAmount(), "amount paid minus change");
        assertEquals(PaymentMethod.CASH, response.getMethod());
        assertEquals("Salah pesanan", response.getReason());
        assertEquals("Pemilik", response.getRefundedBy());
        assertEquals(List.of("Susu: sisa 5 ml (menipis)"), response.getStockWarnings());
        ArgumentCaptor<PaymentRefundEntity> refund = ArgumentCaptor.forClass(PaymentRefundEntity.class);
        verify(refunds).persist(refund.capture());
        assertSame(ownerShift, refund.getValue().getCashShift());
        assertSame(payment, refund.getValue().getPayment());
        assertEquals(KEY, refund.getValue().getIdempotencyKey());
        assertEquals(fingerprint("Salah pesanan"), refund.getValue().getRequestFingerprint());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("FNB_ORDER_REFUNDED", log.getValue().getAction());
        assertEquals("Salah pesanan", log.getValue().getReason());
        assertEquals("amount=50000.00 method=CASH", log.getValue().getDetails());
        assertEquals(orderId, log.getValue().getEntityId());
    }

    @Test
    void nonOwnerIsRejectedAfterTheLockAndBeforeAnyOrderLoad() {
        when(guard.requireOwner(principal, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class,
                () -> service.refund(principal, outletId, orderId, KEY, request("alasan")));

        InOrder inOrder = inOrder(orders, guard);
        inOrder.verify(orders).lockTenant(tenantId);
        inOrder.verify(guard).requireOwner(principal, outletId);
        verify(orders, never()).byId(any(UUID.class), any(UUID.class), any(UUID.class));
        verifyNoInteractions(payments, refunds, shifts, stock, audit);
    }

    @Test
    void invalidKeyAndBlankReasonAreRejectedBeforeLoadingTheOrder() {
        ownerCalls();

        assertThrows(InvalidOperationException.class,
                () -> service.refund(principal, outletId, orderId, "short", request("alasan")));
        assertThrows(InvalidOperationException.class,
                () -> service.refund(principal, outletId, orderId, null, request("alasan")));
        InvalidOperationException blank = assertThrows(InvalidOperationException.class,
                () -> service.refund(principal, outletId, orderId, KEY, request("   ")));

        assertEquals("Alasan refund wajib diisi.", blank.getMessage());
        verify(orders, never()).byId(any(UUID.class), any(UUID.class), any(UUID.class));
        verifyNoInteractions(refunds, shifts, stock, audit);
    }

    @Test
    void unknownOrderIsNotFound() {
        ownerCalls();
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.refund(principal, outletId, orderId, KEY, request("alasan")));
        verifyNoInteractions(refunds, shifts, stock, audit);
    }

    @Test
    void orderWithoutAPaymentConflictsAndOpenOrderWithAPaymentRowConflictsToo() {
        ownerCalls();
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
        when(payments.byOrder(tenantId, outletId, orderId)).thenReturn(Optional.empty());

        OperationConflictException unpaid = assertThrows(OperationConflictException.class,
                () -> service.refund(principal, outletId, orderId, KEY, request("alasan")));
        assertEquals("Hanya pesanan lunas yang dapat direfund.", unpaid.getMessage());

        // Defensive: an OPEN order is rejected by the entity rule even if a payment row existed.
        when(payments.byOrder(tenantId, outletId, orderId)).thenReturn(Optional.of(payment(PaymentMethod.CASH, "50000.00", "0.00")));
        OperationConflictException open = assertThrows(OperationConflictException.class,
                () -> service.refund(principal, outletId, orderId, KEY, request("alasan")));
        assertEquals("Hanya pesanan lunas yang dapat direfund.", open.getMessage());
        assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus());
        verify(refunds, never()).persist(any(PaymentRefundEntity.class));
        verifyNoInteractions(stock, audit);
    }

    @Test
    void secondRefundWithANewKeyIsACleanConflict() {
        ownerCalls();
        PaymentEntity payment = payment(PaymentMethod.CASH, "50000.00", "0.00");
        paidOrderWith(payment);
        when(refunds.byPayment(tenantId, paymentId)).thenReturn(Optional.of(
                PaymentRefundEntity.record(tenant, outlet, payment, order, new BigDecimal("50000.00"), "pertama",
                        owner, ownerShift, "refund-key-first-001", fingerprint("pertama"))));

        OperationConflictException ex = assertThrows(OperationConflictException.class,
                () -> service.refund(principal, outletId, orderId, KEY, request("kedua")));

        assertEquals("Pesanan sudah direfund.", ex.getMessage());
        verify(refunds, never()).persist(any(PaymentRefundEntity.class));
        verifyNoInteractions(stock, audit, shifts);
    }

    @Test
    void replayWithTheSameKeyReturnsTheStoredRefundWithoutNewEffects() {
        ownerCalls();
        PaymentEntity payment = payment(PaymentMethod.CASH, "60000.00", "10000.00");
        order.confirmPaid();
        order.markRefunded();
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
        when(refunds.byKey(tenantId, KEY)).thenReturn(Optional.of(
                PaymentRefundEntity.record(tenant, outlet, payment, order, new BigDecimal("50000.00"), "Salah pesanan",
                        owner, ownerShift, KEY, fingerprint("Salah pesanan"))));

        FnbRefundResponse response = service.refund(principal, outletId, orderId, KEY, request("Salah pesanan"));

        assertEquals(PaymentStatus.REFUNDED, response.getPaymentStatus());
        assertEquals(new BigDecimal("50000.00"), response.getAmount());
        assertEquals("Salah pesanan", response.getReason());
        verify(refunds, never()).persist(any(PaymentRefundEntity.class));
        verifyNoInteractions(payments, shifts, stock, audit);
    }

    @Test
    void sameKeyWithADifferentReasonOrAnotherOrderIsAConflict() {
        ownerCalls();
        PaymentEntity payment = payment(PaymentMethod.CASH, "50000.00", "0.00");
        order.confirmPaid();
        order.markRefunded();
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
        SalesOrderEntity otherOrder = withId(openOrder(), UUID.randomUUID());
        when(refunds.byKey(tenantId, KEY)).thenReturn(Optional.of(
                PaymentRefundEntity.record(tenant, outlet, payment, order, new BigDecimal("50000.00"), "Salah pesanan",
                        owner, ownerShift, KEY, fingerprint("Salah pesanan"))));

        OperationConflictException ex = assertThrows(OperationConflictException.class,
                () -> service.refund(principal, outletId, orderId, KEY, request("Alasan lain")));
        assertEquals("Idempotency-Key telah digunakan untuk refund berbeda.", ex.getMessage());

        when(orders.byId(tenantId, outletId, otherOrder.getId())).thenReturn(Optional.of(otherOrder));
        assertThrows(OperationConflictException.class,
                () -> service.refund(principal, outletId, otherOrder.getId(), KEY, request("Salah pesanan")));
        verify(refunds, never()).persist(any(PaymentRefundEntity.class));
        verifyNoInteractions(stock, audit);
    }

    @Test
    void cashRefundWithoutAnOpenOwnerShiftConflictsAndRecordsNothing() {
        ownerCalls();
        paidOrderWith(payment(PaymentMethod.CASH, "50000.00", "0.00"));
        when(shifts.requireOpenShift(context)).thenThrow(
                new OperationConflictException("Buka shift terlebih dahulu sebelum menerima pembayaran."));

        OperationConflictException ex = assertThrows(OperationConflictException.class,
                () -> service.refund(principal, outletId, orderId, KEY, request("alasan")));

        assertEquals("Buka shift terlebih dahulu sebelum menerima pembayaran.", ex.getMessage());
        verify(refunds, never()).persist(any(PaymentRefundEntity.class));
        verifyNoInteractions(stock, audit);
    }

    @Test
    void nonCashRefundWorksWithoutAShiftAndUsesTheOwnersShiftWhenOneIsOpen() {
        ownerCalls();
        paidOrderWith(payment(PaymentMethod.MANUAL_NON_CASH, "50000.00", "0.00"));

        FnbRefundResponse response = service.refund(principal, outletId, orderId, KEY, request("alasan"));

        assertEquals(PaymentMethod.MANUAL_NON_CASH, response.getMethod());
        assertEquals(new BigDecimal("50000.00"), response.getAmount());
        ArgumentCaptor<PaymentRefundEntity> refund = ArgumentCaptor.forClass(PaymentRefundEntity.class);
        verify(refunds).persist(refund.capture());
        assertNull(refund.getValue().getCashShift());
        verify(shifts, never()).requireOpenShift(any(MerchantAccessContext.class));
        verify(stock).reverseForRefundedOrder(context, order);
    }

    @Test
    void nonCashRefundIsAttributedToTheOwnersOpenShiftWhenPresent() {
        ownerCalls();
        paidOrderWith(payment(PaymentMethod.MANUAL_NON_CASH, "50000.00", "0.00"));
        when(shifts.findOpenShift(context)).thenReturn(Optional.of(ownerShift));

        service.refund(principal, outletId, orderId, KEY, request("alasan"));

        ArgumentCaptor<PaymentRefundEntity> refund = ArgumentCaptor.forClass(PaymentRefundEntity.class);
        verify(refunds).persist(refund.capture());
        assertSame(ownerShift, refund.getValue().getCashShift());
        verify(shifts, never()).requireOpenShift(any(MerchantAccessContext.class));
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
