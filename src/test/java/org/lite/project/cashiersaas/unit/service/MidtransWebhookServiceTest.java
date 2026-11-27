package org.lite.project.cashiersaas.unit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.configuration.MidtransProperties;
import org.lite.project.cashiersaas.dto.response.MidtransStatus;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.GatewayEventEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.AuthenticationRequiredException;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.PaymentGatewayUnavailableException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.lite.project.cashiersaas.service.DigestService;
import org.lite.project.cashiersaas.service.MidtransClient;
import org.lite.project.cashiersaas.service.MidtransWebhookService;
import org.lite.project.cashiersaas.service.PlatformBillingSettlementService;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** TASK-117 D8: verified, idempotent Midtrans notifications. The provider status call is the truth, never the posted body. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MidtransWebhookServiceTest {
    private static final String MERCHANT = "G-test";

    @Mock private MidtransClient midtrans;
    @Mock private PlatformBillingRepository billing;
    @Mock private PlatformBillingSettlementService settlement;
    @Mock private AuditLogRepository audit;
    private final DigestService digests = new DigestService();

    private final UUID invoiceId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final TenantEntity tenant = new TenantEntity();
    private PlatformPaymentAttemptEntity attempt;
    private MidtransWebhookService service;

    @BeforeEach
    void setUp() {
        MidtransProperties properties = new MidtransProperties();
        properties.setServerKey("SB-Mid-server-test");
        properties.setMerchantId(MERCHANT);
        properties.setFinishUrl("http://localhost:5173/subscription");
        service = new MidtransWebhookService(midtrans, billing, settlement, digests, audit, properties, new ObjectMapper());
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        SubscriptionEntity row = SubscriptionEntity.pending(tenant, PlanCode.PRO, 0);
        PlatformInvoiceEntity invoice = PlatformInvoiceEntity.issue(tenant, row, "INV-1", InvoiceKind.SUBSCRIPTION, "PRO",
                BillingCycle.MONTHLY, 1, 0, 0, new BigDecimal("150000"), Instant.now());
        ReflectionTestUtils.setField(invoice, "id", invoiceId);
        attempt = PlatformPaymentAttemptEntity.initiate(invoice, "MIDTRANS", MERCHANT, orderId.toString(), "key-0000000000001", "fp");
        ReflectionTestUtils.setField(attempt, "id", orderId);
    }

    private String body() {
        return "{\"order_id\":\"" + orderId + "\",\"status_code\":\"200\",\"gross_amount\":\"150000.00\","
                + "\"signature_key\":\"sig\",\"transaction_status\":\"settlement\"}";
    }

    private void signatureIsValid() {
        when(midtrans.validSignature(orderId.toString(), "200", "150000.00", "sig")).thenReturn(true);
    }

    private void providerSays(String transactionStatus, String fraudStatus, String grossAmount) {
        MidtransStatus status = new MidtransStatus();
        status.setTransactionStatus(transactionStatus);
        status.setFraudStatus(fraudStatus);
        status.setGrossAmount(new BigDecimal(grossAmount));
        status.setTransactionId("tx-1");
        status.setOrderId(orderId.toString());
        when(midtrans.status(orderId.toString())).thenReturn(status);
    }

    private void attemptIsKnown() {
        when(billing.attemptByProviderRef("MIDTRANS", orderId.toString())).thenReturn(Optional.of(attempt));
    }

    private GatewayEventEntity storedEvent() {
        ArgumentCaptor<GatewayEventEntity> event = ArgumentCaptor.forClass(GatewayEventEntity.class);
        verify(billing).persist(event.capture());
        return event.getValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_id", "status_code", "gross_amount", "signature_key", "transaction_status"})
    void aBodyMissingARequiredFieldIsABadRequestAndNothingIsCalled(String missing) {
        String raw = body().replaceFirst("\"" + missing + "\":\"[^\"]*\",?", "").replace(",}", "}");

        assertThrows(InvalidOperationException.class, () -> service.handle(raw));

        verifyNoInteractions(midtrans, settlement, audit);
        verify(billing, never()).persist(any(GatewayEventEntity.class));
    }

    @Test
    void aBadSignatureIsUnauthorizedAndNothingIsStoredOrSettled() {
        when(midtrans.validSignature(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        assertThrows(AuthenticationRequiredException.class, () -> service.handle(body()));

        verify(midtrans, never()).status(anyString());
        verify(billing, never()).persist(any(GatewayEventEntity.class));
        verifyNoInteractions(settlement);
    }

    @Test
    void aFailedStatusCallPropagatesSoMidtransRetries() {
        signatureIsValid();
        when(midtrans.status(orderId.toString())).thenThrow(new PaymentGatewayUnavailableException("down"));

        assertThrows(PaymentGatewayUnavailableException.class, () -> service.handle(body()));

        verify(billing, never()).persist(any(GatewayEventEntity.class));
        verifyNoInteractions(settlement);
    }

    @Test
    void anUnknownOrderIsAcknowledgedWithoutStoringOrSettling() {
        signatureIsValid();
        providerSays("settlement", null, "150000.00");
        when(billing.attemptByProviderRef("MIDTRANS", orderId.toString())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.handle(body()));

        verify(billing, never()).persist(any(GatewayEventEntity.class));
        verifyNoInteractions(settlement);
    }

    @Test
    void settlementSettlesTheInvoiceOnceAndStoresTheEventWithTheRawBodyDigest() {
        signatureIsValid();
        providerSays("settlement", null, "150000.00");
        attemptIsKnown();

        service.handle(body());

        verify(settlement).settle(invoiceId, orderId.toString(), null, "midtrans");
        GatewayEventEntity event = storedEvent();
        assertEquals("MIDTRANS", event.getProviderCode());
        assertEquals("tx-1:settlement", event.getProviderEventId());
        assertEquals(digests.digest(body()), event.getPayloadDigest());
    }

    @Test
    void aRepeatedEventIsAcknowledgedAsANoOp() {
        signatureIsValid();
        providerSays("settlement", null, "150000.00");
        attemptIsKnown();
        when(billing.gatewayEventExists("MIDTRANS", MERCHANT, "tx-1:settlement")).thenReturn(true);

        assertDoesNotThrow(() -> service.handle(body()));

        verify(billing, never()).persist(any(GatewayEventEntity.class));
        verifyNoInteractions(settlement);
    }

    @ParameterizedTest
    @CsvSource({"capture,accept,true", "capture,challenge,false", "pending,,false"})
    void captureIsSettledOnlyWhenFraudAcceptedAndPendingChangesNothing(String status, String fraud, boolean settles) {
        signatureIsValid();
        providerSays(status, fraud, "150000.00");
        attemptIsKnown();

        service.handle(body());

        if (settles) {
            verify(settlement).settle(invoiceId, orderId.toString(), null, "midtrans");
        } else {
            verifyNoInteractions(settlement);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deny", "cancel", "expire", "failure"})
    void aTerminalFailureFailsThePendingAttempt(String status) {
        signatureIsValid();
        providerSays(status, null, "150000.00");
        attemptIsKnown();

        service.handle(body());

        verify(settlement).fail(invoiceId, null);
        verify(settlement, never()).settle(any(), any(), any(), any());
    }

    @Test
    void aFailureForAnAttemptThatIsNoLongerPendingIsIgnored() {
        signatureIsValid();
        providerSays("expire", null, "150000.00");
        attempt.fail();
        attemptIsKnown();

        service.handle(body());

        verifyNoInteractions(settlement);
    }

    @Test
    void anAmountThatDiffersFromTheInvoiceIsRejectedAuditedAndNeverSettled() {
        signatureIsValid();
        providerSays("settlement", null, "1000.00");
        attemptIsKnown();

        assertDoesNotThrow(() -> service.handle(body()));

        assertEquals("REJECTED", storedEvent().getProcessingStatus());
        verify(audit).persist(any(AuditLogEntity.class));
        verifyNoInteractions(settlement);
    }

    @Test
    void aSettlementConflictIsAcknowledgedRecordedAsRejectedAndAudited() {
        signatureIsValid();
        providerSays("settlement", null, "150000.00");
        attemptIsKnown();
        when(settlement.settle(invoiceId, orderId.toString(), null, "midtrans"))
                .thenThrow(new OperationConflictException("Tagihan sudah kedaluwarsa."));

        assertDoesNotThrow(() -> service.handle(body()));

        assertEquals("REJECTED", storedEvent().getProcessingStatus());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("PLATFORM_PAYMENT_FAILED", log.getValue().getAction());
    }
}
