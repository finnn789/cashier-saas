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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Cash shifts and owner refunds (TASK-077) against real PostgreSQL, the real Flyway schema (V7) and the security chain. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FnbShiftRefundIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final String PRICE = "25000";
    private static final String SHIFT_REQUIRED = "Buka shift terlebih dahulu sebelum menerima pembayaran.";

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

    // ------------------------------------------------------------------ paths, JSON and helpers

    private static String cashierShifts(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/shifts";
    }

    private static String merchantShifts(MerchantFixture merchant) {
        return "/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/shifts";
    }

    private static String orders(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/orders";
    }

    private static String refundUrl(MerchantFixture merchant, String orderId) {
        return "/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/orders/" + orderId + "/refund";
    }

    private static String paymentJson(String method, String tendered, String key, String labelId) {
        return "{\"method\":\"" + method + "\",\"amountTendered\":" + tendered + ",\"idempotencyKey\":\"" + key + "\""
                + (labelId == null ? "" : ",\"labelId\":\"" + labelId + "\"") + "}";
    }

    private static String closeJson(String counted, String note) {
        return "{\"countedCash\":" + counted + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    private static String refundKey() {
        return "refund-" + UUID.randomUUID();
    }

    private static BigDecimal money(MvcResult result, String path) throws Exception {
        return new BigDecimal(ApiTestSupport.read(result, path));
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, status(result), result.getResponse().getContentAsString());
    }

    private static void assertSuccess(MvcResult result) throws Exception {
        assertTrue(status(result) == 200 || status(result) == 201, result.getResponse().getContentAsString());
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + ": expected " + expected + " but was " + actual);
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual, String message) {
        assertEquals(0, expected.compareTo(actual), message + ": expected " + expected + " but was " + actual);
    }

    private int count(String sql, Object... args) {
        Integer value = api.jdbc().queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private String createProductWithRecipe(MerchantFixture owner, String ingredientQuantity, String perUnit, String[] itemOut)
            throws Exception {
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String itemId = ApiTestSupport.read(api.send(post("/api/v1/merchant/outlets/" + owner.outletId()
                + "/fnb/inventory/items").header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Biji Kopi\",\"unit\":\"g\",\"reorderLevel\":10,\"initialQuantity\":"
                        + ingredientQuantity + "}"), 201), "$.data.id");
        api.send(put("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/products/" + productId + "/recipe")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":\"" + itemId + "\",\"quantityPerUnit\":" + perUnit + "}]}"), 200);
        itemOut[0] = itemId;
        return productId;
    }

    private String simpleProduct(MerchantFixture owner) throws Exception {
        return api.createProduct(owner, api.createCategory(owner, "Minuman"), "Es Teh", PRICE);
    }

    private String createOrder(MerchantFixture actor, String productId, int quantity) throws Exception {
        MvcResult created = api.send(post(orders(actor)).header(AUTH, actor.bearer())
                .header("Idempotency-Key", "pos-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceType\":\"TAKEAWAY\",\"customerLabel\":\"Budi\",\"items\":[{\"productId\":\""
                        + productId + "\",\"quantity\":" + quantity + ",\"optionIds\":[]}]}"), 201);
        return ApiTestSupport.read(created, "$.data.orderId");
    }

    private MvcResult getOrder(MerchantFixture actor, String orderId) throws Exception {
        return api.send(get(orders(actor) + "/" + orderId).header(AUTH, actor.bearer()), 200);
    }

    private BigDecimal total(MerchantFixture actor, String orderId) throws Exception {
        return money(getOrder(actor, orderId), "$.data.total");
    }

    private MvcResult payCall(MerchantFixture actor, String orderId, String method, BigDecimal tendered, String key)
            throws Exception {
        return api.call(post(orders(actor) + "/" + orderId + "/payments").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson(method, tendered.toPlainString(), key,
                        "MANUAL_NON_CASH".equals(method) ? api.firstActiveLabelId(actor) : null)));
    }

    /** Pays the order in cash with the exact total plus {@code extra} as tendered amount. */
    private MvcResult payCash(MerchantFixture actor, String orderId, String extra) throws Exception {
        BigDecimal tendered = total(actor, orderId).add(new BigDecimal(extra));
        MvcResult paid = payCall(actor, orderId, "CASH", tendered, "pay-" + UUID.randomUUID());
        assertSuccess(paid);
        return paid;
    }

    private MvcResult payNonCash(MerchantFixture actor, String orderId) throws Exception {
        MvcResult paid = payCall(actor, orderId, "MANUAL_NON_CASH", total(actor, orderId), "pay-" + UUID.randomUUID());
        assertSuccess(paid);
        return paid;
    }

    private MvcResult currentShift(MerchantFixture actor) throws Exception {
        return api.call(get(cashierShifts(actor) + "/current").header(AUTH, actor.bearer()));
    }

    private MvcResult closeCurrent(MerchantFixture actor, String counted, String note) throws Exception {
        return api.call(post(cashierShifts(actor) + "/current/close").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(closeJson(counted, note)));
    }

    /** Refund request; actor's token against the outlet path of {@code pathMerchant}; null key = no header. */
    private MockHttpServletRequestBuilder refundRequest(MerchantFixture actor, MerchantFixture pathMerchant, String orderId,
                                                        String key, String reasonJson) {
        MockHttpServletRequestBuilder request = post(refundUrl(pathMerchant, orderId)).header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(reasonJson);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private MvcResult refundCall(MerchantFixture owner, String orderId, String key, String reason) throws Exception {
        return api.call(refundRequest(owner, owner, orderId, key, "{\"reason\":\"" + reason + "\"}"));
    }

    private int auditRows(MerchantFixture merchant, String action, String entityId) {
        return count("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = ? AND entity_id = ?::uuid",
                merchant.tenantId(), action, entityId);
    }

    private BigDecimal balance(String itemId) {
        return api.jdbc().queryForObject(
                "SELECT quantity_on_hand FROM inventory_balances WHERE inventory_item_id = ?::uuid", BigDecimal.class, itemId);
    }

    private MvcResult salesReport(MerchantFixture merchant) throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        return api.send(get("/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/reports/sales")
                .param("startDate", today.minusDays(1).toString()).param("endDate", today.plusDays(1).toString())
                .header(AUTH, merchant.bearer()), 200);
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

    // ------------------------------------------------------------------ schema

    @Test
    void migrationV7IsAppliedWithTheShiftAndRefundObjects() {
        assertEquals(1, count("SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success"));
        assertEquals(1, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'uk_cash_shifts_open_per_member'"));
        assertEquals(2, count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name IN ('cash_shifts', 'payment_refunds')"));
        assertEquals(1, count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'payments' "
                + "AND column_name = 'cash_shift_id'"));
        assertEquals(1, count("SELECT count(*) FROM pg_constraint WHERE conname = 'ck_inventory_movements_type' "
                + "AND pg_get_constraintdef(oid) LIKE '%SALE_REVERSAL%'"));
    }

    // ------------------------------------------------------------------ shifts

    @Test
    void shiftLifecycleCountsCashNonCashChangeAndStoresTheCloseDifference() throws Exception {
        MerchantFixture owner = api.onboardOwner("shift-math");
        String productId = simpleProduct(owner);

        MvcResult opened = api.openShift(owner, "100000.00");
        String shiftId = ApiTestSupport.read(opened, "$.data.id");
        assertEquals("OPEN", ApiTestSupport.read(opened, "$.data.status"));
        assertEquals("Owner shift-math", ApiTestSupport.read(opened, "$.data.fullName"));
        assertMoney("100000", money(opened, "$.data.openingCash"), "opening cash");
        assertStatus(409, api.call(post(cashierShifts(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"openingCash\":5}")));
        assertEquals(1, count("SELECT count(*) FROM cash_shifts WHERE tenant_id = ? AND status = 'OPEN'", owner.tenantId()));

        String cashOrder = createOrder(owner, productId, 2);
        String nonCashOrder = createOrder(owner, productId, 1);
        createOrder(owner, productId, 1); // stays open and unpaid
        BigDecimal cashTotal = total(owner, cashOrder);
        BigDecimal nonCashTotal = total(owner, nonCashOrder);
        MvcResult cashPaid = payCash(owner, cashOrder, "5000");
        assertMoney("5000", money(cashPaid, "$.data.changeAmount"), "change");
        payNonCash(owner, nonCashOrder);

        MvcResult current = currentShift(owner);
        assertStatus(200, current);
        assertEquals(shiftId, ApiTestSupport.read(current, "$.data.id"));
        assertEquals("2", ApiTestSupport.read(current, "$.data.paymentCount"));
        assertMoney(cashTotal, money(current, "$.data.cashIn"), "cash in is paid minus change");
        assertMoney("0", money(current, "$.data.cashRefunds"), "no refunds yet");
        BigDecimal expected = new BigDecimal("100000").add(cashTotal);
        assertMoney(expected, money(current, "$.data.expectedCash"), "expected = opening + cash in");
        assertEquals("CASH", ApiTestSupport.read(current, "$.data.totalsByMethod[0].method"));
        assertEquals("1", ApiTestSupport.read(current, "$.data.totalsByMethod[0].count"));
        assertMoney(cashTotal, money(current, "$.data.totalsByMethod[0].amount"), "cash total");
        assertEquals("MANUAL_NON_CASH", ApiTestSupport.read(current, "$.data.totalsByMethod[1].method"));
        assertMoney(nonCashTotal, money(current, "$.data.totalsByMethod[1].amount"), "non-cash total");
        assertEquals("1", ApiTestSupport.read(current, "$.data.openOrderCount"));

        BigDecimal counted = expected.subtract(new BigDecimal("1000"));
        MvcResult closed = closeCurrent(owner, counted.toPlainString(), "kurang seribu");
        assertStatus(200, closed);
        assertEquals("CLOSED", ApiTestSupport.read(closed, "$.data.status"));
        assertMoney(expected, money(closed, "$.data.expectedCash"), "closed expected");
        assertMoney(counted, money(closed, "$.data.countedCash"), "closed counted");
        assertMoney("-1000", money(closed, "$.data.cashDifference"), "difference = counted - expected");
        assertEquals("kurang seribu", ApiTestSupport.read(closed, "$.data.closingNote"));
        assertMoney(expected, api.jdbc().queryForObject("SELECT expected_cash FROM cash_shifts WHERE id = ?::uuid",
                BigDecimal.class, shiftId), "stored expected");
        assertMoney("-1000", api.jdbc().queryForObject("SELECT cash_difference FROM cash_shifts WHERE id = ?::uuid",
                BigDecimal.class, shiftId), "stored difference");

        assertStatus(404, currentShift(owner));
        assertStatus(404, closeCurrent(owner, "0", null));
        assertEquals(1, auditRows(owner, "SHIFT_OPENED", shiftId));
        assertEquals(1, auditRows(owner, "SHIFT_CLOSED", shiftId));
        String closeDetails = api.jdbc().queryForObject("SELECT details FROM audit_logs WHERE tenant_id = ? "
                + "AND action = 'SHIFT_CLOSED' AND entity_id = ?::uuid", String.class, owner.tenantId(), shiftId);
        assertTrue(closeDetails.startsWith("expected=") && closeDetails.endsWith("difference=-1000.00"), closeDetails);

        // A closed shift frees the slot: the member can open the next one.
        api.openShift(owner, "0.00");
        assertStatus(200, currentShift(owner));
    }

    @Test
    void paymentNeedsTheCallersOwnOpenShiftAndAReplayAfterCloseStillReturnsThePayment() throws Exception {
        MerchantFixture owner = api.onboardOwner("pay-shift");
        MerchantFixture cashier = api.onboardCashier(owner, "pay-shift-cashier");
        String productId = simpleProduct(owner);
        String orderId = createOrder(owner, productId, 1);
        BigDecimal total = total(owner, orderId);

        MvcResult noShift = payCall(owner, orderId, "CASH", total, "pay-" + UUID.randomUUID());
        assertStatus(409, noShift);
        assertTrue(noShift.getResponse().getContentAsString().contains(SHIFT_REQUIRED));
        assertEquals(0, count("SELECT count(*) FROM payments WHERE sales_order_id = ?::uuid", orderId));
        assertEquals("UNPAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));

        // Shifts are per member: the owner's open shift does not let the cashier take payments.
        api.openShift(owner, "0.00");
        assertStatus(409, payCall(cashier, orderId, "CASH", total, "pay-" + UUID.randomUUID()));
        assertStatus(409, payCall(cashier, orderId, "MANUAL_NON_CASH", total, "pay-" + UUID.randomUUID()));
        assertEquals(0, count("SELECT count(*) FROM payments WHERE sales_order_id = ?::uuid", orderId));

        String cashierShift = ApiTestSupport.read(api.openShift(cashier, "0.00"), "$.data.id");
        String key = "pay-" + UUID.randomUUID();
        MvcResult paid = payCall(cashier, orderId, "CASH", total, key);
        assertSuccess(paid);
        assertEquals(cashierShift, api.jdbc().queryForObject(
                "SELECT cash_shift_id::text FROM payments WHERE sales_order_id = ?::uuid", String.class, orderId),
                "the payment belongs to the paying member's shift");

        assertStatus(200, closeCurrent(cashier, "0", null));
        MvcResult replay = payCall(cashier, orderId, "CASH", total, key);
        assertSuccess(replay);
        assertEquals(ApiTestSupport.read(paid, "$.data.receiptNumber"), ApiTestSupport.read(replay, "$.data.receiptNumber"),
                "replay returns the stored payment although the shift is closed");
        assertEquals(1, count("SELECT count(*) FROM payments WHERE sales_order_id = ?::uuid", orderId));

        String another = createOrder(owner, productId, 1);
        assertStatus(409, payCall(cashier, another, "CASH", total(owner, another), "pay-" + UUID.randomUUID()));
        assertEquals("UNPAID", ApiTestSupport.read(getOrder(owner, another), "$.data.paymentStatus"));
    }

    @Test
    void nonCashPaymentNeedsAnOpenShiftAndAttachesToItOnceOpened() throws Exception {
        MerchantFixture owner = api.onboardOwner("nc-shift");
        String orderId = createOrder(owner, simpleProduct(owner), 1);
        BigDecimal total = total(owner, orderId);

        MvcResult noShift = payCall(owner, orderId, "MANUAL_NON_CASH", total, "nc-" + UUID.randomUUID());
        assertStatus(409, noShift);
        assertTrue(noShift.getResponse().getContentAsString().contains(SHIFT_REQUIRED));
        assertEquals("UNPAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
        assertEquals(0, count("SELECT count(*) FROM payments WHERE sales_order_id = ?::uuid", orderId));

        String shiftId = ApiTestSupport.read(api.openShift(owner, "0.00"), "$.data.id");
        assertSuccess(payCall(owner, orderId, "MANUAL_NON_CASH", total, "nc-" + UUID.randomUUID()));
        assertEquals(shiftId, api.jdbc().queryForObject(
                "SELECT cash_shift_id::text FROM payments WHERE sales_order_id = ?::uuid", String.class, orderId));
        assertEquals("1", ApiTestSupport.read(currentShift(owner), "$.data.paymentCount"));
    }

    @Test
    void ownerListsShowsAndForceClosesACashiersShiftWhileOtherTenantsSeeNothing() throws Exception {
        MerchantFixture owner = api.onboardOwner("shift-owner");
        MerchantFixture cashier = api.onboardCashier(owner, "shift-owner-cashier");
        MerchantFixture other = api.onboardOwner("shift-other");
        String shiftId = ApiTestSupport.read(api.openShift(cashier, "50000.00"), "$.data.id");
        String orderId = createOrder(cashier, simpleProduct(owner), 1);
        BigDecimal total = total(cashier, orderId);
        payCash(cashier, orderId, "0");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));

        MvcResult list = api.send(get(merchantShifts(owner)).param("from", today.minusDays(1).toString())
                .param("to", today.plusDays(1).toString()).header(AUTH, owner.bearer()), 200);
        assertEquals("1", ApiTestSupport.read(list, "$.data.shifts.length()"));
        assertEquals(shiftId, ApiTestSupport.read(list, "$.data.shifts[0].id"));
        assertEquals("Cashier shift-owner-cashier", ApiTestSupport.read(list, "$.data.shifts[0].fullName"));
        assertEquals("false", ApiTestSupport.read(list, "$.data.hasNext"));
        MvcResult detail = api.send(get(merchantShifts(owner) + "/" + shiftId).header(AUTH, owner.bearer()), 200);
        assertEquals("OPEN", ApiTestSupport.read(detail, "$.data.status"));
        assertMoney(new BigDecimal("50000").add(total), money(detail, "$.data.expectedCash"), "live expected cash");
        assertEquals("1", ApiTestSupport.read(detail, "$.data.paymentCount"));

        // Other tenant: own outlet lists nothing, the shift id is unknown, the foreign outlet path is hidden.
        MvcResult otherList = api.send(get(merchantShifts(other)).param("from", today.minusDays(1).toString())
                .param("to", today.plusDays(1).toString()).header(AUTH, other.bearer()), 200);
        assertEquals("0", ApiTestSupport.read(otherList, "$.data.shifts.length()"));
        assertStatus(404, api.call(get(merchantShifts(other) + "/" + shiftId).header(AUTH, other.bearer())));
        assertStatus(404, api.call(get(merchantShifts(owner) + "/" + shiftId).header(AUTH, other.bearer())));
        assertStatus(404, api.call(post(merchantShifts(owner) + "/" + shiftId + "/close").header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(closeJson("0", null))));
        assertStatus(404, api.call(post(merchantShifts(other) + "/" + shiftId + "/close").header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(closeJson("0", null))));
        assertEquals(1, count("SELECT count(*) FROM cash_shifts WHERE id = ?::uuid AND status = 'OPEN'", shiftId));

        BigDecimal counted = new BigDecimal("50000").add(total).add(new BigDecimal("2500"));
        MvcResult forced = api.send(post(merchantShifts(owner) + "/" + shiftId + "/close").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(closeJson(counted.toPlainString(), "dihitung owner")), 200);
        assertEquals("CLOSED", ApiTestSupport.read(forced, "$.data.status"));
        assertMoney("2500", money(forced, "$.data.cashDifference"), "surplus");
        assertEquals("Owner shift-owner", api.jdbc().queryForObject("SELECT u.full_name FROM cash_shifts c "
                + "JOIN staff_users u ON u.id = c.closed_by_staff_user_id WHERE c.id = ?::uuid", String.class, shiftId),
                "closed by the owner");
        assertEquals("Cashier shift-owner-cashier", api.jdbc().queryForObject("SELECT u.full_name FROM cash_shifts c "
                + "JOIN staff_users u ON u.id = c.opened_by_staff_user_id WHERE c.id = ?::uuid", String.class, shiftId));
        assertEquals(1, auditRows(owner, "SHIFT_CLOSED", shiftId));
        assertStatus(409, api.call(post(merchantShifts(owner) + "/" + shiftId + "/close").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(closeJson("1", null))));
        assertStatus(404, currentShift(cashier));
        assertEquals(0, count("SELECT count(*) FROM cash_shifts WHERE tenant_id = ? AND status = 'OPEN'", owner.tenantId()));
    }

    @Test
    void cashierCannotUseTheMerchantShiftRoutesAndAnonymousCannotOpenAShift() throws Exception {
        MerchantFixture owner = api.onboardOwner("shift-roles");
        MerchantFixture cashier = api.onboardCashier(owner, "shift-roles-cashier");
        String shiftId = ApiTestSupport.read(api.openShift(cashier, "0.00"), "$.data.id");

        assertStatus(403, api.call(get(merchantShifts(owner)).header(AUTH, cashier.bearer())));
        assertStatus(403, api.call(get(merchantShifts(owner) + "/" + shiftId).header(AUTH, cashier.bearer())));
        assertStatus(403, api.call(post(merchantShifts(owner) + "/" + shiftId + "/close").header(AUTH, cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(closeJson("0", null))));
        assertStatus(401, api.call(post(cashierShifts(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"openingCash\":0}")));
        assertStatus(401, api.call(get(cashierShifts(owner) + "/current")));
        assertStatus(400, api.call(post(cashierShifts(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"openingCash\":-1}")));
        assertStatus(400, api.call(post(cashierShifts(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{}")));
        assertEquals(1, count("SELECT count(*) FROM cash_shifts WHERE tenant_id = ?", owner.tenantId()));
        assertEquals("OPEN", ApiTestSupport.read(currentShift(cashier), "$.data.status"));
    }

    @Test
    void concurrentOpensOfTheSameMemberCreateExactlyOneShift() throws Exception {
        MerchantFixture owner = api.onboardOwner("shift-race");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> api.call(post(cashierShifts(owner)).header(AUTH, owner.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"openingCash\":1000}")));
        }

        List<MvcResult> results = runInParallel(tasks);

        int created = 0;
        int conflicts = 0;
        for (MvcResult result : results) {
            if (status(result) == 200 || status(result) == 201) {
                created++;
            } else if (status(result) == 409) {
                conflicts++;
            }
        }
        assertEquals(1, created, "exactly one open succeeds");
        assertEquals(3, conflicts, "all others are conflicts, never a server error");
        assertEquals(1, count("SELECT count(*) FROM cash_shifts WHERE tenant_id = ? AND status = 'OPEN'", owner.tenantId()));
    }

    // ------------------------------------------------------------------ refunds

    @Test
    void cashRefundRestoresStockDropsTheSaleFromTheReportAuditsAndReducesExpectedCash() throws Exception {
        MerchantFixture owner = api.onboardOwner("refund-ok");
        String[] item = new String[1];
        String productId = createProductWithRecipe(owner, "1000", "10", item);
        api.openShift(owner, "20000.00");
        String orderId = createOrder(owner, productId, 3);
        BigDecimal total = total(owner, orderId);
        payCash(owner, orderId, "1000");
        assertMoney("970", balance(item[0]), "stock after the sale");
        assertEquals("1", ApiTestSupport.read(salesReport(owner), "$.data.summary.transactionCount"));
        assertMoney(new BigDecimal("20000").add(total), money(currentShift(owner), "$.data.expectedCash"), "before refund");

        String key = refundKey();
        MvcResult refunded = api.call(refundRequest(owner, owner, orderId, key, "{\"reason\":\"Pelanggan komplain\"}"));
        assertStatus(200, refunded);
        assertEquals("REFUNDED", ApiTestSupport.read(refunded, "$.data.paymentStatus"));
        assertEquals(orderId, ApiTestSupport.read(refunded, "$.data.orderId"));
        assertEquals("CASH", ApiTestSupport.read(refunded, "$.data.method"));
        assertEquals("Pelanggan komplain", ApiTestSupport.read(refunded, "$.data.reason"));
        assertEquals("Owner refund-ok", ApiTestSupport.read(refunded, "$.data.refundedBy"));
        assertMoney(total, money(refunded, "$.data.amount"), "refund amount is paid minus change");
        assertEquals("0", ApiTestSupport.read(refunded, "$.data.stockWarnings.length()"));

        MvcResult order = getOrder(owner, orderId);
        assertEquals("COMPLETED", ApiTestSupport.read(order, "$.data.status"));
        assertEquals("REFUNDED", ApiTestSupport.read(order, "$.data.paymentStatus"));
        assertEquals("0", ApiTestSupport.read(salesReport(owner), "$.data.summary.transactionCount"),
                "refunded orders drop out of the sales report");

        assertMoney("1000", balance(item[0]), "stock is back to the pre-sale balance");
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid "
                + "AND movement_type = 'SALE_REVERSAL'", orderId));
        assertMoney("30", api.jdbc().queryForObject("SELECT quantity_delta FROM inventory_movements WHERE order_id = ?::uuid "
                + "AND movement_type = 'SALE_REVERSAL'", BigDecimal.class, orderId), "reversal delta");
        assertEquals(1, count("SELECT count(*) FROM inventory_movements r JOIN inventory_movements s "
                + "ON s.id = r.reversal_of_movement_id WHERE r.order_id = ?::uuid AND r.movement_type = 'SALE_REVERSAL' "
                + "AND s.movement_type = 'SALE'", orderId), "reversal points at the sale movement");
        assertEquals("sale-reversal:" + orderId + ":" + item[0], api.jdbc().queryForObject(
                "SELECT idempotency_key FROM inventory_movements WHERE order_id = ?::uuid AND movement_type = 'SALE_REVERSAL'",
                String.class, orderId));
        assertMoney(balance(item[0]), api.jdbc().queryForObject("SELECT sum(quantity_delta) FROM inventory_movements "
                + "WHERE inventory_item_id = ?::uuid", BigDecimal.class, item[0]), "ledger matches balance");

        assertEquals(1, auditRows(owner, "FNB_ORDER_REFUNDED", orderId));
        assertEquals("Pelanggan komplain", api.jdbc().queryForObject("SELECT reason FROM audit_logs WHERE tenant_id = ? "
                + "AND action = 'FNB_ORDER_REFUNDED' AND entity_id = ?::uuid", String.class, owner.tenantId(), orderId));
        String details = api.jdbc().queryForObject("SELECT details FROM audit_logs WHERE tenant_id = ? "
                + "AND action = 'FNB_ORDER_REFUNDED' AND entity_id = ?::uuid", String.class, owner.tenantId(), orderId);
        assertTrue(details.startsWith("amount=") && details.endsWith(" method=CASH"), details);
        assertMoney(total, new BigDecimal(details.substring("amount=".length(), details.indexOf(' '))), "audited amount");

        MvcResult shift = currentShift(owner);
        assertMoney(total, money(shift, "$.data.cashRefunds"), "cash refunds of the shift");
        assertMoney(total, money(shift, "$.data.cashIn"), "cash in is unchanged");
        assertMoney("20000", money(shift, "$.data.expectedCash"), "expected cash = opening + cash in - refunds");

        // Replay: same key and body returns the stored refund with no second effect.
        MvcResult replay = api.call(refundRequest(owner, owner, orderId, key, "{\"reason\":\"Pelanggan komplain\"}"));
        assertStatus(200, replay);
        assertEquals(ApiTestSupport.read(refunded, "$.data.refundedAt"), ApiTestSupport.read(replay, "$.data.refundedAt"));
        assertMoney(total, money(replay, "$.data.amount"), "replayed amount");
        assertStatus(409, api.call(refundRequest(owner, owner, orderId, key, "{\"reason\":\"Alasan lain\"}")));
        // A second refund under a new key is a conflict, not a duplicate effect.
        assertStatus(409, refundCall(owner, orderId, refundKey(), "Pelanggan komplain"));
        assertEquals(1, count("SELECT count(*) FROM payment_refunds WHERE sales_order_id = ?::uuid", orderId));
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid "
                + "AND movement_type = 'SALE_REVERSAL'", orderId));
        assertEquals(1, auditRows(owner, "FNB_ORDER_REFUNDED", orderId));
        assertMoney("1000", balance(item[0]), "stock restored once");
    }

    @Test
    void refundRulesOpenOrderRoleValidationShiftsAndCashAttribution() throws Exception {
        MerchantFixture owner = api.onboardOwner("refund-rules");
        MerchantFixture cashier = api.onboardCashier(owner, "refund-rules-cashier");
        String productId = simpleProduct(owner);

        String openOrder = createOrder(owner, productId, 1);
        assertStatus(409, refundCall(owner, openOrder, refundKey(), "belum dibayar"));
        assertStatus(404, refundCall(owner, UUID.randomUUID().toString(), refundKey(), "tidak ada"));

        // The cashier takes the payments with their own shift.
        api.openShift(cashier, "0.00");
        String cashOrder = createOrder(cashier, productId, 1);
        BigDecimal cashTotal = total(cashier, cashOrder);
        payCash(cashier, cashOrder, "0");
        String nonCashOrder = createOrder(cashier, productId, 1);
        payNonCash(cashier, nonCashOrder);

        assertStatus(403, refundCall(cashier, cashOrder, refundKey(), "kasir tidak boleh"));
        assertStatus(400, api.call(refundRequest(owner, owner, cashOrder, null, "{\"reason\":\"tanpa kunci\"}")));
        assertStatus(400, api.call(refundRequest(owner, owner, cashOrder, "short", "{\"reason\":\"kunci pendek\"}")));
        assertStatus(400, api.call(refundRequest(owner, owner, cashOrder, refundKey(), "{\"reason\":\"   \"}")));
        assertStatus(400, api.call(refundRequest(owner, owner, cashOrder, refundKey(), "{}")));
        assertStatus(400, api.call(refundRequest(owner, owner, cashOrder, refundKey(),
                "{\"reason\":\"" + "x".repeat(501) + "\"}")));

        // CASH refund needs the owner's own open shift.
        MvcResult noShift = refundCall(owner, cashOrder, refundKey(), "belum ada shift");
        assertStatus(409, noShift);
        assertTrue(noShift.getResponse().getContentAsString().contains(SHIFT_REQUIRED));
        assertEquals("PAID", ApiTestSupport.read(getOrder(owner, cashOrder), "$.data.paymentStatus"));
        assertEquals(0, count("SELECT count(*) FROM payment_refunds WHERE tenant_id = ?", owner.tenantId()));
        assertEquals(0, auditRows(owner, "FNB_ORDER_REFUNDED", cashOrder));

        // A non-cash refund needs no shift and is recorded without one.
        MvcResult nonCashRefund = api.send(refundRequest(owner, owner, nonCashOrder, refundKey(),
                "{\"reason\":\"salah metode\"}"), 200);
        assertEquals("MANUAL_NON_CASH", ApiTestSupport.read(nonCashRefund, "$.data.method"));
        assertEquals("REFUNDED", ApiTestSupport.read(getOrder(owner, nonCashOrder), "$.data.paymentStatus"));
        assertEquals(1, count("SELECT count(*) FROM payment_refunds WHERE sales_order_id = ?::uuid "
                + "AND cash_shift_id IS NULL", nonCashOrder));

        // With an open owner shift the cash refund works and lands on the owner's shift, not the cashier's.
        String ownerShift = ApiTestSupport.read(api.openShift(owner, "100000.00"), "$.data.id");
        assertStatus(200, refundCall(owner, cashOrder, refundKey(), "sudah ada shift"));
        assertEquals(ownerShift, api.jdbc().queryForObject("SELECT cash_shift_id::text FROM payment_refunds "
                + "WHERE sales_order_id = ?::uuid", String.class, cashOrder));
        MvcResult ownerCurrent = currentShift(owner);
        assertMoney(cashTotal, money(ownerCurrent, "$.data.cashRefunds"), "owner shift refunds");
        assertMoney(new BigDecimal("100000").subtract(cashTotal), money(ownerCurrent, "$.data.expectedCash"),
                "owner shift expected cash drops by the refund");
        MvcResult cashierCurrent = currentShift(cashier);
        assertMoney("0", money(cashierCurrent, "$.data.cashRefunds"), "cashier shift is untouched by the refund");
        assertMoney(cashTotal, money(cashierCurrent, "$.data.cashIn"), "cashier cash in");
        assertEquals(2, count("SELECT count(*) FROM payment_refunds WHERE tenant_id = ?", owner.tenantId()));
    }

    @Test
    void anotherTenantCannotRefundOrLearnAboutTheOrder() throws Exception {
        MerchantFixture owner = api.onboardOwner("refund-iso-a");
        MerchantFixture other = api.onboardOwner("refund-iso-b");
        api.openShift(owner, "0.00");
        api.openShift(other, "0.00");
        String orderId = createOrder(owner, simpleProduct(owner), 1);
        payCash(owner, orderId, "0");

        assertStatus(404, api.call(refundRequest(other, other, orderId, refundKey(), "{\"reason\":\"bukan milik saya\"}")));
        assertStatus(404, api.call(refundRequest(other, owner, orderId, refundKey(), "{\"reason\":\"outlet orang lain\"}")));

        assertEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
        assertEquals(0, count("SELECT count(*) FROM payment_refunds WHERE sales_order_id = ?::uuid", orderId));
        assertEquals(0, auditRows(owner, "FNB_ORDER_REFUNDED", orderId));
    }

    @Test
    void concurrentRefundsWithDifferentKeysRefundExactlyOnceAndRestoreStockOnce() throws Exception {
        MerchantFixture owner = api.onboardOwner("refund-race");
        String[] item = new String[1];
        String productId = createProductWithRecipe(owner, "500", "20", item);
        api.openShift(owner, "0.00");
        String orderId = createOrder(owner, productId, 2);
        payCash(owner, orderId, "0");
        assertMoney("460", balance(item[0]), "stock after the sale");

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String key = refundKey();
            tasks.add(() -> refundCall(owner, orderId, key, "balapan"));
        }
        List<MvcResult> results = runInParallel(tasks);

        int ok = 0;
        int conflicts = 0;
        for (MvcResult result : results) {
            if (status(result) == 200) {
                ok++;
            } else if (status(result) == 409) {
                conflicts++;
            }
        }
        assertEquals(1, ok, "exactly one refund succeeds");
        assertEquals(3, conflicts, "all others conflict, never a server error");
        assertEquals(1, count("SELECT count(*) FROM payment_refunds WHERE sales_order_id = ?::uuid", orderId));
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid "
                + "AND movement_type = 'SALE_REVERSAL'", orderId));
        assertEquals(1, auditRows(owner, "FNB_ORDER_REFUNDED", orderId));
        assertMoney("500", balance(item[0]), "stock restored exactly once");
        assertNotEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
    }
}
