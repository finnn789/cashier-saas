package org.lite.project.cashiersaas.service;

import org.lite.project.cashiersaas.entity.shared.billing.GatewayEventEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Simulated payment gateway: no outbound call, it only creates attempts and stores the events a real provider would send.
 * With the mock disabled, attempts are MANUAL_TRANSFER and an operator settles the invoice.
 * shortcut: single class instead of a provider interface, extract one when the first real provider is added.
 */
@Service
public class MockPaymentGateway {
    public static final String MOCK_PROVIDER = "MOCK";
    public static final String MANUAL_PROVIDER = "MANUAL_TRANSFER";

    private final PlatformBillingRepository billing;
    private final DigestService digests;
    private final boolean enabled;

    public MockPaymentGateway(PlatformBillingRepository billing, DigestService digests,
                              @Value("${cashier.billing.mock-gateway.enabled:true}") boolean enabled) {
        this.billing = billing;
        this.digests = digests;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public PlatformPaymentAttemptEntity newAttempt(PlatformInvoiceEntity invoice, String idempotencyKey, String fingerprint) {
        PlatformPaymentAttemptEntity attempt = enabled
                ? PlatformPaymentAttemptEntity.initiate(invoice, MOCK_PROVIDER, "mock", "mock_" + UUID.randomUUID(), idempotencyKey, fingerprint)
                : PlatformPaymentAttemptEntity.initiate(invoice, MANUAL_PROVIDER, "manual", null, idempotencyKey, fingerprint);
        billing.persist(attempt);
        return attempt;
    }

    /** The open attempt of the invoice, or a fresh one when the previous attempt failed. */
    public PlatformPaymentAttemptEntity pendingOrNewAttempt(PlatformInvoiceEntity invoice) {
        return billing.pendingAttempts(invoice.getId()).stream().findFirst().orElseGet(() -> {
            String key = "gw-" + UUID.randomUUID();
            return newAttempt(invoice, key, digests.digest(invoice.getId() + "|" + key));
        });
    }

    public void recordEvent(PlatformPaymentAttemptEntity attempt, String normalizedStatus, Instant now) {
        String eventId = "mock-evt-" + UUID.randomUUID();
        String eventType = "mock.payment." + normalizedStatus.toLowerCase();
        billing.persist(GatewayEventEntity.processed(attempt, eventId, normalizedStatus, eventType,
                digests.digest(eventId + "|" + attempt.getId() + "|" + normalizedStatus), now));
    }
}
