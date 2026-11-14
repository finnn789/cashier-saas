package org.lite.project.cashiersaas.integration.fnb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.lite.project.cashiersaas.integration.support.MerchantFixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Receipts (TASK-078): sequential INV- numbers per outlet per day, the full receipt (footer, label, note, customer,
 * refund marker, escaping) and the receipt-footer business setting, against real PostgreSQL and the security chain.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FnbReceiptIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final String PRICE = "25000";
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;

    private ApiTestSupport api;

    @BeforeAll
    void setUpAll() {
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        api.ensureFnbBusinessType();
    }

    @AfterAll
    void cleanUp() {
        api.truncateTenantData();
    }

    // ------------------------------------------------------------------ helpers

    private static String orders(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/orders";
    }

    private static String settingsUrl(MerchantFixture merchant) {
        return "/api/v1/merchant/outlets/" + merchant.outletId() + "/business-settings";
    }

    private String product(MerchantFixture owner) throws Exception {
        return api.createProduct(owner, api.createCategory(owner, "Minuman"), "Es Teh", PRICE);
    }

    private MvcResult receipt(MerchantFixture actor, MerchantFixture pathMerchant, String orderId) throws Exception {
        return api.call(get(orders(pathMerchant) + "/" + orderId + "/receipt").header(AUTH, actor.bearer()));
    }

    private MvcResult receipt(MerchantFixture actor, String orderId) throws Exception {
        return receipt(actor, actor, orderId);
    }

    private static void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private int count(String sql, Object... args) {
        Integer value = api.jdbc().queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private static void assertReceiptNumber(String number, int sequence, String datePrefixBefore) {
        String after = "INV-" + LocalDate.now(JAKARTA).format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        String suffix = String.format("%04d", sequence);
        assertTrue(number.equals(datePrefixBefore + suffix) || number.equals(after + suffix),
                "expected INV-yyyyMMdd-" + suffix + " but was " + number);
    }

    /** Receipt prefix of the outlet's business day now; a test that straddles midnight accepts the day after too. */
    private static String prefixNow() {
        return "INV-" + LocalDate.now(JAKARTA).format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
    }

    private MvcResult putSettings(MerchantFixture actor, MerchantFixture pathMerchant, String footerOrNull,
                                  boolean sendFooter) throws Exception {
        JsonNode current = JSON.readTree(api.send(get(settingsUrl(pathMerchant)).header(AUTH, pathMerchant.bearer()), 200)
                .getResponse().getContentAsString()).path("data");
        ObjectNode body = ((ObjectNode) current.deepCopy());
        body.remove("outletId");
        body.remove("receiptFooter");
        if (sendFooter) body.put("receiptFooter", footerOrNull);
        return api.call(put(settingsUrl(pathMerchant)).header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private MvcResult checkout(MerchantFixture actor, String productId, String customerLabel, String orderNote) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("serviceType", "DINE_IN");
        if (customerLabel != null) body.put("customerLabel", customerLabel);
        if (orderNote != null) body.put("orderNote", orderNote);
        body.putArray("items").addObject().put("productId", productId).put("quantity", 1).putArray("optionIds");
        return api.send(post(orders(actor)).header(AUTH, actor.bearer()).header("Idempotency-Key", "pos-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)), 201);
    }

    private static String receiptNumberOf(MvcResult paid) throws Exception {
        return ApiTestSupport.read(paid, "$.data.receiptNumber");
    }

    // ------------------------------------------------------------------ receipt numbers

    @Test
    void receiptNumbersAreSequentialPerOutletAndDayAndReplaysAndFailuresConsumeNoNumber() throws Exception {
        MerchantFixture owner = api.onboardOwner("rcp-seq");
        MerchantFixture other = api.onboardOwner("rcp-seq-other");
        String productId = product(owner);
        api.openShift(owner, "0.00");
        api.openShift(other, "0.00");
        String before = prefixNow();

        String order1 = api.createPosOrder(owner, productId, 1);
        String order2 = api.createPosOrder(owner, productId, 1);
        String order3 = api.createPosOrder(owner, productId, 1);
        String key1 = "rcp-" + UUID.randomUUID();
        MvcResult first = api.pay(owner, order1, "CASH", api.orderTotal(owner, order1), null, key1);
        assertTrue(first.getResponse().getStatus() == 200 || first.getResponse().getStatus() == 201,
                first.getResponse().getContentAsString());
        assertReceiptNumber(receiptNumberOf(first), 1, before);
        assertTrue(receiptNumberOf(first).matches("INV-\\d{8}-\\d{4}"), receiptNumberOf(first));

        // Replay of the same key returns the same number and consumes none.
        MvcResult replay = api.pay(owner, order1, "CASH", api.orderTotal(owner, order1), null, key1);
        assertEquals(receiptNumberOf(first), receiptNumberOf(replay));
        assertEquals(1, count("SELECT last_value FROM receipt_sequences WHERE outlet_id = ?", owner.outletId()));

        // Refused payments (already paid, short cash) consume none either.
        assertStatus(409, api.pay(owner, order1, "CASH", api.orderTotal(owner, order1), null, null));
        assertStatus(400, api.pay(owner, order2, "CASH", "1", null, null));
        assertEquals(1, count("SELECT last_value FROM receipt_sequences WHERE outlet_id = ?", owner.outletId()));

        MvcResult second = api.payCashExact(owner, order2);
        MvcResult third = api.payNonCash(owner, order3, api.firstActiveLabelId(owner));
        assertReceiptNumber(receiptNumberOf(second), 2, before);
        assertReceiptNumber(receiptNumberOf(third), 3, before);
        assertEquals(3, count("SELECT last_value FROM receipt_sequences WHERE outlet_id = ?", owner.outletId()));
        assertEquals(3, count("SELECT count(DISTINCT receipt_number) FROM payments WHERE tenant_id = ?", owner.tenantId()));

        // Another outlet has its own sequence starting at 0001.
        MvcResult otherFirst = api.payCashExact(other, api.createPosOrder(other, product(other), 1));
        assertReceiptNumber(receiptNumberOf(otherFirst), 1, before);

        // The receipt endpoint prints the same number.
        assertEquals(receiptNumberOf(third), ApiTestSupport.read(receipt(owner, order3), "$.data.receiptNumber"));

        // A new business day restarts at 0001 and keeps the previous day's row (the existing day is moved back one day
        // first, receipt numbers included, so the new number cannot collide with the unique (outlet, receipt) index).
        String todayStamp = receiptNumberOf(first).substring(4, 12);
        String yesterdayStamp = LocalDate.now(JAKARTA).minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        api.jdbc().update("UPDATE receipt_sequences SET business_date = business_date - 1 WHERE outlet_id = ?", owner.outletId());
        api.jdbc().update("UPDATE payments SET receipt_number = replace(receipt_number, ?, ?) WHERE outlet_id = ?",
                todayStamp, yesterdayStamp, owner.outletId());
        MvcResult nextDay = api.payCashExact(owner, api.createPosOrder(owner, productId, 1));
        assertReceiptNumber(receiptNumberOf(nextDay), 1, before);
        assertEquals(2, count("SELECT count(*) FROM receipt_sequences WHERE outlet_id = ?", owner.outletId()));
        assertEquals(3, count("SELECT last_value FROM receipt_sequences WHERE outlet_id = ? AND business_date = ?",
                owner.outletId(), java.sql.Date.valueOf(LocalDate.now(JAKARTA).minusDays(1))));
        assertEquals(1, count("SELECT last_value FROM receipt_sequences WHERE outlet_id = ? AND business_date = ?",
                owner.outletId(), java.sql.Date.valueOf(LocalDate.now(JAKARTA))));
    }

    @Test
    void parallelPaymentsGetUniqueGaplessReceiptNumbers() throws Exception {
        MerchantFixture owner = api.onboardOwner("rcp-parallel");
        String productId = product(owner);
        api.openShift(owner, "0.00");
        String before = prefixNow();
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            orderIds.add(api.createPosOrder(owner, productId, 1));
        }
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (String orderId : orderIds) {
            String total = api.orderTotal(owner, orderId);
            tasks.add(() -> api.pay(owner, orderId, "CASH", total, null, null));
        }

        List<MvcResult> results = runInParallel(tasks);

        TreeSet<String> numbers = new TreeSet<>();
        for (MvcResult result : results) {
            assertTrue(result.getResponse().getStatus() == 200 || result.getResponse().getStatus() == 201,
                    result.getResponse().getContentAsString());
            numbers.add(receiptNumberOf(result));
        }
        assertEquals(4, numbers.size(), "every parallel payment gets its own number: " + numbers);
        int sequence = 1;
        for (String number : numbers) {
            assertReceiptNumber(number, sequence++, before);
        }
        assertEquals(4, count("SELECT last_value FROM receipt_sequences WHERE outlet_id = ?", owner.outletId()));
    }

    private List<MvcResult> runInParallel(List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (Callable<MvcResult> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "all threads must reach the start gate");
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ receipt content and footer setting

    @Test
    void receiptFooterIsSetClearedValidatedAuditedAndPrintedOnTheReceipt() throws Exception {
        MerchantFixture owner = api.onboardOwner("rcp-footer");
        MerchantFixture cashier = api.onboardCashier(owner, "rcp-footer-cashier");
        MerchantFixture other = api.onboardOwner("rcp-footer-other");
        api.openShift(owner, "0.00");
        String paidOrder = ApiTestSupport.read(checkout(owner, product(owner), "Siti", "tanpa gula"), "$.data.orderId");
        api.payNonCash(owner, paidOrder, api.firstActiveLabelId(owner));

        MvcResult noFooter = receipt(owner, paidOrder);
        assertStatus(200, noFooter);
        assertFalse(noFooter.getResponse().getContentAsString().contains("\"receiptFooter\""), "no footer by default");
        assertEquals("Warung rcp-footer", ApiTestSupport.read(noFooter, "$.data.businessName"));
        assertEquals("Siti", ApiTestSupport.read(noFooter, "$.data.customerLabel"));
        assertEquals("tanpa gula", ApiTestSupport.read(noFooter, "$.data.orderNote"));
        assertEquals(api.jdbc().queryForObject("SELECT method_label FROM payments WHERE sales_order_id = ?::uuid",
                String.class, paidOrder), ApiTestSupport.read(noFooter, "$.data.methodLabel"));
        assertEquals("false", ApiTestSupport.read(noFooter, "$.data.refunded"));
        assertFalse(ApiTestSupport.read(noFooter, "$.data.html").contains("REFUND"));

        MvcResult set = putSettings(owner, owner, "  Terima kasih, sampai jumpa!  ", true);
        assertStatus(200, set);
        assertEquals("Terima kasih, sampai jumpa!", ApiTestSupport.read(set, "$.data.receiptFooter"));
        assertEquals("Terima kasih, sampai jumpa!", ApiTestSupport.read(
                api.send(get(settingsUrl(owner)).header(AUTH, owner.bearer()), 200), "$.data.receiptFooter"));
        MvcResult withFooter = receipt(owner, paidOrder);
        assertEquals("Terima kasih, sampai jumpa!", ApiTestSupport.read(withFooter, "$.data.receiptFooter"));
        assertTrue(ApiTestSupport.read(withFooter, "$.data.html").contains("Terima kasih, sampai jumpa!"));
        assertEquals("Terima kasih, sampai jumpa!", ApiTestSupport.read(receipt(cashier, owner, paidOrder), "$.data.receiptFooter"),
                "the cashier prints the same receipt");

        // The settings audit JSON carries before and after of the footer.
        String details = api.jdbc().queryForObject("SELECT details FROM audit_logs WHERE tenant_id = ? "
                + "AND action = 'BUSINESS_SETTINGS_CHANGED'", String.class, owner.tenantId());
        JsonNode audit = JSON.readTree(details);
        assertTrue(audit.path("before").path("receiptFooter").isNull());
        assertEquals("Terima kasih, sampai jumpa!", audit.path("after").path("receiptFooter").asText());

        // Resubmitting the same footer changes nothing and writes no second audit row.
        assertStatus(200, putSettings(owner, owner, "Terima kasih, sampai jumpa!", true));
        assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'BUSINESS_SETTINGS_CHANGED'",
                owner.tenantId()));

        // 200 characters are accepted, 201 are rejected and leave the footer untouched.
        assertStatus(200, putSettings(owner, owner, "x".repeat(200), true));
        MvcResult tooLong = putSettings(owner, owner, "y".repeat(201), true);
        assertStatus(400, tooLong);
        assertEquals("x".repeat(200), ApiTestSupport.read(
                api.send(get(settingsUrl(owner)).header(AUTH, owner.bearer()), 200), "$.data.receiptFooter"));

        // Blank (or omitted) clears it again.
        MvcResult cleared = putSettings(owner, owner, "   ", true);
        assertStatus(200, cleared);
        assertFalse(cleared.getResponse().getContentAsString().contains("receiptFooter"));
        assertFalse(receipt(owner, paidOrder).getResponse().getContentAsString().contains("\"receiptFooter\""));
        assertStatus(200, putSettings(owner, owner, "Footer lagi", true));
        assertStatus(200, putSettings(owner, owner, null, false));
        assertFalse(api.send(get(settingsUrl(owner)).header(AUTH, owner.bearer()), 200).getResponse()
                .getContentAsString().contains("receiptFooter"), "omitting the field clears it");

        // Owner only; the footer of one tenant never shows on another tenant's receipt.
        assertStatus(403, putSettings(cashier, owner, "Hack", true));
        assertStatus(404, putSettings(other, owner, "Hack", true));
        assertStatus(200, putSettings(other, other, "Footer other", true));
        assertFalse(ApiTestSupport.read(receipt(owner, paidOrder), "$.data.html").contains("Footer other"));
    }

    @Test
    void receiptHtmlEscapesFooterNoteCustomerAndRefundReason() throws Exception {
        MerchantFixture owner = api.onboardOwner("rcp-escape");
        api.openShift(owner, "0.00");
        assertStatus(200, putSettings(owner, owner, "<script>alert(1)</script> & \"x\"", true));
        MvcResult created = checkout(owner, product(owner), "<img src=x onerror=1>", "<b>note</b> & 'q'");
        String orderId = ApiTestSupport.read(created, "$.data.orderId");
        api.payCashExact(owner, orderId);

        String html = ApiTestSupport.read(receipt(owner, orderId), "$.data.html");

        assertFalse(html.contains("<script>"), "raw script tag must not appear");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;x&quot;"), html);
        assertFalse(html.contains("<img src=x"), "raw customer markup must not appear");
        assertTrue(html.contains("&lt;img src=x onerror=1&gt;"), html);
        assertFalse(html.contains("<b>note</b>"), "raw note markup must not appear");
        assertTrue(html.contains("&lt;b&gt;note&lt;/b&gt; &amp; &#39;q&#39;"), html);

        // Raw values stay untouched in the JSON fields (escaping is an HTML concern only).
        MvcResult json = receipt(owner, orderId);
        assertEquals("<img src=x onerror=1>", ApiTestSupport.read(json, "$.data.customerLabel"));
        assertEquals("<b>note</b> & 'q'", ApiTestSupport.read(json, "$.data.orderNote"));
    }

    // ------------------------------------------------------------------ refunded, cancelled, unpaid

    @Test
    void refundedOrdersStillHaveAReceiptWithTheRefundMarkerAndReason() throws Exception {
        MerchantFixture owner = api.onboardOwner("rcp-refund");
        api.openShift(owner, "0.00");
        String orderId = api.createPosOrder(owner, product(owner), 1);
        api.payNonCash(owner, orderId, api.firstActiveLabelId(owner));
        assertEquals("false", ApiTestSupport.read(receipt(owner, orderId), "$.data.refunded"));

        assertStatus(200, api.call(post("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/orders/" + orderId + "/refund")
                .header(AUTH, owner.bearer()).header("Idempotency-Key", "refund-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Salah input <b>\"}")));

        MvcResult refunded = receipt(owner, orderId);
        assertStatus(200, refunded);
        assertEquals("true", ApiTestSupport.read(refunded, "$.data.refunded"));
        assertEquals("Salah input <b>", ApiTestSupport.read(refunded, "$.data.refundReason"));
        assertTrue(ApiTestSupport.read(refunded, "$.data.refundedAt").startsWith(
                LocalDate.now(JAKARTA).toString().substring(0, 7)), "refundedAt is rendered in the outlet zone");
        String html = ApiTestSupport.read(refunded, "$.data.html");
        assertTrue(html.contains("REFUND"), "clear refund marker");
        assertTrue(html.contains("Salah input &lt;b&gt;"), html);
        assertEquals("REFUNDED", ApiTestSupport.read(api.send(get(orders(owner) + "/" + orderId)
                .header(AUTH, owner.bearer()), 200), "$.data.paymentStatus"));
    }

    @Test
    void receiptIsNotFoundForUnpaidCancelledAndForeignOrders() throws Exception {
        MerchantFixture owner = api.onboardOwner("rcp-404");
        MerchantFixture other = api.onboardOwner("rcp-404-other");
        String productId = product(owner);
        api.openShift(owner, "0.00");

        String unpaid = api.createPosOrder(owner, productId, 1);
        assertStatus(404, receipt(owner, unpaid));

        String cancelled = api.createPosOrder(owner, productId, 1);
        api.send(post(orders(owner) + "/" + cancelled + "/cancel").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Salah pesan\"}"), 200);
        assertStatus(404, receipt(owner, cancelled));

        String paid = api.createPosOrder(owner, productId, 1);
        api.payCashExact(owner, paid);
        assertStatus(200, receipt(owner, paid));
        assertStatus(404, receipt(other, owner, paid));
        assertStatus(404, receipt(other, other, paid));
        assertStatus(404, receipt(owner, UUID.randomUUID().toString()));
        assertEquals(401, api.call(get(orders(owner) + "/" + paid + "/receipt")).getResponse().getStatus());
    }
}
