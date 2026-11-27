package org.lite.project.cashiersaas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.configuration.MidtransProperties;
import org.lite.project.cashiersaas.dto.response.MidtransStatus;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.GatewayEventEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.exception.runtime.AuthenticationRequiredException;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Midtrans payment notifications. The posted body is only a trigger: it must carry a valid signature, and the outcome is
 * re-read from Midtrans before anything is settled. Handled cases return normally (Midtrans then stops retrying);
 * a failed status call propagates so Midtrans retries.
 */
@Slf4j
@Service
public class MidtransWebhookService {
    private static final String[] REQUIRED = {"order_id", "status_code", "gross_amount", "signature_key", "transaction_status"};

    private final MidtransClient midtrans;
    private final PlatformBillingRepository billing;
    private final PlatformBillingSettlementService settlement;
    private final DigestService digests;
    private final AuditLogRepository audit;
    private final MidtransProperties properties;
    private final ObjectMapper json;

    @Autowired
    public MidtransWebhookService(MidtransClient midtrans, PlatformBillingRepository billing, PlatformBillingSettlementService settlement,
                                  DigestService digests, AuditLogRepository audit, MidtransProperties properties) {
        this(midtrans, billing, settlement, digests, audit, properties, new ObjectMapper());
    }

    /** Test seam: a pre-built JSON reader. */
    public MidtransWebhookService(MidtransClient midtrans, PlatformBillingRepository billing, PlatformBillingSettlementService settlement,
                                  DigestService digests, AuditLogRepository audit, MidtransProperties properties, ObjectMapper json) {
        this.midtrans = midtrans;
        this.billing = billing;
        this.settlement = settlement;
        this.digests = digests;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
    }

    @Transactional
    public void handle(String rawBody) {
        JsonNode body = parse(rawBody);
        String orderId = body.get("order_id").asText();
        if (!midtrans.validSignature(orderId, body.get("status_code").asText(), body.get("gross_amount").asText(),
                body.get("signature_key").asText())) {
            throw new AuthenticationRequiredException("Tanda tangan tidak valid.");
        }
        MidtransStatus status = midtrans.status(orderId);
        Optional<PlatformPaymentAttemptEntity> found = billing.attemptByProviderRef(PaymentGateway.MIDTRANS_PROVIDER, orderId);
        if (found.isEmpty()) {
            log.warn("Midtrans notification for an unknown order: order={}", orderId);
            return;
        }
        PlatformPaymentAttemptEntity attempt = found.get();
        String eventId = (status.getTransactionId() == null ? orderId : status.getTransactionId()) + ":" + status.getTransactionStatus();
        if (billing.gatewayEventExists(PaymentGateway.MIDTRANS_PROVIDER, properties.getMerchantId(), eventId)) {
            return;
        }
        PlatformInvoiceEntity invoice = attempt.getPlatformInvoice();
        boolean paid = isPaid(status);
        boolean failed = isFailed(status);
        GatewayEventEntity event = GatewayEventEntity.processed(attempt, eventId, paid ? PlatformPaymentAttemptEntity.SUCCEEDED
                        : failed ? PlatformPaymentAttemptEntity.FAILED : PlatformPaymentAttemptEntity.PENDING,
                "midtrans." + status.getTransactionStatus(), digests.digest(rawBody), Instant.now());
        if (status.getGrossAmount().compareTo(invoice.getAmountDue()) != 0) {
            event.reject();
            rejectedAudit(invoice, "Jumlah dari Midtrans " + status.getGrossAmount().toPlainString() + " berbeda dari tagihan.");
        } else if (paid) {
            try {
                settlement.settle(invoice.getId(), orderId, null, "midtrans");
            } catch (OperationConflictException conflict) {
                event.reject();
                rejectedAudit(invoice, conflict.getMessage());
                log.error("Midtrans payment could not be applied, refund manually: order={} invoice={} reason={}",
                        orderId, invoice.getInvoiceNumber(), conflict.getMessage());
            }
        } else if (failed && attempt.isPending()) {
            try {
                settlement.fail(invoice.getId(), null);
            } catch (OperationConflictException conflict) {
                event.reject();
                rejectedAudit(invoice, conflict.getMessage());
            }
        }
        billing.persist(event);
    }

    private JsonNode parse(String rawBody) {
        JsonNode body;
        try {
            body = json.readTree(rawBody);
        } catch (Exception exception) {
            throw new InvalidOperationException("Notifikasi tidak valid.");
        }
        if (body == null) {
            throw new InvalidOperationException("Notifikasi tidak valid.");
        }
        for (String field : REQUIRED) {
            JsonNode value = body.get(field);
            if (value == null || value.isNull() || value.asText().isBlank()) {
                throw new InvalidOperationException("Notifikasi tidak lengkap: " + field + ".");
            }
        }
        return body;
    }

    private static boolean isPaid(MidtransStatus status) {
        String state = status.getTransactionStatus();
        return "settlement".equals(state) || ("capture".equals(state) && "accept".equals(status.getFraudStatus()));
    }

    private static boolean isFailed(MidtransStatus status) {
        return switch (status.getTransactionStatus()) {
            case "deny", "cancel", "expire", "failure" -> true;
            default -> false;
        };
    }

    private void rejectedAudit(PlatformInvoiceEntity invoice, String reason) {
        audit.persist(AuditLogEntity.recordAction(invoice.getTenant(), null, null, AuditLogEntity.Action.PLATFORM_PAYMENT_FAILED,
                invoice.getId(), reason, "invoice=" + invoice.getInvoiceNumber() + " provider=" + PaymentGateway.MIDTRANS_PROVIDER));
    }
}
