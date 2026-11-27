package org.lite.project.cashiersaas.service;

import org.lite.project.cashiersaas.configuration.MidtransProperties;
import org.lite.project.cashiersaas.entity.shared.billing.GatewayEventEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Payment provider switch (cashier.billing.provider): MOCK simulates a gateway without an outbound call, MANUAL_TRANSFER
 * leaves settlement to an operator, MIDTRANS creates a Snap redirect payment.
 * shortcut: provider switch inside this class, extract an interface when a second real provider is added.
 */
@Service
public class PaymentGateway {
    public static final String MOCK_PROVIDER = "MOCK";
    public static final String MANUAL_PROVIDER = "MANUAL_TRANSFER";
    public static final String MIDTRANS_PROVIDER = "MIDTRANS";

    private final PlatformBillingRepository billing;
    private final DigestService digests;
    private final MidtransClient midtrans;
    private final MidtransProperties midtransProperties;
    private final String provider;

    public PaymentGateway(PlatformBillingRepository billing, DigestService digests, MidtransClient midtrans,
                          MidtransProperties midtransProperties, @Value("${cashier.billing.provider:MOCK}") String provider) {
        if (!MOCK_PROVIDER.equals(provider) && !MANUAL_PROVIDER.equals(provider) && !MIDTRANS_PROVIDER.equals(provider)) {
            throw new IllegalStateException("cashier.billing.provider must be MOCK, MANUAL_TRANSFER or MIDTRANS.");
        }
        if (MIDTRANS_PROVIDER.equals(provider) && (isBlank(midtransProperties.getServerKey())
                || isBlank(midtransProperties.getMerchantId()) || isBlank(midtransProperties.getFinishUrl()))) {
            throw new IllegalStateException("MIDTRANS_SERVER_KEY, MIDTRANS_MERCHANT_ID and MIDTRANS_FINISH_URL are required "
                    + "when cashier.billing.provider is MIDTRANS.");
        }
        this.billing = billing;
        this.digests = digests;
        this.midtrans = midtrans;
        this.midtransProperties = midtransProperties;
        this.provider = provider;
    }

    public boolean isMockEnabled() {
        return MOCK_PROVIDER.equals(provider);
    }

    public boolean isMidtrans() {
        return MIDTRANS_PROVIDER.equals(provider);
    }

    /** For MIDTRANS the customer is the invoice owner (Snap shows it); both may be null. */
    public PlatformPaymentAttemptEntity newAttempt(PlatformInvoiceEntity invoice, String idempotencyKey, String fingerprint,
                                                   String customerEmail, String customerName) {
        PlatformPaymentAttemptEntity attempt = initiate(invoice, idempotencyKey, fingerprint);
        billing.persist(attempt);
        if (isMidtrans()) {
            attempt.assignProviderPaymentRef(attempt.getId().toString());
            attempt.attachPaymentUrl(midtrans.createSnapRedirect(invoice, attempt, customerEmail, customerName));
        }
        return attempt;
    }

    /**
     * The open attempt of the invoice, or a fresh one when the previous attempt failed. A fresh Midtrans attempt made here has no
     * Snap page: it only records a payment that arrived for an attempt that was already closed.
     */
    public PlatformPaymentAttemptEntity pendingOrNewAttempt(PlatformInvoiceEntity invoice) {
        return billing.pendingAttempts(invoice.getId()).stream().findFirst().orElseGet(() -> {
            String key = "gw-" + UUID.randomUUID();
            PlatformPaymentAttemptEntity attempt = initiate(invoice, key, digests.digest(invoice.getId() + "|" + key));
            billing.persist(attempt);
            return attempt;
        });
    }

    public void recordEvent(PlatformPaymentAttemptEntity attempt, String normalizedStatus, Instant now) {
        String eventId = "mock-evt-" + UUID.randomUUID();
        String eventType = "mock.payment." + normalizedStatus.toLowerCase();
        billing.persist(GatewayEventEntity.processed(attempt, eventId, normalizedStatus, eventType,
                digests.digest(eventId + "|" + attempt.getId() + "|" + normalizedStatus), now));
    }

    private PlatformPaymentAttemptEntity initiate(PlatformInvoiceEntity invoice, String idempotencyKey, String fingerprint) {
        if (isMidtrans()) {
            return PlatformPaymentAttemptEntity.initiate(invoice, MIDTRANS_PROVIDER, midtransProperties.getMerchantId(), null,
                    idempotencyKey, fingerprint);
        }
        return isMockEnabled()
                ? PlatformPaymentAttemptEntity.initiate(invoice, MOCK_PROVIDER, "mock", "mock_" + UUID.randomUUID(), idempotencyKey, fingerprint)
                : PlatformPaymentAttemptEntity.initiate(invoice, MANUAL_PROVIDER, "manual", null, idempotencyKey, fingerprint);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
