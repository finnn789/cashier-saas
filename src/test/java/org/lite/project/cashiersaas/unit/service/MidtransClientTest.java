package org.lite.project.cashiersaas.unit.service;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.lite.project.cashiersaas.configuration.MidtransProperties;
import org.lite.project.cashiersaas.dto.response.MidtransStatus;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.PaymentGatewayUnavailableException;
import org.lite.project.cashiersaas.service.MidtransClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** TASK-117 D1/D3/D8/D9: the only class that talks to Midtrans. Fake server key only. */
@Tag("unit")
class MidtransClientTest {
    private static final String SERVER_KEY = "SB-Mid-server-test";
    private static final String BASIC = "Basic " + Base64.getEncoder().encodeToString((SERVER_KEY + ":").getBytes(StandardCharsets.UTF_8));
    private static final String REDIRECT = "https://app.sandbox.midtrans.com/snap/v4/redirection/tok-1";

    private final UUID attemptId = UUID.randomUUID();
    private final TenantEntity tenant = new TenantEntity();
    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
    }

    private MidtransClient client(boolean production) {
        MidtransProperties properties = new MidtransProperties();
        properties.setServerKey(SERVER_KEY);
        properties.setMerchantId("G-test");
        properties.setFinishUrl("http://localhost:5173/subscription");
        properties.setProduction(production);
        return new MidtransClient(properties, restClient);
    }

    private PlatformInvoiceEntity invoice(String amount) {
        SubscriptionEntity row = SubscriptionEntity.pending(tenant, PlanCode.PRO, 0);
        return PlatformInvoiceEntity.issue(tenant, row, "INV-1", InvoiceKind.SUBSCRIPTION, "PRO", BillingCycle.MONTHLY, 1, 0, 0,
                new BigDecimal(amount), Instant.now());
    }

    private PlatformPaymentAttemptEntity attempt(PlatformInvoiceEntity invoice) {
        PlatformPaymentAttemptEntity attempt = PlatformPaymentAttemptEntity.initiate(invoice, "MIDTRANS", "G-test",
                attemptId.toString(), "key-0000000000001", "fp");
        ReflectionTestUtils.setField(attempt, "id", attemptId);
        return attempt;
    }

    private static String sha512(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void signatureIsSha512OfOrderIdStatusCodeGrossAmountAndServerKeyAndRejectsATamperedAmount() {
        String signature = sha512("order-1" + "200" + "150000.00" + SERVER_KEY);
        MidtransClient client = client(false);

        assertTrue(client.validSignature("order-1", "200", "150000.00", signature));
        assertFalse(client.validSignature("order-1", "200", "1.00", signature));
        assertFalse(client.validSignature("order-1", "200", "150000.00", "0".repeat(128)));
    }

    @ParameterizedTest
    @CsvSource({"false,https://app.sandbox.midtrans.com", "true,https://app.midtrans.com"})
    void snapRedirectIsRequestedFromTheSandboxOrProductionHostWithBasicAuthAndAnIntegerAmount(boolean production, String host) {
        PlatformInvoiceEntity invoice = invoice("150000.00");
        PlatformPaymentAttemptEntity attempt = attempt(invoice);
        server.expect(once(), requestTo(host + "/snap/v1/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(jsonPath("$.transaction_details.order_id").value(attemptId.toString()))
                .andExpect(jsonPath("$.transaction_details.gross_amount").value(150000))
                .andExpect(jsonPath("$.item_details[0].price").value(150000))
                .andExpect(jsonPath("$.customer_details.email").value("owner@example.test"))
                .andExpect(jsonPath("$.callbacks.finish").value("http://localhost:5173/subscription"))
                .andExpect(jsonPath("$.expiry.unit").value("minute"))
                .andExpect(jsonPath("$.expiry.duration", Matchers.allOf(Matchers.greaterThan(1430), Matchers.lessThanOrEqualTo(1440))))
                .andRespond(withSuccess("{\"token\":\"tok-1\",\"redirect_url\":\"" + REDIRECT + "\"}", MediaType.APPLICATION_JSON));

        assertEquals(REDIRECT, client(production).createSnapRedirect(invoice, attempt, "owner@example.test", "Owner"));

        server.verify();
    }

    @Test
    void anAmountWithAFractionFailsLoudlyWithoutCallingMidtrans() {
        PlatformInvoiceEntity invoice = invoice("150000.50");
        PlatformPaymentAttemptEntity attempt = attempt(invoice);

        assertThrows(IllegalStateException.class, () -> client(false).createSnapRedirect(invoice, attempt, "o@example.test", "Owner"));

        server.verify();
    }

    static Stream<Arguments> snapFailures() {
        return Stream.of(
                Arguments.of("server error", withServerError()),
                Arguments.of("client error", withStatus(HttpStatus.UNAUTHORIZED)),
                Arguments.of("I/O error", withException(new IOException("timeout"))),
                Arguments.of("missing redirect_url", withSuccess("{\"token\":\"tok-1\"}", MediaType.APPLICATION_JSON)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snapFailures")
    void aFailedSnapCallIsReportedAsGatewayUnavailable(String failure, ResponseCreator response) {
        PlatformInvoiceEntity invoice = invoice("150000");
        PlatformPaymentAttemptEntity attempt = attempt(invoice);
        server.expect(once(), requestTo("https://app.sandbox.midtrans.com/snap/v1/transactions")).andRespond(response);

        assertThrows(PaymentGatewayUnavailableException.class,
                () -> client(false).createSnapRedirect(invoice, attempt, "o@example.test", "Owner"));
    }

    @Test
    void statusReadsTheProviderTruthFromTheApiHostWithBasicAuth() {
        server.expect(once(), requestTo("https://api.sandbox.midtrans.com/v2/" + attemptId + "/status"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", BASIC))
                .andRespond(withSuccess("{\"order_id\":\"" + attemptId + "\",\"transaction_id\":\"tx-1\",\"transaction_status\":\"capture\","
                        + "\"fraud_status\":\"accept\",\"gross_amount\":\"150000.00\"}", MediaType.APPLICATION_JSON));

        MidtransStatus status = client(false).status(attemptId.toString());

        assertEquals("capture", status.getTransactionStatus());
        assertEquals("accept", status.getFraudStatus());
        assertEquals("tx-1", status.getTransactionId());
        assertEquals(attemptId.toString(), status.getOrderId());
        assertEquals(0, new BigDecimal("150000").compareTo(status.getGrossAmount()));
    }

    @Test
    void aFailedStatusCallIsReportedAsGatewayUnavailable() {
        server.expect(once(), requestTo("https://api.sandbox.midtrans.com/v2/" + attemptId + "/status")).andRespond(withServerError());

        assertThrows(PaymentGatewayUnavailableException.class, () -> client(false).status(attemptId.toString()));
    }
}
