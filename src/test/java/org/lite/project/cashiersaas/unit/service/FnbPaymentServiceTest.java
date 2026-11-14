package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbPaymentRequest;
import org.lite.project.cashiersaas.dto.response.FnbPaymentResponse;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentLabelEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentLabelRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentRepository;
import org.lite.project.cashiersaas.service.FnbPaymentService;
import org.lite.project.cashiersaas.service.FnbShiftService;
import org.lite.project.cashiersaas.service.FnbStockService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Label rules, receipt numbering and replay of {@link FnbPaymentService#pay} (TASK-078); lock/shift are mocked. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbPaymentServiceTest {
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    @Mock private MerchantAccessGuard guard;
    @Mock private FnbOrderRepository orders;
    @Mock private FnbPaymentRepository payments;
    @Mock private FnbPaymentLabelRepository labels;
    @Mock private AuditLogRepository audit;
    @Mock private FnbStockService stock;
    @Mock private FnbShiftService shifts;
    @InjectMocks private FnbPaymentService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final StaffUserEntity cashier = StaffUserEntity.createCashier("k@example.com", "hash", "Kasir", null);
    private final MerchantAccessContext context =
            new MerchantAccessContext(tenant, outlet, TenantMembershipEntity.grantCashier(cashier, tenant));
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenantId, Set.of(Role.CASHIER));
    private SalesOrderEntity order;
    private CashShiftEntity shift;

    @BeforeEach
    void setUp() {
        order = withId(SalesOrderEntity.pos(tenant, outlet,
                new SalesOrderEntity.PosDetails("checkout-key-0001", "fp", "POS-1", ServiceType.DINE_IN, null, null),
                new SalesOrderEntity.Charges(new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO)), orderId);
        shift = CashShiftEntity.open(tenant, outlet, context.getMembership(), BigDecimal.ZERO);
        when(guard.requireOutlet(principal, outletId)).thenReturn(context);
        when(orders.byId(tenantId, outletId, orderId)).thenReturn(Optional.of(order));
    }

    private static FnbPaymentRequest request(PaymentMethod method, String amount, UUID labelId, String key) {
        FnbPaymentRequest request = new FnbPaymentRequest();
        request.setMethod(method);
        request.setAmountTendered(new BigDecimal(amount));
        request.setLabelId(labelId);
        request.setIdempotencyKey(key);
        return request;
    }

    private PaymentLabelEntity label(String name, boolean active) {
        PaymentLabelEntity label = withId(PaymentLabelEntity.create(tenant, outlet, name, 1), UUID.randomUUID());
        if (!active) label.update(name, 1, false);
        return label;
    }

    private void noExistingPayment() {
        when(payments.byKey(eq(tenantId), anyString())).thenReturn(Optional.empty());
        when(payments.byOrder(tenantId, outletId, orderId)).thenReturn(Optional.empty());
    }

    private void paidFlow(int sequence) {
        noExistingPayment();
        when(shifts.requireOpenShift(context)).thenReturn(shift);
        when(payments.nextReceiptSequence(eq(outletId), any(LocalDate.class))).thenReturn(sequence);
        when(stock.consumeForPaidOrder(context, order)).thenReturn(List.of());
    }

    @Test
    void cashWithALabelIsRejectedBeforeAnythingIsWritten() {
        noExistingPayment();

        InvalidOperationException ex = assertThrows(InvalidOperationException.class, () -> service.pay(principal, outletId,
                orderId, request(PaymentMethod.CASH, "100.00", UUID.randomUUID(), "pay-key-0000000001")));

        assertEquals("Pembayaran tunai tidak memakai label.", ex.getMessage());
        verify(payments, never()).persist(any(PaymentEntity.class));
        verify(payments, never()).nextReceiptSequence(any(), any());
        assertEquals("UNPAID", order.getPaymentStatus().name());
    }

    @Test
    void nonCashWithoutUnknownOrInactiveLabelIsRejected() {
        noExistingPayment();
        UUID unknown = UUID.randomUUID();
        PaymentLabelEntity inactive = label("Debit", false);
        when(labels.byId(tenantId, outletId, unknown)).thenReturn(Optional.empty());
        when(labels.byId(tenantId, outletId, inactive.getId())).thenReturn(Optional.of(inactive));

        for (UUID labelId : new UUID[]{null, unknown, inactive.getId()}) {
            InvalidOperationException ex = assertThrows(InvalidOperationException.class, () -> service.pay(principal, outletId,
                    orderId, request(PaymentMethod.MANUAL_NON_CASH, "100.00", labelId, "pay-key-0000000001")));
            assertEquals("Pilih label pembayaran non-tunai.", ex.getMessage());
        }
        verify(payments, never()).persist(any(PaymentEntity.class));
        verify(shifts, never()).requireOpenShift(any());
        assertEquals("UNPAID", order.getPaymentStatus().name());
    }

    @Test
    void nonCashMustMatchTheTotalExactlyAndCashMayNotBeShort() {
        noExistingPayment();
        UUID labelId = UUID.randomUUID();

        assertThrows(InvalidOperationException.class, () -> service.pay(principal, outletId, orderId,
                request(PaymentMethod.MANUAL_NON_CASH, "99.00", labelId, "pay-key-0000000001")));
        assertThrows(InvalidOperationException.class, () -> service.pay(principal, outletId, orderId,
                request(PaymentMethod.CASH, "99.00", null, "pay-key-0000000001")));
        verify(payments, never()).nextReceiptSequence(any(), any());
    }

    @Test
    void nonCashWithAnActiveLabelSnapshotsTheLabelNameAndNumbersTheReceipt() {
        PaymentLabelEntity qris = label("QRIS", true);
        when(labels.byId(tenantId, outletId, qris.getId())).thenReturn(Optional.of(qris));
        paidFlow(7);

        FnbPaymentResponse response = service.pay(principal, outletId, orderId,
                request(PaymentMethod.MANUAL_NON_CASH, "100.00", qris.getId(), "pay-key-0000000001"));

        ArgumentCaptor<PaymentEntity> payment = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(payments).persist(payment.capture());
        assertEquals("QRIS", payment.getValue().getMethodLabel());
        assertEquals(PaymentMethod.MANUAL_NON_CASH, payment.getValue().getMethod());
        assertEquals(0, BigDecimal.ZERO.compareTo(payment.getValue().getChangeAmount()));
        assertSame(shift, payment.getValue().getCashShift());
        assertEquals("QRIS", response.getMethodLabel());
        assertEquals(today() + "-0007", response.getReceiptNumber().substring(4));
        assertTrue(response.getReceiptNumber().startsWith("INV-"));
        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(payments).nextReceiptSequence(eq(outletId), date.capture());
        assertEquals(LocalDate.now(JAKARTA).format(DateTimeFormatter.BASIC_ISO_DATE),
                date.getValue().format(DateTimeFormatter.BASIC_ISO_DATE), "business date is the outlet-zone date");
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }

    private static String today() {
        return LocalDate.now(JAKARTA).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    @Test
    void cashPaymentHasNoLabelAndReceiptNumbersPadToFourDigitsWithoutACap() {
        paidFlow(12345);

        FnbPaymentResponse response = service.pay(principal, outletId, orderId,
                request(PaymentMethod.CASH, "150.00", null, "pay-key-0000000001"));

        assertNull(response.getMethodLabel());
        assertEquals(0, new BigDecimal("50.00").compareTo(response.getChangeAmount()));
        assertEquals(today() + "-12345", response.getReceiptNumber().substring(4));
        verify(labels, never()).byId(any(), any(), any());
    }

    @Test
    void replayWithTheSameKeyAndLabelReturnsTheStoredPaymentWithoutANewNumber() {
        PaymentLabelEntity qris = label("QRIS", true);
        when(labels.byId(tenantId, outletId, qris.getId())).thenReturn(Optional.of(qris));
        paidFlow(1);
        FnbPaymentRequest first = request(PaymentMethod.MANUAL_NON_CASH, "100.00", qris.getId(), "pay-key-0000000001");
        FnbPaymentResponse paid = service.pay(principal, outletId, orderId, first);
        ArgumentCaptor<PaymentEntity> stored = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(payments).persist(stored.capture());
        when(payments.byKey(tenantId, "pay-key-0000000001")).thenReturn(Optional.of(stored.getValue()));

        // "100" and "100.00" are the same amount; the replay needs no label lookup, shift or sequence draw.
        FnbPaymentResponse replay = service.pay(principal, outletId, orderId,
                request(PaymentMethod.MANUAL_NON_CASH, "100", qris.getId(), "pay-key-0000000001"));

        assertEquals(paid.getReceiptNumber(), replay.getReceiptNumber());
        assertEquals("QRIS", replay.getMethodLabel());
        verify(payments, times(1)).nextReceiptSequence(any(), any());
        verify(payments, times(1)).persist(any(PaymentEntity.class));
    }

    @Test
    void replayWithADifferentLabelMethodOrAmountIsAConflict() {
        PaymentLabelEntity qris = label("QRIS", true);
        when(labels.byId(tenantId, outletId, qris.getId())).thenReturn(Optional.of(qris));
        paidFlow(1);
        service.pay(principal, outletId, orderId,
                request(PaymentMethod.MANUAL_NON_CASH, "100.00", qris.getId(), "pay-key-0000000001"));
        ArgumentCaptor<PaymentEntity> stored = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(payments).persist(stored.capture());
        when(payments.byKey(tenantId, "pay-key-0000000001")).thenReturn(Optional.of(stored.getValue()));

        assertThrows(OperationConflictException.class, () -> service.pay(principal, outletId, orderId,
                request(PaymentMethod.MANUAL_NON_CASH, "100.00", UUID.randomUUID(), "pay-key-0000000001")));
        assertThrows(OperationConflictException.class, () -> service.pay(principal, outletId, orderId,
                request(PaymentMethod.MANUAL_NON_CASH, "100.00", null, "pay-key-0000000001")));
        assertThrows(OperationConflictException.class, () -> service.pay(principal, outletId, orderId,
                request(PaymentMethod.CASH, "100.00", null, "pay-key-0000000001")));
        verify(payments, times(1)).persist(any(PaymentEntity.class));
        verify(payments, times(1)).nextReceiptSequence(any(), any());
    }

    @Test
    void anAlreadyPaidOrderUnderANewKeyConflictsAndDrawsNoNumber() {
        when(payments.byKey(eq(tenantId), anyString())).thenReturn(Optional.empty());
        when(payments.byOrder(tenantId, outletId, orderId)).thenReturn(Optional.of(
                PaymentEntity.settle(tenant, outlet, order, PaymentMethod.CASH, new BigDecimal("100.00"), BigDecimal.ZERO,
                        "INV-20260101-0001", "other-key-00000001", "fp", null, cashier, shift)));

        assertThrows(OperationConflictException.class, () -> service.pay(principal, outletId, orderId,
                request(PaymentMethod.CASH, "100.00", null, "pay-key-0000000002")));
        verify(payments, never()).nextReceiptSequence(any(), any());
        verify(payments, never()).persist(any(PaymentEntity.class));
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
