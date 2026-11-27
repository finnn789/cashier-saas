package org.lite.project.cashiersaas.service;

import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.configuration.MidtransProperties;
import org.lite.project.cashiersaas.dto.response.MidtransStatus;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.exception.runtime.PaymentGatewayUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The only class that talks to Midtrans (Snap and the status API). Never logs the server key or a response body. */
@Slf4j
@Service
public class MidtransClient {
    private static final String SANDBOX_APP = "https://app.sandbox.midtrans.com";
    private static final String SANDBOX_API = "https://api.sandbox.midtrans.com";
    private static final String PRODUCTION_APP = "https://app.midtrans.com";
    private static final String PRODUCTION_API = "https://api.midtrans.com";
    private static final String UNAVAILABLE = "Pembayaran sedang tidak tersedia, coba lagi.";

    private final MidtransProperties properties;
    private final RestClient restClient;

    @Autowired
    public MidtransClient(MidtransProperties properties) {
        this(properties, buildRestClient());
    }

    /** Test seam: a pre-built client (for example bound to a MockRestServiceServer). */
    public MidtransClient(MidtransProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    /** Creates the Snap transaction and returns the hosted payment page URL. The order id is the attempt id. */
    public String createSnapRedirect(PlatformInvoiceEntity invoice, PlatformPaymentAttemptEntity attempt,
                                     String customerEmail, String customerName) {
        long gross = wholeRupiah(invoice.getAmountDue());
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("order_id", attempt.getId().toString());
        transaction.put("gross_amount", gross);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", invoice.getInvoiceNumber());
        item.put("price", gross);
        item.put("quantity", 1);
        item.put("name", "Tagihan " + invoice.getInvoiceNumber());
        Map<String, Object> expiry = new LinkedHashMap<>();
        expiry.put("unit", "minute");
        expiry.put("duration", Math.max(1, Duration.between(Instant.now(), invoice.getDueAt()).toMinutes()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_details", transaction);
        body.put("item_details", List.of(item));
        if (customerEmail != null || customerName != null) {
            Map<String, Object> customer = new LinkedHashMap<>();
            if (customerEmail != null) {
                customer.put("email", customerEmail);
            }
            if (customerName != null) {
                customer.put("first_name", customerName);
            }
            body.put("customer_details", customer);
        }
        body.put("expiry", expiry);
        body.put("callbacks", Map.of("finish", properties.getFinishUrl()));
        JsonNode response;
        try {
            response = restClient.post().uri(appBase() + "/snap/v1/transactions")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException exception) {
            log.warn("Midtrans Snap request failed: order={} error={}", attempt.getId(), exception.getClass().getSimpleName());
            throw new PaymentGatewayUnavailableException(UNAVAILABLE);
        }
        String redirect = response == null ? null : text(response, "redirect_url");
        if (redirect == null) {
            log.warn("Midtrans Snap answered without redirect_url: order={}", attempt.getId());
            throw new PaymentGatewayUnavailableException(UNAVAILABLE);
        }
        return redirect;
    }

    /** The provider's own view of the order: the truth a webhook body is checked against. */
    public MidtransStatus status(String orderId) {
        JsonNode response;
        try {
            response = restClient.get().uri(apiBase() + "/v2/{orderId}/status", orderId)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth()).accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException exception) {
            log.warn("Midtrans status request failed: order={} error={}", orderId, exception.getClass().getSimpleName());
            throw new PaymentGatewayUnavailableException(UNAVAILABLE);
        }
        String transactionStatus = response == null ? null : text(response, "transaction_status");
        String gross = response == null ? null : text(response, "gross_amount");
        if (transactionStatus == null || gross == null) {
            log.warn("Midtrans status answered without a transaction: order={}", orderId);
            throw new PaymentGatewayUnavailableException(UNAVAILABLE);
        }
        MidtransStatus status = new MidtransStatus();
        status.setTransactionStatus(transactionStatus);
        status.setFraudStatus(text(response, "fraud_status"));
        status.setTransactionId(text(response, "transaction_id"));
        status.setOrderId(text(response, "order_id"));
        try {
            status.setGrossAmount(new BigDecimal(gross));
        } catch (NumberFormatException exception) {
            throw new PaymentGatewayUnavailableException(UNAVAILABLE);
        }
        return status;
    }

    /** sha512(order_id + status_code + gross_amount + serverKey) as lowercase hex, compared in constant time. */
    public boolean validSignature(String orderId, String statusCode, String grossAmount, String signatureKey) {
        String expected = sha512(orderId + statusCode + grossAmount + properties.getServerKey());
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signatureKey.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** Prices are whole rupiah; a fraction means a bug upstream, so fail loudly. */
    private static long wholeRupiah(BigDecimal amount) {
        try {
            return amount.toBigIntegerExact().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Invoice amount must be a whole rupiah amount for Midtrans.", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private String appBase() {
        return properties.isProduction() ? PRODUCTION_APP : SANDBOX_APP;
    }

    private String apiBase() {
        return properties.isProduction() ? PRODUCTION_API : SANDBOX_API;
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((properties.getServerKey() + ":").getBytes(StandardCharsets.UTF_8));
    }

    private static String sha512(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-512 unavailable", exception);
        }
    }

    private static RestClient buildRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
