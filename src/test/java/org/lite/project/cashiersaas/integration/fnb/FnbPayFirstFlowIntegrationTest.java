package org.lite.project.cashiersaas.integration.fnb;

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/** End-to-end Pay First flow against real PostgreSQL, real Flyway schema and the real security chain. */
class FnbPayFirstFlowIntegrationTest extends AbstractPostgresIntegrationTest {

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

    private static final String PRICE = "25000";

    private static String posOrderJson(String productId, int quantity) {
        return "{\"serviceType\":\"DINE_IN\",\"customerLabel\":\"Budi\","
                + "\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + ",\"note\":\"less ice\",\"optionIds\":[]}]}";
    }

    private static String paymentJson(String method, String tendered, String key) {
        return "{\"method\":\"" + method + "\",\"amountTendered\":" + tendered + ",\"idempotencyKey\":\"" + key + "\"}";
    }

    private String orders(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/orders";
    }

    private String createPosOrder(MerchantFixture merchant, String productId, int quantity)
            throws Exception {
        MvcResult created = api.send(post(orders(merchant)).header(HttpHeaders.AUTHORIZATION, merchant.bearer())
                .header("Idempotency-Key", "pos-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(posOrderJson(productId, quantity)), 201);
        assertEquals("NEW", ApiTestSupport.read(created, "$.data.status"));
        assertEquals("UNPAID", ApiTestSupport.read(created, "$.data.paymentStatus"));
        assertEquals("POS", ApiTestSupport.read(created, "$.data.source"));
        return ApiTestSupport.read(created, "$.data.orderId");
    }

    private MvcResult getOrder(MerchantFixture merchant, String orderId) throws Exception {
        return api.send(get(orders(merchant) + "/" + orderId).header(HttpHeaders.AUTHORIZATION, merchant.bearer()), 200);
    }

    private static BigDecimal money(MvcResult result, String path) throws Exception {
        return new BigDecimal(ApiTestSupport.read(result, path));
    }

    private int countPayments(String orderId) {
        Integer count = api.jdbc().queryForObject(
                "SELECT count(*) FROM payments WHERE sales_order_id = ?::uuid", Integer.class, orderId);
        return count == null ? -1 : count;
    }

    @Test
    void posOrderCashPaymentAndSalesReport() throws Exception {
        MerchantFixture merchant = api.onboardOwner("pos");
        String categoryId = api.createCategory(merchant, "Minuman");
        String productId = api.createProduct(merchant, categoryId, "Es Teh", PRICE);

        String orderId = createPosOrder(merchant, productId, 2);
        BigDecimal total = money(getOrder(merchant, orderId), "$.data.total");
        assertTrue(total.compareTo(new BigDecimal("50000")) >= 0, "total includes subtotal 2 x " + PRICE);

        BigDecimal tendered = total.add(new BigDecimal("5000"));
        MvcResult paid = api.send(post(orders(merchant) + "/" + orderId + "/payments")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("CASH", tendered.toPlainString(), "pay-" + UUID.randomUUID())), 200, 201);
        assertEquals(0, new BigDecimal("5000").compareTo(money(paid, "$.data.changeAmount")), "change");
        assertEquals("CASH", ApiTestSupport.read(paid, "$.data.method"));
        assertNotNull(ApiTestSupport.read(paid, "$.data.receiptNumber"));

        MvcResult afterPay = getOrder(merchant, orderId);
        assertEquals("CONFIRMED", ApiTestSupport.read(afterPay, "$.data.status"));
        assertEquals("PAID", ApiTestSupport.read(afterPay, "$.data.paymentStatus"));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        MvcResult report = api.send(get("/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/reports/sales")
                .param("startDate", today.minusDays(1).toString()).param("endDate", today.plusDays(1).toString())
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()), 200);
        assertEquals("1", ApiTestSupport.read(report, "$.data.summary.transactionCount"));
        assertEquals(0, total.compareTo(money(report, "$.data.summary.total")), "report total");
        assertEquals(orderId, ApiTestSupport.read(report, "$.data.transactions[0].orderId"));
        assertEquals("CASH", ApiTestSupport.read(report, "$.data.transactions[0].paymentMethod"));
        assertEquals(productId, ApiTestSupport.read(report, "$.data.topProducts[0].productId"));
        assertEquals("2", ApiTestSupport.read(report, "$.data.topProducts[0].quantity"));
        assertEquals("CASH", ApiTestSupport.read(report, "$.data.paymentBreakdown[0].method"));
        assertEquals("1", ApiTestSupport.read(report, "$.data.paymentBreakdown[0].transactionCount"));
        assertEquals(1, countPayments(orderId));
    }

