package org.lite.project.cashiersaas.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbPaymentMapper;
import org.lite.project.cashiersaas.dto.request.FnbRefundRequest;
import org.lite.project.cashiersaas.dto.response.FnbRefundResponse;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentRefundEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentRepository;
import org.lite.project.cashiersaas.repository.FnbRefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FnbRefundService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final FnbPaymentRepository payments;
    private final FnbRefundRepository refunds;
    private final FnbShiftService shifts;
    private final FnbStockService stock;
    private final AuditLogRepository audit;
    private final DigestService digests;
    private final PlanEntitlementService planEntitlementService;

    @Transactional
    public FnbRefundResponse refund(TenantPrincipal principal, UUID outletId, UUID orderId, String key, FnbRefundRequest request) {
        UUID tenantId = principal.getTenantId();
        orders.lockTenant(tenantId);
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        // Before the replay lookup on purpose: a downgraded tenant gets 402 even when retrying an earlier refund key.
        planEntitlementService.require(tenantId, PlanCode.PRO);
        String idempotencyKey = FnbPosOrderService.validatedKey(key);
        String reason = request.getReason().trim();
        if (reason.isEmpty()) throw new InvalidOperationException("Alasan refund wajib diisi.");
        String fingerprint = digests.digest(orderId + "|" + reason);
        SalesOrderEntity order = orders.byId(tenantId, outletId, orderId).orElseThrow(ResourceNotFoundException::new);
        orders.refreshOrder(order);
        PaymentRefundEntity keyed = refunds.byKey(tenantId, idempotencyKey).orElse(null);
        if (keyed != null) return replay(keyed, outletId, orderId, fingerprint);

        PaymentEntity payment = payments.byOrder(tenantId, outletId, orderId)
                .orElseThrow(() -> new OperationConflictException("Hanya pesanan lunas yang dapat direfund."));
        // payment_id is UNIQUE; a second refund under a new key must be a clean 409, not a constraint violation.
        if (refunds.byPayment(tenantId, payment.getId()).isPresent()) throw new OperationConflictException("Pesanan sudah direfund.");
        order.markRefunded();
        CashShiftEntity shift = payment.getMethod() == PaymentMethod.CASH
                ? shifts.requireOpenShift(context) : shifts.findOpenShift(context).orElse(null);
        BigDecimal amount = payment.getAmountPaid().subtract(payment.getChangeAmount());
        PaymentRefundEntity refund = PaymentRefundEntity.record(context.getTenant(), context.getOutlet(), payment, order, amount,
                reason, context.getMembership().getUser(), shift, idempotencyKey, fingerprint);
        refunds.persist(refund);
        orders.flush();
        List<String> stockWarnings = stock.reverseForRefundedOrder(context, order);
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(),
                AuditLogEntity.Action.FNB_ORDER_REFUNDED, order.getId(), reason,
                "amount=" + amount.toPlainString() + " method=" + payment.getMethod()));
        FnbRefundResponse response = FnbPaymentMapper.refund(refund);
        response.setStockWarnings(stockWarnings);
        return response;
    }

    private FnbRefundResponse replay(PaymentRefundEntity refund, UUID outletId, UUID orderId, String fingerprint) {
        if (!refund.getOutlet().getId().equals(outletId) || !refund.getSalesOrder().getId().equals(orderId)
                || !refund.getRequestFingerprint().equals(fingerprint))
            throw new OperationConflictException("Idempotency-Key telah digunakan untuk refund berbeda.");
        return FnbPaymentMapper.refund(refund);
    }
}
