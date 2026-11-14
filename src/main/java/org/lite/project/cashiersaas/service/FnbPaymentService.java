package org.lite.project.cashiersaas.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbPaymentMapper;
import org.lite.project.cashiersaas.dto.request.FnbPaymentRequest;
import org.lite.project.cashiersaas.dto.response.FnbPaymentResponse;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentLabelEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentLabelRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FnbPaymentService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final FnbPaymentRepository payments;
    private final FnbPaymentLabelRepository labels;
    private final AuditLogRepository audit;
    private final FnbStockService stock;
    private final FnbShiftService shifts;

    @Transactional
    public FnbPaymentResponse pay(TenantPrincipal principal, UUID outletId, UUID orderId, FnbPaymentRequest request) {
        if (request.getMethod() == null) throw new InvalidOperationException("Metode pembayaran wajib diisi.");
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        SalesOrderEntity order = current(principal, outletId, orderId);
        String fingerprint = fingerprint(request.getMethod(), request.getAmountTendered(), request.getLabelId());
        PaymentEntity keyedPayment = payments.byKey(principal.getTenantId(), request.getIdempotencyKey()).orElse(null);
        if (keyedPayment != null)
            return replayPayment(keyedPayment, orderId, outletId, request.getMethod(), fingerprint);
        if (payments.byOrder(principal.getTenantId(), outletId, orderId).isPresent())
            throw new OperationConflictException("Pesanan sudah dibayar.");
        BigDecimal tendered = request.getAmountTendered();
        if (request.getMethod() == PaymentMethod.CASH && tendered.compareTo(order.getTotal()) < 0)
            throw new InvalidOperationException("Uang tunai kurang dari total pesanan.");
        if (request.getMethod() != PaymentMethod.CASH && tendered.compareTo(order.getTotal()) != 0)
            throw new InvalidOperationException("Pembayaran non-tunai harus sesuai total pesanan.");
        String methodLabel = methodLabel(principal, outletId, request);
        CashShiftEntity shift = shifts.requireOpenShift(context);
        return settle(context, order, request.getMethod(), tendered, request.getIdempotencyKey(), fingerprint, methodLabel, shift);
    }

    FnbPaymentResponse settle(MerchantAccessContext context, SalesOrderEntity order, PaymentMethod method, BigDecimal tendered, String key, String fingerprint, String methodLabel, CashShiftEntity shift) {
        BigDecimal change = method == PaymentMethod.CASH ? tendered.subtract(order.getTotal()) : BigDecimal.ZERO;
        order.confirmPaid();
        PaymentEntity payment = PaymentEntity.settle(context.getTenant(), context.getOutlet(), order, method, tendered, change, receiptNumber(context.getOutlet()), key, fingerprint, methodLabel, context.getMembership().getUser(), shift);
        payments.persist(payment);
        orders.flush();
        List<String> stockWarnings = stock.consumeForPaidOrder(context, order);
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(), AuditLogEntity.Action.FNB_PAYMENT_RECORDED, order.getId(), null, "method=" + method + ";paymentId=" + payment.getId()));
        FnbPaymentResponse response = FnbPaymentMapper.payment(payment);
        response.setStockWarnings(stockWarnings);
        return response;
    }

    SalesOrderEntity current(TenantPrincipal principal, UUID outletId, UUID orderId) {
        SalesOrderEntity order = orders.byId(principal.getTenantId(), outletId, orderId).orElseThrow(ResourceNotFoundException::new);
        orders.refreshOrder(order);
        return orders.byId(principal.getTenantId(), outletId, orderId).orElseThrow(ResourceNotFoundException::new);
    }

    private String methodLabel(TenantPrincipal principal, UUID outletId, FnbPaymentRequest request) {
        UUID labelId = request.getLabelId();
        if (request.getMethod() == PaymentMethod.CASH) {
            if (labelId != null) throw new InvalidOperationException("Pembayaran tunai tidak memakai label.");
            return null;
        }
        if (labelId == null) throw new InvalidOperationException("Pilih label pembayaran non-tunai.");
        return labels.byId(principal.getTenantId(), outletId, labelId).filter(PaymentLabelEntity::isActive)
                .map(PaymentLabelEntity::getName).orElseThrow(() -> new InvalidOperationException("Pilih label pembayaran non-tunai."));
    }

    static String fingerprint(PaymentMethod method, BigDecimal tendered, UUID labelId) {
        String source = method.name() + "|" + normalize(tendered) + "|" + (labelId == null ? "" : labelId);
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

    /** INV-yyyyMMdd-NNNN, numbered per outlet per business day in the outlet zone; 4 digits are a minimum, not a cap. */
    private String receiptNumber(OutletEntity outlet) {
        LocalDate date = LocalDate.now(ZoneId.of(outlet.getTimeZone()));
        return "INV-" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%04d", payments.nextReceiptSequence(outlet.getId(), date));
    }
}