    @Test
    void posOrderWithIdempotencyKeyThenCashPayment() throws Exception {
        MerchantFixture merchant = api.onboardOwner("idemorder");
        String categoryId = api.createCategory(merchant, "Makanan");
        String productId = api.createProduct(merchant, categoryId, "Nasi Goreng", PRICE);

        String key = "pos-" + UUID.randomUUID();
        String body = posOrderJson(productId, 1);
        MvcResult first = api.send(post(orders(merchant)).header(HttpHeaders.AUTHORIZATION, merchant.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body), 201);
        assertEquals("NEW", ApiTestSupport.read(first, "$.data.status"));
        assertEquals("UNPAID", ApiTestSupport.read(first, "$.data.paymentStatus"));
        String orderNumber = ApiTestSupport.read(first, "$.data.orderNumber");
        String orderId = ApiTestSupport.read(first, "$.data.orderId");

        // Same key + same body: same order, no duplicate.
        MvcResult retry = api.send(post(orders(merchant)).header(HttpHeaders.AUTHORIZATION, merchant.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body), 200, 201);
        assertEquals(orderNumber, ApiTestSupport.read(retry, "$.data.orderNumber"));
        Integer orderRows = api.jdbc().queryForObject(
                "SELECT count(*) FROM sales_orders WHERE tenant_id = ?", Integer.class, merchant.tenantId());
        assertEquals(1, orderRows, "idempotent retry must not create a second order");

        // Same key + different body: conflict.
        MvcResult changed = api.call(post(orders(merchant)).header(HttpHeaders.AUTHORIZATION, merchant.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(posOrderJson(productId, 3)));
        assertEquals(409, changed.getResponse().getStatus());

        MvcResult unpaid = getOrder(merchant, orderId);
        assertEquals("UNPAID", ApiTestSupport.read(unpaid, "$.data.paymentStatus"));
        assertEquals("NEW", ApiTestSupport.read(unpaid, "$.data.status"));

        MvcResult list = api.send(get(orders(merchant)).param("source", "POS")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()), 200);
        assertEquals(orderNumber, ApiTestSupport.read(list, "$.data.orders[0].orderNumber"));
        assertEquals(orderId, ApiTestSupport.read(list, "$.data.orders[0].orderId"));
        BigDecimal total = money(list, "$.data.orders[0].total");

        MvcResult paid = api.send(post(orders(merchant) + "/" + orderId + "/payments")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("CASH", total.add(BigDecimal.valueOf(1000)).toPlainString(),
                        "pay-" + UUID.randomUUID())), 200, 201);
        assertEquals(0, new BigDecimal("1000").compareTo(money(paid, "$.data.changeAmount")));

