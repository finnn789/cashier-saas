package org.lite.project.cashiersaas.integration.fnb;

import com.jayway.jsonpath.JsonPath;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Concurrency behaviour of the ingredient stock ledger (TASK-067) against real PostgreSQL, real Flyway schema and
 * the real security chain: parallel movement idempotency keys, parallel payments and a mixed no-deadlock load.
 * Every scenario releases its requests together through a start gate, runs them on a fixed pool of 8 threads and
 * waits for every response with a bounded timeout.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FnbStockConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final String PRICE = "25000";
    private static final int THREADS = 8;
    private static final int WAIT_SECONDS = 60;
    private static final int ROUNDS = 15;

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

    // ------------------------------------------------------------------ paths and JSON builders

    private static String adminItems(MerchantFixture merchant) {
        return "/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/inventory/items";
    }

    private static String stockItems(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/inventory/items";
    }

    private static String orders(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/orders";
    }

    private static String productRecipeUrl(MerchantFixture merchant, String productId) {
        return "/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/products/" + productId + "/recipe";
    }

    private static String itemJson(String name, String unit, String reorderLevel, String initialQuantity) {
        return "{\"name\":\"" + name + "\",\"unit\":\"" + unit + "\",\"reorderLevel\":" + reorderLevel
                + (initialQuantity == null ? "" : ",\"initialQuantity\":" + initialQuantity) + "}";
    }

    private static String recipeJson(String itemId, String quantityPerUnit) {
        return "{\"lines\":[{\"inventoryItemId\":\"" + itemId + "\",\"quantityPerUnit\":" + quantityPerUnit + "}]}";
    }

    private static String movementJson(String type, String quantity, String reason, String key) {
        return "{\"type\":\"" + type + "\",\"quantity\":" + quantity
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + ",\"idempotencyKey\":\"" + key + "\"}";
    }

    private static String paymentJson(String method, String tendered, String key) {
        return "{\"method\":\"" + method + "\",\"amountTendered\":" + tendered + ",\"idempotencyKey\":\"" + key + "\"}";
    }

    // ------------------------------------------------------------------ API helpers

    private String createIngredient(MerchantFixture owner, String name, String unit, String reorderLevel,
                                    String initialQuantity) throws Exception {
        return ApiTestSupport.read(api.send(post(adminItems(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson(name, unit, reorderLevel, initialQuantity)),
                201), "$.data.id");
    }

    private void setRecipe(MerchantFixture owner, String productId, String itemId, String quantityPerUnit)
            throws Exception {
        MvcResult result = api.call(put(productRecipeUrl(owner, productId)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(recipeJson(itemId, quantityPerUnit)));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** Creates an unpaid POS takeaway order of one unit (no table needed) and returns its id. */
    private String createOrder(MerchantFixture actor, String productId) throws Exception {
        String body = "{\"serviceType\":\"TAKEAWAY\",\"customerLabel\":\"Budi\",\"items\":[{\"productId\":\""
                + productId + "\",\"quantity\":1,\"optionIds\":[]}]}";
        MvcResult created = api.send(post(orders(actor)).header(AUTH, actor.bearer())
                .header("Idempotency-Key", "pos-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(body), 201);
        assertEquals("UNPAID", ApiTestSupport.read(created, "$.data.paymentStatus"));
        return ApiTestSupport.read(created, "$.data.orderId");
    }

    private MvcResult getOrder(MerchantFixture actor, String orderId) throws Exception {
        return api.send(get(orders(actor) + "/" + orderId).header(AUTH, actor.bearer()), 200);
    }

    /** Raw payment call: the status is NOT asserted here, the scenarios assert the legal outcome set. */
    private MvcResult payCashCall(MerchantFixture actor, String orderId, String key) throws Exception {
        return api.call(post(orders(actor) + "/" + orderId + "/payments").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson("CASH", "1000000", key)));
    }

    /** Raw movement call: the status is NOT asserted here, the scenarios assert the legal outcome set. */
    private MvcResult postMovement(MerchantFixture actor, String itemId, String body) throws Exception {
        return api.call(post(stockItems(actor) + "/" + itemId + "/movements").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Raw cancel call (cashier route): the status is NOT asserted here, the scenarios assert the legal outcome set. */
    private MvcResult cancelCall(MerchantFixture actor, String orderId) throws Exception {
        return api.call(post(orders(actor) + "/" + orderId + "/cancel").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Salah pesan\"}"));
    }

    /** Raw NOMINAL 1000 discount call (owner-only merchant route); the status is NOT asserted here. */
    private MvcResult discountCall(MerchantFixture owner, String orderId) throws Exception {
        return api.call(post("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/orders/" + orderId + "/discount")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NOMINAL\",\"value\":1000,\"reason\":\"Promo\"}"));
    }

    // ------------------------------------------------------------------ JSON, SQL and result helpers

    private static List<String> readList(MvcResult result, String path) throws Exception {
        List<Object> raw = JsonPath.read(result.getResponse().getContentAsString(), path);
        List<String> values = new ArrayList<>();
        for (Object value : raw) {
            values.add(String.valueOf(value));
        }
        return values;
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void assertDecimal(String expected, BigDecimal actual, String message) {
        assertEquals(0, decimal(expected).compareTo(actual), message + ": expected " + expected + " but was " + actual);
    }

    private BigDecimal balance(String itemId) {
        return api.jdbc().queryForObject(
                "SELECT quantity_on_hand FROM inventory_balances WHERE inventory_item_id = ?::uuid",
                BigDecimal.class, itemId);
    }

    private int count(String sql, Object... args) {
        Integer value = api.jdbc().queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private int movementsOfItem(String itemId) {
        return count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid", itemId);
    }

    private int saleMovements(String orderId) {
        return count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid AND movement_type = 'SALE'",
                orderId);
    }

    private int countPayments(String orderId) {
        return count("SELECT count(*) FROM payments WHERE sales_order_id = ?::uuid", orderId);
    }

    /** The movement ledger must always add up to the cached balance. */
    private void assertLedgerMatchesBalance(String itemId) {
        BigDecimal sum = api.jdbc().queryForObject(
                "SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_movements WHERE inventory_item_id = ?::uuid",
                BigDecimal.class, itemId);
        assertEquals(0, sum.compareTo(balance(itemId)),
                "SUM(quantity_delta)=" + sum + " must equal quantity_on_hand=" + balance(itemId));
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String describe(MvcResult result) throws Exception {
        return status(result) + ": " + result.getResponse().getContentAsString();
    }

    private static boolean isPaymentSuccess(MvcResult result) {
        return status(result) == 200 || status(result) == 201;
    }

    private static boolean is2xx(MvcResult result) {
        return status(result) >= 200 && status(result) < 300;
    }

    private static boolean is4xx(MvcResult result) {
        return status(result) >= 400 && status(result) < 500;
    }

    private static void assertNoServerError(List<MvcResult> results) throws Exception {
        for (MvcResult result : results) {
            assertTrue(status(result) < 500, "no 5xx allowed, was " + describe(result));
        }
    }

    private static void assertAllStatus(List<MvcResult> results, int expected) throws Exception {
        for (MvcResult result : results) {
            assertEquals(expected, status(result), describe(result));
        }
    }

    private static void assertAllPaymentSuccess(List<MvcResult> results) throws Exception {
        for (MvcResult result : results) {
            assertTrue(isPaymentSuccess(result), "payment must succeed, was " + describe(result));
        }
    }

    /** Asserts every response is 201 or 409 (never 5xx) and returns the movement ids of the 201 bodies. */
    private static List<String> createdMovementIds(List<MvcResult> results) throws Exception {
        List<String> ids = new ArrayList<>();
        for (MvcResult result : results) {
            assertTrue(status(result) < 500, "no 5xx allowed, was " + describe(result));
            if (status(result) == 201) {
                ids.add(ApiTestSupport.read(result, "$.data.id"));
            } else {
                assertEquals(409, status(result), "every non-201 movement response must be 409: " + describe(result));
            }
        }
        return ids;
    }

    /** Runs all tasks released together by a start gate on a fixed pool of 8 threads; every wait is bounded. */
    private List<MvcResult> runInParallel(List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(Math.min(tasks.size(), THREADS));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (Callable<MvcResult> task : tasks) {
                Callable<MvcResult> gated = () -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                };
                futures.add(pool.submit(gated));
            }
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS), "all pool threads must reach the start gate");
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ A. same key, same payload

    @Test
    void sameMovementKeyAndPayloadInParallelAppliesExactlyOnce() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-a");
        String item = createIngredient(owner, "Biji Kopi", "g", "10", null);
        String key = "conc-a-" + UUID.randomUUID();
        String body = movementJson("RESTOCK", "100", "Belanja", key);

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> postMovement(owner, item, body));
        }
        List<MvcResult> results = runInParallel(tasks);

        assertNoServerError(results);
        List<String> ids = createdMovementIds(results);
        assertFalse(ids.isEmpty(), "at least one request must be answered 201");
        assertEquals(1, new HashSet<>(ids).size(), "every 201 body carries the same movement id: " + ids);
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE tenant_id = ?::uuid "
                + "AND idempotency_key = ?", owner.tenantId().toString(), key), "exactly one movement for the key");
        assertEquals(1, movementsOfItem(item), "no other movement exists for the ingredient");
        assertDecimal("100", balance(item), "the restock is applied exactly once");
        assertLedgerMatchesBalance(item);

        // Deterministic follow-up (TASK-070): a sequential replay of the same key and payload is answered 201
        // with the winner's movement id and changes nothing, whether or not the parallel requests overlapped.
        MvcResult replay = postMovement(owner, item, body);
        assertEquals(201, status(replay), "sequential replay must be 201: " + describe(replay));
        assertEquals(ids.get(0), ApiTestSupport.read(replay, "$.data.id"), "replay returns the winner's movement id");
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE tenant_id = ?::uuid "
                + "AND idempotency_key = ?", owner.tenantId().toString(), key), "still one movement row for the key");
        assertDecimal("100", balance(item), "the replay does not apply the restock again");
        assertLedgerMatchesBalance(item);
        System.out.println("[concurrency] A same key+payload: 201=" + ids.size() + " 409="
                + (results.size() - ids.size()) + " of " + results.size() + " parallel requests");
    }

    // ------------------------------------------------------------------ B. same key, different payload

    @Test
    void sameMovementKeyWithDifferentPayloadsInParallelAppliesExactlyOneOfThem() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-b");
        String item = createIngredient(owner, "Biji Kopi", "g", "10", null);
        String key = "conc-b-" + UUID.randomUUID();

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String body = movementJson("RESTOCK", String.valueOf(100 + i), "Belanja", key);
            tasks.add(() -> postMovement(owner, item, body));
        }
        List<MvcResult> results = runInParallel(tasks);

        assertNoServerError(results);
        List<String> ids = createdMovementIds(results);
        assertEquals(1, new HashSet<>(ids).size(), "exactly one distinct movement id is returned in 201 bodies: " + ids);
        assertEquals(1, ids.size(), "payloads differ, so a replay (second 201) is impossible: " + ids);
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE tenant_id = ?::uuid "
                + "AND idempotency_key = ?", owner.tenantId().toString(), key), "exactly one movement row for the key");
        assertEquals(ids.get(0), api.jdbc().queryForObject("SELECT id::text FROM inventory_movements "
                + "WHERE tenant_id = ?::uuid AND idempotency_key = ?", String.class, owner.tenantId().toString(), key),
                "the stored movement is the one that was answered 201");
        BigDecimal delta = api.jdbc().queryForObject("SELECT quantity_delta FROM inventory_movements "
                + "WHERE tenant_id = ?::uuid AND idempotency_key = ?", BigDecimal.class,
                owner.tenantId().toString(), key);
        assertTrue(delta.compareTo(decimal("100")) >= 0 && delta.compareTo(decimal("107")) <= 0,
                "stored delta must be one of the submitted quantities, was " + delta);
        assertEquals(0, delta.compareTo(balance(item)), "balance equals the single applied delta " + delta);
        assertLedgerMatchesBalance(item);

        // Deterministic follow-up (TASK-070): the stored movement identifies the winner's payload, so the key reuse
        // checks no longer depend on the parallel requests having overlapped.
        int winnerQuantity = delta.intValueExact();
        String winnerId = ids.get(0);
        MvcResult differing = postMovement(owner, item,
                movementJson("RESTOCK", String.valueOf(winnerQuantity + 50), "Belanja", key));
        assertEquals(409, status(differing), "same key with a different payload must be 409: " + describe(differing));
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE tenant_id = ?::uuid "
                + "AND idempotency_key = ?", owner.tenantId().toString(), key), "still one movement row for the key");
        assertEquals(0, delta.compareTo(balance(item)), "balance unchanged by the rejected payload");
        MvcResult replay = postMovement(owner, item,
                movementJson("RESTOCK", String.valueOf(winnerQuantity), "Belanja", key));
        assertEquals(201, status(replay), "the winner's exact payload is a replay: " + describe(replay));
        assertEquals(winnerId, ApiTestSupport.read(replay, "$.data.id"), "replay returns the winner's movement id");
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE tenant_id = ?::uuid "
                + "AND idempotency_key = ?", owner.tenantId().toString(), key), "replay adds no movement row");
        assertEquals(0, delta.compareTo(balance(item)), "balance unchanged by the replay");
        assertLedgerMatchesBalance(item);
        System.out.println("[concurrency] B same key+different payloads: 201=" + ids.size() + " 409="
                + (results.size() - ids.size()) + " of " + results.size() + " parallel requests");
    }

    // ------------------------------------------------------------------ C. distinct keys, no lost update

    @Test
    void parallelRestocksAndWastesWithDistinctKeysLoseNoUpdate() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-c");
        String item = createIngredient(owner, "Biji Kopi", "g", "10", "100");
        String prefix = "conc-c-" + UUID.randomUUID() + "-";

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String body = i % 3 == 2
                    ? movementJson("WASTE", "4", "Tumpah", prefix + i)
                    : movementJson("RESTOCK", "10", "Belanja", prefix + i);
            tasks.add(() -> postMovement(owner, item, body));
        }
        List<MvcResult> results = runInParallel(tasks);

        assertEquals(15, results.size());
        assertAllStatus(results, 201);
        assertEquals(15, new HashSet<>(createdMovementIds(results)).size(), "15 distinct movements were created");
        assertDecimal("180", balance(item), "100 + 10 x 10 - 5 x 4");
        assertEquals(15, count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid "
                + "AND idempotency_key LIKE ?", item, prefix + "%"), "exactly the 15 movements of this scenario");
        assertEquals(16, movementsOfItem(item), "initial restock + 15 movements");
        assertLedgerMatchesBalance(item);
    }

    // ------------------------------------------------------------------ D. different orders, shared ingredient

    @Test
    void parallelPaymentsOfDifferentOrdersDeductTheSharedIngredientExactlyOncePerOrder() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-d");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String item = createIngredient(owner, "Biji Kopi", "g", "10", "1000");
        setRecipe(owner, productId, item, "50");
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            orderIds.add(createOrder(owner, productId));
        }

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (String orderId : orderIds) {
            String paymentKey = "pay-" + UUID.randomUUID();
            tasks.add(() -> payCashCall(owner, orderId, paymentKey));
        }
        List<MvcResult> results = runInParallel(tasks);

        assertNoServerError(results);
        assertAllPaymentSuccess(results);
        assertEquals(THREADS, count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid "
                + "AND movement_type = 'SALE'", item), "one SALE movement per order");
        for (String orderId : orderIds) {
            assertEquals(1, saleMovements(orderId), "exactly one SALE movement for order " + orderId);
            assertDecimal("-50", api.jdbc().queryForObject("SELECT quantity_delta FROM inventory_movements "
                    + "WHERE order_id = ?::uuid AND movement_type = 'SALE'", BigDecimal.class, orderId), "sale delta");
            assertEquals("sale:" + orderId + ":" + item, api.jdbc().queryForObject("SELECT idempotency_key "
                    + "FROM inventory_movements WHERE order_id = ?::uuid AND movement_type = 'SALE'",
                    String.class, orderId));
            assertEquals(1, countPayments(orderId), "order is paid exactly once");
            assertEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
        }
        assertDecimal("600", balance(item), "1000 - 8 x 50");
        assertLedgerMatchesBalance(item);
    }

    // ------------------------------------------------------------------ E1. same order, same payment key

    @Test
    void parallelPaymentsOfOneOrderWithTheSameKeySettleAndDeductOnce() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-e1");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String item = createIngredient(owner, "Biji Kopi", "g", "10", "1000");
        setRecipe(owner, productId, item, "50");
        String orderId = createOrder(owner, productId);
        String paymentKey = "pay-" + UUID.randomUUID();

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> payCashCall(owner, orderId, paymentKey));
        }
        List<MvcResult> results = runInParallel(tasks);

        assertNoServerError(results);
        Set<String> receipts = new HashSet<>();
        int successes = 0;
        for (MvcResult result : results) {
            if (isPaymentSuccess(result)) {
                successes++;
                receipts.add(ApiTestSupport.read(result, "$.data.receiptNumber"));
            } else {
                assertEquals(409, status(result), "a non-success payment response must be 409: " + describe(result));
            }
        }
        assertTrue(successes >= 1, "at least one payment must succeed");
        assertEquals(1, receipts.size(), "every successful response refers to the same payment: " + receipts);
        assertEquals(1, countPayments(orderId), "exactly one payment row");
        assertEquals(1, saleMovements(orderId), "exactly one SALE movement for the order");
        assertDecimal("950", balance(item), "stock deducted once");
        assertEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
        assertLedgerMatchesBalance(item);
    }

    // ------------------------------------------------------------------ E2. same order, different payment keys

    @Test
    void parallelPaymentsOfOneOrderWithDifferentKeysAllowExactlyOneAndDeductOnce() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-e2");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String item = createIngredient(owner, "Biji Kopi", "g", "10", "1000");
        setRecipe(owner, productId, item, "50");
        String orderId = createOrder(owner, productId);

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String paymentKey = "pay-" + i + "-" + UUID.randomUUID();
            tasks.add(() -> payCashCall(owner, orderId, paymentKey));
        }
        List<MvcResult> results = runInParallel(tasks);

        assertNoServerError(results);
        int successes = 0;
        int conflicts = 0;
        for (MvcResult result : results) {
            if (isPaymentSuccess(result)) {
                successes++;
            } else if (status(result) == 409) {
                conflicts++;
            }
        }
        assertEquals(1, successes, "exactly one payment succeeds");
        assertEquals(THREADS - 1, conflicts, "all others are rejected with 409");
        assertEquals(1, countPayments(orderId), "exactly one payment row");
        assertEquals(1, saleMovements(orderId), "exactly one SALE movement for the order");
        assertDecimal("950", balance(item), "stock deducted once");
        assertEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
        assertLedgerMatchesBalance(item);
    }

    // ------------------------------------------------------------------ F. mixed load, no deadlock

    @Test
    void mixedPaymentsRestocksAndWastesFinishWithoutDeadlockAndKeepTheLedgerConsistent() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-f");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String item = createIngredient(owner, "Biji Kopi", "g", "10", "1000");
        setRecipe(owner, productId, item, "50");
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            orderIds.add(createOrder(owner, productId));
        }
        String prefix = "conc-f-" + UUID.randomUUID() + "-";

        // Interleaved so that payments and manual movements compete from the first wave on.
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String orderId = orderIds.get(i);
            String paymentKey = "pay-" + UUID.randomUUID();
            String restock = movementJson("RESTOCK", "100", "Belanja", prefix + "restock-" + i);
            String waste = movementJson("WASTE", "30", "Tumpah", prefix + "waste-" + i);
            tasks.add(() -> payCashCall(owner, orderId, paymentKey));
            tasks.add(() -> postMovement(owner, item, restock));
            tasks.add(() -> postMovement(owner, item, waste));
        }
        List<MvcResult> results = runInParallel(tasks);

        assertEquals(12, results.size());
        assertNoServerError(results);
        for (int i = 0; i < results.size(); i++) {
            if (i % 3 == 0) {
                assertTrue(isPaymentSuccess(results.get(i)), "payment must succeed, was " + describe(results.get(i)));
            } else {
                assertEquals(201, status(results.get(i)), describe(results.get(i)));
            }
        }
        for (String orderId : orderIds) {
            assertEquals(1, saleMovements(orderId), "exactly one SALE movement for order " + orderId);
            assertEquals(1, countPayments(orderId), "order is paid exactly once");
        }
        assertDecimal("1080", balance(item), "1000 + 4 x 100 - 4 x 30 - 4 x 50");
        assertEquals(13, movementsOfItem(item), "initial restock + 8 manual + 4 sale movements");
        assertLedgerMatchesBalance(item);
    }

    // ------------------------------------------------------------------ G. negative stock stays non-blocking

    @Test
    void negativeStockDoesNotBlockParallelPaymentsAndReportsMinusWarnings() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-g");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String item = createIngredient(owner, "Susu", "g", "10", "100");
        setRecipe(owner, productId, item, "50");
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            orderIds.add(createOrder(owner, productId));
        }

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (String orderId : orderIds) {
            String paymentKey = "pay-" + UUID.randomUUID();
            tasks.add(() -> payCashCall(owner, orderId, paymentKey));
        }
        List<MvcResult> results = runInParallel(tasks);

        assertNoServerError(results);
        assertAllPaymentSuccess(results);
        assertDecimal("-200", balance(item), "100 - 6 x 50");
        assertEquals(6, count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid "
                + "AND movement_type = 'SALE'", item), "6 SALE movements");
        for (String orderId : orderIds) {
            assertEquals(1, saleMovements(orderId), "exactly one SALE movement for order " + orderId);
            assertEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
        }

        // The payments are serialised, so the running balances are 50, 0, -50, -100, -150, -200 in some order:
        // 50 is above the reorder level (no warning), 0 is "habis", the four negatives are "minus".
        List<String> actualWarnings = new ArrayList<>();
        int responsesWithMinus = 0;
        for (MvcResult result : results) {
            List<String> warnings = readList(result, "$.data.stockWarnings");
            actualWarnings.addAll(warnings);
            if (warnings.stream().anyMatch(warning -> warning.contains("(minus)"))) {
                responsesWithMinus++;
            }
        }
        assertEquals(4, responsesWithMinus, "the four payments that end below zero carry the minus warning");
        List<String> expectedWarnings = new ArrayList<>(List.of("Susu: sisa 0 g (habis)",
                "Susu: sisa -50 g (minus)", "Susu: sisa -100 g (minus)", "Susu: sisa -150 g (minus)",
                "Susu: sisa -200 g (minus)"));
        Collections.sort(expectedWarnings);
        Collections.sort(actualWarnings);
        assertEquals(expectedWarnings, actualWarnings);
        assertLedgerMatchesBalance(item);
    }

    // ------------------------------------------------------------------ H. payment racing with cancel

    @Test
    void paymentRacingWithCancelLeavesExactlyOneConsistentOutcomePerOrder() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-h");
        MerchantFixture cashier = api.onboardCashier(owner, "conc-h-cashier");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String item = createIngredient(owner, "Biji Kopi", "g", "10", "10000");
        setRecipe(owner, productId, item, "50");
        int paymentWins = 0;
        int cancelWins = 0;

        for (int round = 0; round < ROUNDS; round++) {
            String orderId = createOrder(cashier, productId);
            String paymentKey = "pay-" + UUID.randomUUID();
            List<Callable<MvcResult>> tasks = new ArrayList<>();
            tasks.add(() -> payCashCall(cashier, orderId, paymentKey));
            tasks.add(() -> cancelCall(cashier, orderId));
            List<MvcResult> results = runInParallel(tasks);
            MvcResult payment = results.get(0);
            MvcResult cancel = results.get(1);
            String context = "round " + round + " order " + orderId + " payment=" + describe(payment)
                    + " cancel=" + describe(cancel);

            assertNoServerError(results);
            boolean paid = is2xx(payment);
            assertTrue(paid ^ is2xx(cancel), "exactly one of payment and cancel is 2xx: " + context);
            assertTrue(is4xx(paid ? cancel : payment), "the loser must be a clean 4xx: " + context);

            String paymentStatus = api.jdbc().queryForObject(
                    "SELECT payment_status FROM sales_orders WHERE id = ?::uuid", String.class, orderId);
            String orderStatus = api.jdbc().queryForObject(
                    "SELECT status FROM sales_orders WHERE id = ?::uuid", String.class, orderId);
            if (paid) {
                paymentWins++;
                assertEquals("PAID", paymentStatus, "payment won: " + context);
                assertFalse("CANCELLED".equals(orderStatus), "payment won, order must not be cancelled: " + context);
                assertEquals(1, countPayments(orderId), "payment won, exactly one payment row: " + context);
                assertEquals(1, saleMovements(orderId), "payment won, exactly one SALE movement: " + context);
                assertDecimal("-50", api.jdbc().queryForObject("SELECT quantity_delta FROM inventory_movements "
                        + "WHERE order_id = ?::uuid AND movement_type = 'SALE'", BigDecimal.class, orderId),
                        "sale delta of " + orderId);
            } else {
                cancelWins++;
                assertEquals("UNPAID", paymentStatus, "cancel won: " + context);
                assertEquals("CANCELLED", orderStatus, "cancel won: " + context);
                assertEquals(0, countPayments(orderId), "cancel won, no payment row: " + context);
                assertEquals(0, saleMovements(orderId), "cancel won, no SALE movement: " + context);
            }
        }

        System.out.println("[concurrency] H pay vs cancel: payment won=" + paymentWins + " cancel won=" + cancelWins
                + " of " + ROUNDS + " rounds");
        assertEquals(ROUNDS, paymentWins + cancelWins);
        assertDecimal(String.valueOf(10000 - 50 * paymentWins), balance(item), "10000 - 50 x rounds won by the payment");
        assertEquals(paymentWins, count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid "
                + "AND movement_type = 'SALE'", item), "one SALE movement per paid order");
        assertLedgerMatchesBalance(item);
    }

    // ------------------------------------------------------------------ I. payment racing with discount

    @Test
    void paymentRacingWithDiscountKeepsTheChargedTotalAndTheDiscountRowConsistent() throws Exception {
        MerchantFixture owner = api.onboardOwner("conc-i");
        MerchantFixture cashier = api.onboardCashier(owner, "conc-i-cashier");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String item = createIngredient(owner, "Biji Kopi", "g", "10", "10000");
        setRecipe(owner, productId, item, "50");
        int discountApplied = 0;
        int discountRejected = 0;

        for (int round = 0; round < ROUNDS; round++) {
            String orderId = createOrder(cashier, productId);
            BigDecimal undiscountedTotal = api.jdbc().queryForObject(
                    "SELECT total FROM sales_orders WHERE id = ?::uuid", BigDecimal.class, orderId);
            String paymentKey = "pay-" + UUID.randomUUID();
            List<Callable<MvcResult>> tasks = new ArrayList<>();
            tasks.add(() -> payCashCall(cashier, orderId, paymentKey));
            tasks.add(() -> discountCall(owner, orderId));
            List<MvcResult> results = runInParallel(tasks);
            MvcResult payment = results.get(0);
            MvcResult discount = results.get(1);
            String context = "round " + round + " order " + orderId + " payment=" + describe(payment)
                    + " discount=" + describe(discount);

            assertNoServerError(results);
            assertTrue(is2xx(payment), "the payment must always succeed: " + context);
            assertTrue(is2xx(discount) || is4xx(discount), "the discount is 2xx or a clean 4xx: " + context);

            Map<String, Object> order = api.jdbc().queryForMap("SELECT subtotal, discount_amount, tax_amount, "
                    + "service_charge_amount, total, payment_status FROM sales_orders WHERE id = ?::uuid", orderId);
            BigDecimal subtotal = (BigDecimal) order.get("subtotal");
            BigDecimal discountAmount = (BigDecimal) order.get("discount_amount");
            BigDecimal tax = (BigDecimal) order.get("tax_amount");
            BigDecimal serviceCharge = (BigDecimal) order.get("service_charge_amount");
            BigDecimal total = (BigDecimal) order.get("total");
            assertEquals("PAID", order.get("payment_status"), context);
            assertEquals(1, countPayments(orderId), "exactly one payment row: " + context);
            assertEquals(1, saleMovements(orderId), "exactly one SALE movement: " + context);
            Map<String, Object> paymentRow = api.jdbc().queryForMap("SELECT amount_paid, change_amount "
                    + "FROM payments WHERE sales_order_id = ?::uuid", orderId);
            BigDecimal tendered = (BigDecimal) paymentRow.get("amount_paid");
            BigDecimal change = (BigDecimal) paymentRow.get("change_amount");
            assertEquals(0, change.compareTo(tendered.subtract(total)),
                    "change " + change + " must equal tendered " + tendered + " - persisted total " + total + ": "
                            + context);
            assertEquals(0, total.compareTo(subtotal.subtract(discountAmount).add(tax).add(serviceCharge)),
                    "persisted total must add up from its persisted parts: " + context);
            int discountRows = count("SELECT count(*) FROM discounts WHERE sales_order_id = ?::uuid", orderId);
            if (is2xx(discount)) {
                discountApplied++;
                assertEquals(1, discountRows, "discount applied, exactly one discount row: " + context);
                assertDecimal("1000", discountAmount, "persisted discount amount of " + orderId);
                assertDecimal("1000", api.jdbc().queryForObject("SELECT amount FROM discounts "
                        + "WHERE sales_order_id = ?::uuid", BigDecimal.class, orderId), "discount row amount");
                assertTrue(total.compareTo(undiscountedTotal) < 0,
                        "discounted total " + total + " must be below " + undiscountedTotal + ": " + context);
            } else {
                discountRejected++;
                assertEquals(0, discountRows, "discount rejected, no discount row: " + context);
                assertDecimal("0", discountAmount, "no persisted discount amount of " + orderId);
                assertEquals(0, total.compareTo(undiscountedTotal),
                        "discount rejected, total must stay " + undiscountedTotal + " but was " + total + ": "
                                + context);
            }
        }

        System.out.println("[concurrency] I pay vs discount: discount applied before payment=" + discountApplied
                + " rejected=" + discountRejected + " of " + ROUNDS + " rounds");
        assertEquals(ROUNDS, discountApplied + discountRejected);
        assertDecimal(String.valueOf(10000 - 50 * ROUNDS), balance(item), "every order was paid, 50 g each");
        assertEquals(ROUNDS, count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid "
                + "AND movement_type = 'SALE'", item), "one SALE movement per paid order");
        assertLedgerMatchesBalance(item);
    }
}
