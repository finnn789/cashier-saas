package org.lite.project.cashiersaas.service;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbPaymentMapper;
import org.lite.project.cashiersaas.dto.request.FnbMockPaymentResolutionRequest;
import org.lite.project.cashiersaas.dto.request.FnbPaymentRequest;
import org.lite.project.cashiersaas.dto.response.FnbPaymentResponse;
import org.lite.project.cashiersaas.entity.fnb.FnbGatewayAttemptEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.enums.FnbGatewayOutcome;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.PaymentStatus;
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
public class MockFnbGatewayService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final FnbGatewayAttemptRepository attempts;
    private final FnbPaymentRepository payments;
    private final AuditLogRepository audit;
    private final FnbTableReconciliationService reconciliation;

    @Transactional
    public FnbPaymentResponse initiate(TenantPrincipal principal, UUID outletId, UUID orderId, FnbPaymentRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        SalesOrderEntity order = current(principal, outletId, orderId);
        String fingerprint = FnbPaymentService.fingerprint(PaymentMethod.MOCK_GATEWAY, request.getAmountTendered());
        FnbGatewayAttemptEntity keyedAttempt = attempts.byKey(principal.getTenantId(), request.getIdempotencyKey()).orElse(null);
        PaymentEntity keyedPayment = payments.byKey(principal.getTenantId(), request.getIdempotencyKey()).orElse(null);
        if (keyedAttempt != null) return replayAttempt(keyedAttempt, keyedPayment, orderId, outletId, fingerprint);
        if (keyedPayment != null) throw new OperationConflictException("Kunci pembayaran telah digunakan.");
        if (payments.byOrder(principal.getTenantId(), outletId, orderId).isPresent()) throw new OperationConflictException("Pesanan sudah dibayar.");
        if (attempts.pendingForOrder(principal.getTenantId(), outletId, orderId).isPresent()) throw new OperationConflictException("Gateway attempt masih tertunda.");
        if (order.getStatus() != OrderStatus.NEW || order.getPaymentStatus() != PaymentStatus.UNPAID) throw new OperationConflictException("Mock gateway hanya dapat dimulai untuk pesanan baru yang belum dibayar.");
        if (request.getAmountTendered().compareTo(order.getTotal()) != 0) throw new InvalidOperationException("Jumlah gateway harus sesuai total pesanan.");
        FnbGatewayAttemptEntity attempt = FnbGatewayAttemptEntity.pending(context.getTenant(), context.getOutlet(), order, context.getMembership().getUser(), order.getTotal(), request.getIdempotencyKey(), fingerprint, "MOCK-" + UUID.randomUUID());
        attempts.persist(attempt); orders.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(), AuditLogEntity.Action.FNB_GATEWAY_ATTEMPT_CREATED, attempt.getId(), null, "provider=MOCK"));
        return FnbPaymentMapper.attempt(attempt);
    }

    @Transactional
    public FnbPaymentResponse resolve(TenantPrincipal principal, UUID outletId, UUID orderId, UUID attemptId, FnbMockPaymentResolutionRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        SalesOrderEntity order = current(principal, outletId, orderId);
        FnbGatewayAttemptEntity attempt = attempts.byId(principal.getTenantId(), outletId, attemptId).orElseThrow(ResourceNotFoundException::new);
        if (!attempt.getSalesOrder().getId().equals(orderId)) throw new ResourceNotFoundException();
        if (attempt.getOutcome() != FnbGatewayOutcome.PENDING) {
            if (attempt.getOutcome() != request.getOutcome()) throw new OperationConflictException("Gateway attempt sudah memiliki outcome terminal.");
            return terminalReplay(principal, outletId, attempt);
        }
        if (request.getOutcome() == null || request.getOutcome() == FnbGatewayOutcome.PENDING) throw new InvalidOperationException("Outcome resolusi harus terminal.");
        if (request.getOutcome() == FnbGatewayOutcome.SUCCEEDED && attempt.getAmount().compareTo(order.getTotal()) != 0) throw new OperationConflictException("Jumlah gateway tidak lagi sesuai total pesanan.");
        attempt.resolve(request.getOutcome(), context.getMembership().getUser());
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(), AuditLogEntity.Action.FNB_GATEWAY_ATTEMPT_RESOLVED, attempt.getId(), null, "outcome=" + request.getOutcome()));
        if (request.getOutcome() == FnbGatewayOutcome.SUCCEEDED) return settle(context, order, attempt);
        return FnbPaymentMapper.attempt(attempt);
    }

    private FnbPaymentResponse terminalReplay(TenantPrincipal principal, UUID outletId, FnbGatewayAttemptEntity attempt) {
        if (attempt.getOutcome() == FnbGatewayOutcome.FAILED) return FnbPaymentMapper.attempt(attempt);
        PaymentEntity payment = payments.byOrder(principal.getTenantId(), outletId, attempt.getSalesOrder().getId()).orElseThrow(() -> new OperationConflictException("Pembayaran gateway berhasil tidak ditemukan."));
        return FnbPaymentMapper.mockPayment(payment, attempt);
    }
    private SalesOrderEntity current(TenantPrincipal principal, UUID outletId, UUID orderId) {
        SalesOrderEntity order = orders.byId(principal.getTenantId(), outletId, orderId).orElseThrow(ResourceNotFoundException::new);
        orders.refreshOrder(order);
        return orders.byId(principal.getTenantId(), outletId, orderId).orElseThrow(ResourceNotFoundException::new);
    }
    private FnbPaymentResponse replayAttempt(FnbGatewayAttemptEntity attempt, PaymentEntity payment, UUID orderId, UUID outletId, String fingerprint) {
        if (!attempt.getOutlet().getId().equals(outletId) || !attempt.getSalesOrder().getId().equals(orderId) || !attempt.getRequestFingerprint().equals(fingerprint)) throw new OperationConflictException("Kunci pembayaran telah digunakan.");
        if (attempt.getOutcome() == FnbGatewayOutcome.SUCCEEDED) {
            if (payment == null) throw new OperationConflictException("Pembayaran gateway berhasil tidak ditemukan.");
            return FnbPaymentMapper.mockPayment(payment, attempt);
        }
        if (payment != null) throw new OperationConflictException("Kunci pembayaran tidak konsisten.");
        return FnbPaymentMapper.attempt(attempt);
    }
    private FnbPaymentResponse settle(MerchantAccessContext context, SalesOrderEntity order, FnbGatewayAttemptEntity attempt) {
        if (payments.byOrder(context.getTenant().getId(), context.getOutlet().getId(), order.getId()).isPresent()) throw new OperationConflictException("Pesanan sudah dibayar.");
        order.confirmPaid();
        PaymentEntity payment = PaymentEntity.settle(context.getTenant(), context.getOutlet(), order, PaymentMethod.MOCK_GATEWAY, attempt.getAmount(), BigDecimal.ZERO, receiptNumber(), attempt.getIdempotencyKey(), attempt.getRequestFingerprint(), attempt.getProviderReference(), context.getMembership().getUser());
        payments.persist(payment); orders.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(), AuditLogEntity.Action.FNB_PAYMENT_RECORDED, order.getId(), null, "method=MOCK_GATEWAY;paymentId=" + payment.getId()));
        reconciliation.reconcile(order);
        return FnbPaymentMapper.mockPayment(payment, attempt);
    }
    private static String receiptNumber() { return "R-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(); }
}