        MvcResult afterPay = getOrder(merchant, orderId);
        assertEquals("PAID", ApiTestSupport.read(afterPay, "$.data.paymentStatus"));
        assertEquals("CONFIRMED", ApiTestSupport.read(afterPay, "$.data.status"));
    }

    @Test
    void paymentIdempotencyReplaysSameRequestAndRejectsDifferentBody() throws Exception {
        MerchantFixture merchant = api.onboardOwner("idem");
        String productId = api.createProduct(merchant, api.createCategory(merchant, "Kopi"), "Latte", PRICE);
        String orderId = createPosOrder(merchant, productId, 1);
        BigDecimal total = money(getOrder(merchant, orderId), "$.data.total");
        BigDecimal tendered = total.add(new BigDecimal("2000"));
        String key = "pay-" + UUID.randomUUID();

        MvcResult first = api.send(post(orders(merchant) + "/" + orderId + "/payments")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("CASH", tendered.toPlainString(), key)), 200, 201);
        MvcResult replay = api.send(post(orders(merchant) + "/" + orderId + "/payments")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("CASH", tendered.toPlainString(), key)), 200, 201);
        assertEquals(ApiTestSupport.read(first, "$.data.receiptNumber"),
                ApiTestSupport.read(replay, "$.data.receiptNumber"), "replay returns the same payment");
        assertEquals(1, countPayments(orderId));

        MvcResult different = api.call(post(orders(merchant) + "/" + orderId + "/payments")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("CASH", tendered.add(BigDecimal.TEN).toPlainString(), key)));
        assertEquals(409, different.getResponse().getStatus(), "same key with different body conflicts");

        MvcResult newKey = api.call(post(orders(merchant) + "/" + orderId + "/payments")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("CASH", tendered.toPlainString(), "pay-" + UUID.randomUUID())));
        assertEquals(409, newKey.getResponse().getStatus(), "already paid order rejects a new key");
        assertEquals(1, countPayments(orderId));
    }

    @Test
    void concurrentPaymentsWithDifferentKeysSettleExactlyOnce() throws Exception {
        MerchantFixture merchant = api.onboardOwner("race");
        String productId = api.createProduct(merchant, api.createCategory(merchant, "Snack"), "Roti", PRICE);
        String orderId = createPosOrder(merchant, productId, 1);
        BigDecimal total = money(getOrder(merchant, orderId), "$.data.total");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String key = "race-" + i + "-" + UUID.randomUUID();
                Callable<Integer> task = () -> {
                    ready.countDown();
                    go.await();
                    return api.call(post(orders(merchant) + "/" + orderId + "/payments")
                            .header(HttpHeaders.AUTHORIZATION, merchant.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(paymentJson("CASH", total.toPlainString(), key))).getResponse().getStatus();
                };
                futures.add(pool.submit(task));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            go.countDown();
            int ok = 0;
            int conflict = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(60, TimeUnit.SECONDS);
                if (status == 200 || status == 201) {
                    ok++;
                } else if (status == 409) {
                    conflict++;
                }
            }
            assertEquals(1, ok, "exactly one payment succeeds");
            assertEquals(threads - 1, conflict, "all others get 409 conflict");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, countPayments(orderId), "exactly one payment row");
        assertEquals("PAID", ApiTestSupport.read(getOrder(merchant, orderId), "$.data.paymentStatus"));
    }

    @Test
    void tenantBCannotSeeOrPayTenantAOrder() throws Exception {
        MerchantFixture tenantA = api.onboardOwner("tenantA");
        MerchantFixture tenantB = api.onboardOwner("tenantB");
        String productId = api.createProduct(tenantA, api.createCategory(tenantA, "Menu"), "Mie", PRICE);
        String orderId = createPosOrder(tenantA, productId, 1);

        // Tenant B principal against its own outlet but tenant A's order id.
        assertEquals(404, api.call(get(orders(tenantB) + "/" + orderId)
                .header(HttpHeaders.AUTHORIZATION, tenantB.bearer())).getResponse().getStatus());
        // Tenant B principal against tenant A's outlet path.
        assertEquals(404, api.call(get(orders(tenantA) + "/" + orderId)
                .header(HttpHeaders.AUTHORIZATION, tenantB.bearer())).getResponse().getStatus());
        MvcResult pay = api.call(post(orders(tenantA) + "/" + orderId + "/payments")
                .header(HttpHeaders.AUTHORIZATION, tenantB.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("CASH", "100000", "x-" + UUID.randomUUID())));
        assertTrue(pay.getResponse().getStatus() == 404 || pay.getResponse().getStatus() == 403,
                "cross-tenant payment must be refused, was " + pay.getResponse().getStatus());
        assertEquals(0, countPayments(orderId));
        assertEquals("UNPAID", ApiTestSupport.read(getOrder(tenantA, orderId), "$.data.paymentStatus"));
    }
}
