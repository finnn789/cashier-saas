package org.lite.project.cashiersaas.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbPaymentMapper;
import org.lite.project.cashiersaas.dto.request.FnbPaymentRequest;
import org.lite.project.cashiersaas.dto.response.FnbPaymentResponse;
import org.lite.project.cashiersaas.entity.fnb.FnbGatewayAttemptEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbGatewayAttemptRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FnbPaymentService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final FnbPaymentRepository payments;
    private final FnbGatewayAttemptRepository attempts;
    private final AuditLogRepository audit;
    private final MockFnbGatewayService mockGateway;
    private final FnbTableReconciliationService reconciliation;
    private final FnbRealtimePublisher realtime;

    @Transactional
    public FnbPaymentResponse pay(TenantPrincipal principal, UUID outletId, UUID orderId, FnbPaymentRequest request) {
        if (request.getMethod() == PaymentMethod.MOCK_GATEWAY)
            return mockGateway.initiate(principal, outletId, orderId, request);
        if (request.getMethod() == null) throw new InvalidOperationException("Metode pembayaran wajib diisi.");
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        SalesOrderEntity order = current(principal, outletId, orderId);
        String fingerprint = fingerprint(request.getMethod(), request.getAmountTendered());
        PaymentEntity keyedPayment = payments.byKey(principal.getTenantId(), request.getIdempotencyKey()).orElse(null);
        FnbGatewayAttemptEntity keyedAttempt = attempts.byKey(principal.getTenantId(), request.getIdempotencyKey()).orElse(null);
        if (keyedPayment != null)
            return replayPayment(keyedPayment, orderId, outletId, request.getMethod(), fingerprint);
        if (keyedAttempt != null) throw new OperationConflictException("Kunci pembayaran telah digunakan.");
        if (attempts.pendingForOrder(principal.getTenantId(), outletId, orderId).isPresent())
            throw new OperationConflictException("Gateway attempt masih tertunda.");
        if (payments.byOrder(principal.getTenantId(), outletId, orderId).isPresent())
            throw new OperationConflictException("Pesanan sudah dibayar.");
        BigDecimal tendered = request.getAmountTendered();
        if (request.getMethod() == PaymentMethod.CASH && tendered.compareTo(order.getTotal()) < 0)
            throw new InvalidOperationException("Uang tunai kurang dari total pesanan.");
        if (request.getMethod() != PaymentMethod.CASH && tendered.compareTo(order.getTotal()) != 0)
            throw new InvalidOperationException("Pembayaran non-tunai harus sesuai total pesanan.");
        return settle(context, order, request.getMethod(), tendered, request.getIdempotencyKey(), fingerprint, null);
    }

    FnbPaymentResponse settle(MerchantAccessContext context, SalesOrderEntity order, PaymentMethod method, BigDecimal tendered, String key, String fingerprint, String reference) {
        BigDecimal change = method == PaymentMethod.CASH ? tendered.subtract(order.getTotal()) : BigDecimal.ZERO;
        order.confirmPaid();
        PaymentEntity payment = PaymentEntity.settle(context.getTenant(), context.getOutlet(), order, method, tendered, change, receiptNumber(), key, fingerprint, reference, context.getMembership().getUser());
        payments.persist(payment);
        orders.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(), AuditLogEntity.Action.FNB_PAYMENT_RECORDED, order.getId(), null, "method=" + method + ";paymentId=" + payment.getId()));
        reconciliation.reconcile(order);
        realtime.orderChanged("PAYMENT_SETTLED", order);
        return FnbPaymentMapper.payment(payment);
    }

    SalesOrderEntity current(TenantPrincipal principal, UUID outletId, UUID orderId) {
        SalesOrderEntity order = orders.byId(principal.getTenantId(), outletId, orderId).orElseThrow(ResourceNotFoundException::new);
        orders.refreshOrder(order);
        return orders.byId(principal.getTenantId(), outletId, orderId).orElseThrow(ResourceNotFoundException::new);
    }

    static String fingerprint(PaymentMethod method, BigDecimal tendered) {
        String source = method.name() + "|" + normalize(tendered);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private FnbPaymentResponse replayPayment(PaymentEntity payment, UUID orderId, UUID outletId, PaymentMethod method, String fingerprint) {
        if (!payment.getOutlet().getId().equals(outletId) || !payment.getSalesOrder().getId().equals(orderId) || payment.getMethod() != method || !payment.getRequestFingerprint().equals(fingerprint))
            throw new OperationConflictException("Kunci pembayaran telah digunakan.");
        return FnbPaymentMapper.payment(payment);
    }

    private static String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String receiptNumber() {
        return "R-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
