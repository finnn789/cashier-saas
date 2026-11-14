package org.lite.project.cashiersaas.integration.fnb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Petty cash, opening-cash suggestion, order note / optional customer and order list filters (TASK-078)
 * against real PostgreSQL, the real Flyway schema (V1..V8) and the security chain.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FnbPettyCashOrderIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final String PRICE = "25000";
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NO_SHIFT_FOR_EXPENSE = "Buka shift terlebih dahulu sebelum mencatat pengeluaran.";

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

    private static String shifts(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/shifts";
    }

    private static String orders(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/orders";
    }

    private static String expenseBody(String amount, String description) {
        return "{\"amount\":" + amount + (description == null ? "" : ",\"description\":\"" + description + "\"") + "}";
    }

    private MvcResult expense(MerchantFixture actor, MerchantFixture pathMerchant, String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(shifts(pathMerchant) + "/current/expenses")
                .header(AUTH, actor.bearer()).contentType(MediaType.APPLICATION_JSON).content(body);
        return api.call(key == null ? request : request.header("Idempotency-Key", key));
    }

    private MvcResult expense(MerchantFixture actor, String key, String amount, String description) throws Exception {
        return expense(actor, actor, key, expenseBody(amount, description));
    }

    private static String expenseKey() {
        return "exp-" + UUID.randomUUID();
    }

    private MvcResult currentShift(MerchantFixture actor) throws Exception {
        return api.call(get(shifts(actor) + "/current").header(AUTH, actor.bearer()));
    }

    private MvcResult close(MerchantFixture actor, String counted) throws Exception {
        return api.call(post(shifts(actor) + "/current/close").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":" + counted + "}"));
    }

    private MvcResult suggestion(MerchantFixture actor) throws Exception {
        return api.send(get(shifts(actor) + "/opening-suggestion").header(AUTH, actor.bearer()), 200);
    }

    private static BigDecimal money(MvcResult result, String path) throws Exception {
        return new BigDecimal(ApiTestSupport.read(result, path));
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + ": expected " + expected + " but was " + actual);
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual, String message) {
        assertEquals(0, expected.compareTo(actual), message + ": expected " + expected + " but was " + actual);
    }

    private static void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private static void assertSuccess(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || status == 201, result.getResponse().getContentAsString());
    }

    private int count(String sql, Object... args) {
        Integer value = api.jdbc().queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private String product(MerchantFixture owner) throws Exception {
        return api.createProduct(owner, api.createCategory(owner, "Minuman"), "Es Teh", PRICE);
    }

    private static JsonNode data(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static void assertNoValue(JsonNode node, String field) {
        assertTrue(node.path(field).isMissingNode() || node.path(field).isNull(), field + " must be empty: " + node);
    }

    // ------------------------------------------------------------------ petty cash

    @Test
    void pettyCashNeedsAnOpenShiftIsIdempotentAndIsSubtractedFromExpectedCashAtClose() throws Exception {
        MerchantFixture owner = api.onboardOwner("pc-math");
        MerchantFixture cashier = api.onboardCashier(owner, "pc-math-cashier");
        MerchantFixture other = api.onboardOwner("pc-math-other");
        String productId = product(owner);

        // No shift: refused with the petty-cash message, nothing written; listing expenses is 404.
        MvcResult noShift = expense(cashier, expenseKey(), "15000", "Beli es batu");
        assertStatus(409, noShift);
        assertTrue(noShift.getResponse().getContentAsString().contains(NO_SHIFT_FOR_EXPENSE));
        assertStatus(404, api.call(get(shifts(cashier) + "/current/expenses").header(AUTH, cashier.bearer())));
        assertEquals(0, count("SELECT count(*) FROM cash_expenses WHERE tenant_id = ?", owner.tenantId()));

        String shiftId = ApiTestSupport.read(api.openShift(cashier, "100000.00"), "$.data.id");
        MvcResult paid = api.payCashExact(cashier, api.createPosOrder(cashier, productId, 1));
        BigDecimal paidAmount = money(paid, "$.data.amountPaid");

        String key = expenseKey();
        MvcResult recorded = expense(cashier, key, "15000", "  Beli es batu  ");
        assertStatus(201, recorded);
        String expenseId = ApiTestSupport.read(recorded, "$.data.id");
        assertMoney("15000", money(recorded, "$.data.amount"), "expense amount");
        assertEquals("Beli es batu", ApiTestSupport.read(recorded, "$.data.description"));
        assertEquals("Cashier pc-math-cashier", ApiTestSupport.read(recorded, "$.data.recordedBy"));
        assertFalse(ApiTestSupport.read(recorded, "$.data.recordedAt").isBlank());
        assertEquals(shiftId, api.jdbc().queryForObject("SELECT cash_shift_id::text FROM cash_expenses WHERE id = ?::uuid",
                String.class, expenseId));

        // Replay: same key and body returns the stored expense, no second row or audit.
        MvcResult replay = expense(cashier, key, "15000", "Beli es batu");
        assertSuccess(replay);
        assertEquals(expenseId, ApiTestSupport.read(replay, "$.data.id"));
        assertEquals(1, count("SELECT count(*) FROM cash_expenses WHERE tenant_id = ?", owner.tenantId()));
        assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'CASH_EXPENSE_RECORDED' "
                + "AND entity_id = ?::uuid", owner.tenantId(), expenseId));
        assertEquals("amount=15000.00", api.jdbc().queryForObject("SELECT details FROM audit_logs WHERE tenant_id = ? "
                + "AND action = 'CASH_EXPENSE_RECORDED' AND entity_id = ?::uuid", String.class, owner.tenantId(), expenseId));

        // Same key with another body or by another member is a conflict.
        assertStatus(409, expense(cashier, key, "15001", "Beli es batu"));
        assertStatus(409, expense(cashier, key, "15000", "Beli gula"));
        assertStatus(409, expense(owner, owner, key, expenseBody("15000", "Beli es batu")));
        assertEquals(1, count("SELECT count(*) FROM cash_expenses WHERE tenant_id = ?", owner.tenantId()));

        // Validation.
        assertStatus(400, expense(cashier, expenseKey(), "0", "Nol"));
        assertStatus(400, expense(cashier, expenseKey(), "-5", "Negatif"));
        assertStatus(400, expense(cashier, expenseKey(), "10.001", "Tiga desimal"));
        assertStatus(400, expense(cashier, expenseKey(), "100", null));
        assertStatus(400, expense(cashier, expenseKey(), "100", "   "));
        assertStatus(400, expense(cashier, expenseKey(), "100", "x".repeat(201)));
        assertStatus(400, expense(cashier, cashier, null, expenseBody("100", "Tanpa key")));
        assertStatus(400, expense(cashier, cashier, "short", expenseBody("100", "Key pendek")));
        assertEquals(1, count("SELECT count(*) FROM cash_expenses WHERE tenant_id = ?", owner.tenantId()));

        // A second expense; the cashier lists both in order and the live shift subtracts them.
        assertStatus(201, expense(cashier, expenseKey(), "5000.50", "Parkir"));
        MvcResult listed = api.send(get(shifts(cashier) + "/current/expenses").header(AUTH, cashier.bearer()), 200);
        assertEquals(List.of("Beli es batu", "Parkir"), JsonPath.read(listed.getResponse().getContentAsString(),
                "$.data[*].description"));

        MvcResult current = currentShift(cashier);
        assertStatus(200, current);
        assertMoney("20000.50", money(current, "$.data.expensesTotal"), "expenses total");
        BigDecimal expected = new BigDecimal("100000").add(paidAmount).subtract(new BigDecimal("20000.50"));
        assertMoney(expected, money(current, "$.data.expectedCash"), "expected = opening + cash in - expenses");
        assertEquals("2", ApiTestSupport.read(current, "$.data.expenses.length()"));
        assertEquals("Parkir", ApiTestSupport.read(current, "$.data.expenses[1].description"));

        // The owner (no own shift) cannot list the cashier's expenses via the current route, but sees them on the
        // shift detail and a count-only summary on the list.
        assertStatus(404, api.call(get(shifts(owner) + "/current/expenses").header(AUTH, owner.bearer())));
        String merchantShifts = "/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/shifts";
        MvcResult detail = api.send(get(merchantShifts + "/" + shiftId).header(AUTH, owner.bearer()), 200);
        assertMoney(expected, money(detail, "$.data.expectedCash"), "detail expected cash");
        assertEquals("2", ApiTestSupport.read(detail, "$.data.expenses.length()"));
        LocalDate today = LocalDate.now(JAKARTA);
        MvcResult list = api.send(get(merchantShifts).param("from", today.minusDays(1).toString())
                .param("to", today.plusDays(1).toString()).header(AUTH, owner.bearer()), 200);
        assertMoney("20000.50", money(list, "$.data.shifts[0].expensesTotal"), "list row expenses total");
        assertNoValue(data(list).path("shifts").path(0), "expenses");

        // Another tenant's cashier gets no evidence; unauthenticated is 401.
        MerchantFixture otherCashier = api.onboardCashier(other, "pc-math-other-cashier");
        assertStatus(404, expense(otherCashier, owner, expenseKey(), expenseBody("100", "Hack")));
        assertEquals(401, api.call(post(shifts(owner) + "/current/expenses").header("Idempotency-Key", expenseKey())
                .contentType(MediaType.APPLICATION_JSON).content(expenseBody("100", "Hack"))).getResponse().getStatus());

        // Close: expected includes the expenses; counting exactly the expected cash gives difference 0.
        MvcResult closed = close(cashier, expected.toPlainString());
        assertStatus(200, closed);
        assertEquals("CLOSED", ApiTestSupport.read(closed, "$.data.status"));
        assertMoney(expected, money(closed, "$.data.expectedCash"), "closed expected cash");
        assertMoney("0", money(closed, "$.data.cashDifference"), "no difference");
        assertMoney("20000.50", money(closed, "$.data.expensesTotal"), "closed expenses total");
        assertMoney(expected, api.jdbc().queryForObject("SELECT expected_cash FROM cash_shifts WHERE id = ?::uuid",
                BigDecimal.class, shiftId), "stored expected cash");

        // After close: a replay still answers from the stored expense, a new expense is refused (no open shift).
        assertSuccess(expense(cashier, key, "15000", "Beli es batu"));
        assertStatus(409, expense(cashier, expenseKey(), "100", "Terlambat"));
        assertEquals(2, count("SELECT count(*) FROM cash_expenses WHERE tenant_id = ?", owner.tenantId()));
    }

    @Test
    void expensesAreCountedAgainstTheDrawerWhenClosingTheShift() throws Exception {
        MerchantFixture owner = api.onboardOwner("pc-diff");
        api.openShift(owner, "50000.00");
        assertStatus(201, expense(owner, expenseKey(), "10000", "Beli plastik"));

        // The drawer holds 40000: counting it against expected (50000 - 10000) is exactly balanced.
        MvcResult closed = close(owner, "40000");
        assertStatus(200, closed);
        assertMoney("40000", money(closed, "$.data.expectedCash"), "expected after expenses");
        assertMoney("0", money(closed, "$.data.cashDifference"), "balanced");
    }

    // ------------------------------------------------------------------ opening suggestion

    @Test
    void openingSuggestionIsZeroWithoutHistoryElseTheCountedCashOfTheLatestClosedShiftOfAnyMember() throws Exception {
        MerchantFixture owner = api.onboardOwner("sug");
        MerchantFixture cashier = api.onboardCashier(owner, "sug-cashier");
        MerchantFixture other = api.onboardOwner("sug-other");

        MvcResult none = suggestion(cashier);
        assertMoney("0", money(none, "$.data.openingCash"), "no history");
        assertNoValue(data(none), "sourceShiftId");

        // An open shift is not history.
        String first = ApiTestSupport.read(api.openShift(cashier, "50000.00"), "$.data.id");
        assertMoney("0", money(suggestion(owner), "$.data.openingCash"), "open shifts do not count");

        assertStatus(200, close(cashier, "47500.00"));
        MvcResult afterFirst = suggestion(owner);
        assertMoney("47500", money(afterFirst, "$.data.openingCash"), "counted cash of the closed shift");
        assertEquals(first, ApiTestSupport.read(afterFirst, "$.data.sourceShiftId"));
        assertMoney("47500", money(suggestion(cashier), "$.data.openingCash"), "any member of the outlet sees it");

        // The owner opens with the suggestion and closes with a different count: the latest closed shift wins.
        String second = ApiTestSupport.read(api.openShift(owner, "47500.00"), "$.data.id");
        assertEquals(first, ApiTestSupport.read(suggestion(owner), "$.data.sourceShiftId"), "still the closed one");
        assertStatus(200, close(owner, "60000.50"));
        MvcResult afterSecond = suggestion(cashier);
        assertMoney("60000.50", money(afterSecond, "$.data.openingCash"), "latest closed shift");
        assertEquals(second, ApiTestSupport.read(afterSecond, "$.data.sourceShiftId"));

        // Other tenants never see it; no token is 401.
        assertMoney("0", money(suggestion(other), "$.data.openingCash"), "other tenant has its own history");
        assertStatus(404, api.call(get(shifts(owner) + "/opening-suggestion").header(AUTH, other.bearer())));
        assertEquals(401, api.call(get(shifts(owner) + "/opening-suggestion")).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ order note and customer label

    private MvcResult postOrder(MerchantFixture actor, String key, String productId, int quantity, String customerLabel,
                                String orderNote) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("serviceType", "TAKEAWAY");
        if (customerLabel != null) body.put("customerLabel", customerLabel);
        if (orderNote != null) body.put("orderNote", orderNote);
        body.putArray("items").addObject().put("productId", productId).put("quantity", quantity).putArray("optionIds");
        return api.call(post(orders(actor)).header(AUTH, actor.bearer()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private MvcResult putItems(MerchantFixture actor, String orderId, String productId, String customerLabel,
                               String orderNote) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("serviceType", "DINE_IN");
        if (customerLabel != null) body.put("customerLabel", customerLabel);
        if (orderNote != null) body.put("orderNote", orderNote);
        body.putArray("items").addObject().put("productId", productId).put("quantity", 1).putArray("optionIds");
        return api.call(put(orders(actor) + "/" + orderId + "/items").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private String column(String column, String orderId) {
        return api.jdbc().queryForObject("SELECT " + column + " FROM sales_orders WHERE id = ?::uuid", String.class, orderId);
    }

    @Test
    void orderNoteAndCustomerLabelAreOptionalTrimmedClearedOnBlankAndPartOfTheIdempotencyFingerprint() throws Exception {
        MerchantFixture owner = api.onboardOwner("note");
        String productId = product(owner);

        // Neither a customer nor a note is required.
        MvcResult bare = postOrder(owner, "pos-" + UUID.randomUUID(), productId, 1, null, null);
        assertStatus(201, bare);
        String bareId = ApiTestSupport.read(bare, "$.data.orderId");
        assertNoValue(data(bare), "customerLabel");
        assertNoValue(data(bare), "orderNote");
        assertNull(column("customer_label", bareId));
        assertNull(column("order_note", bareId));

        // Blank values become null; real values are trimmed.
        MvcResult blank = postOrder(owner, "pos-" + UUID.randomUUID(), productId, 1, "   ", "   ");
        assertStatus(201, blank);
        String blankId = ApiTestSupport.read(blank, "$.data.orderId");
        assertNull(column("customer_label", blankId));
        assertNull(column("order_note", blankId));

        String key = "pos-" + UUID.randomUUID();
        MvcResult noted = postOrder(owner, key, productId, 1, "  Budi  ", "  tanpa gula, es sedikit  ");
        assertStatus(201, noted);
        String notedId = ApiTestSupport.read(noted, "$.data.orderId");
        assertEquals("Budi", ApiTestSupport.read(noted, "$.data.customerLabel"));
        assertEquals("tanpa gula, es sedikit", ApiTestSupport.read(noted, "$.data.orderNote"));
        assertEquals("tanpa gula, es sedikit", column("order_note", notedId));
        MvcResult detail = api.send(get(orders(owner) + "/" + notedId).header(AUTH, owner.bearer()), 200);
        assertEquals("tanpa gula, es sedikit", ApiTestSupport.read(detail, "$.data.orderNote"));
        MvcResult listed = api.send(get(orders(owner)).param("orderNumber", ApiTestSupport.read(noted, "$.data.orderNumber"))
                .header(AUTH, owner.bearer()), 200);
        assertEquals("tanpa gula, es sedikit", ApiTestSupport.read(listed, "$.data.orders[0].orderNote"));

        // The note is part of the idempotency fingerprint: same body replays, a different note conflicts.
        MvcResult replay = postOrder(owner, key, productId, 1, "  Budi  ", "  tanpa gula, es sedikit  ");
        assertSuccess(replay);
        assertEquals(notedId, ApiTestSupport.read(replay, "$.data.orderId"));
        assertStatus(409, postOrder(owner, key, productId, 1, "Budi", "catatan lain"));
        assertEquals(3, count("SELECT count(*) FROM sales_orders WHERE tenant_id = ?", owner.tenantId()));

        // Size limits.
        assertStatus(400, postOrder(owner, "pos-" + UUID.randomUUID(), productId, 1, null, "n".repeat(501)));
        assertStatus(400, postOrder(owner, "pos-" + UUID.randomUUID(), productId, 1, "c".repeat(81), null));
        assertStatus(201, postOrder(owner, "pos-" + UUID.randomUUID(), productId, 1, "c".repeat(80), "n".repeat(500)));

        // Replace items (hold bill): set, trim, then blank clears both.
        MvcResult replaced = putItems(owner, notedId, productId, " Siti ", " extra pedas ");
        assertStatus(200, replaced);
        assertEquals("Siti", ApiTestSupport.read(replaced, "$.data.customerLabel"));
        assertEquals("extra pedas", ApiTestSupport.read(replaced, "$.data.orderNote"));
        assertEquals("extra pedas", column("order_note", notedId));
        MvcResult cleared = putItems(owner, notedId, productId, "  ", "  ");
        assertStatus(200, cleared);
        assertNoValue(data(cleared), "customerLabel");
        assertNoValue(data(cleared), "orderNote");
        assertNull(column("customer_label", notedId));
        assertNull(column("order_note", notedId));
        MvcResult omitted = putItems(owner, notedId, productId, null, "catatan baru");
        assertStatus(200, omitted);
        assertEquals("catatan baru", ApiTestSupport.read(omitted, "$.data.orderNote"));
        assertNoValue(data(omitted), "customerLabel");
        assertStatus(400, putItems(owner, notedId, productId, null, "n".repeat(501)));
    }

    // ------------------------------------------------------------------ order list filters

    private MvcResult listOrders(MerchantFixture actor, String from, String to, String orderNumber) throws Exception {
        MockHttpServletRequestBuilder request = get(orders(actor)).header(AUTH, actor.bearer());
        if (from != null) request = request.param("from", from);
        if (to != null) request = request.param("to", to);
        if (orderNumber != null) request = request.param("orderNumber", orderNumber);
        return api.call(request);
    }

    private static List<Object> ids(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.orders[*].orderId");
    }

    @Test
    void orderListFiltersByOutletDayRangeAndExactOrderNumber() throws Exception {
        MerchantFixture owner = api.onboardOwner("filters");
        MerchantFixture other = api.onboardOwner("filters-other");
        String productId = product(owner);
        LocalDate today = LocalDate.now(JAKARTA);
        LocalDate yesterday = today.minusDays(1);

        String midnightOrder = api.createPosOrder(owner, productId, 1);
        String lateOrder = api.createPosOrder(owner, productId, 1);
        String freshOrder = api.createPosOrder(owner, productId, 1);
        // Exactly 00:00 today (included in today, excluded from yesterday) and 23:30 yesterday, both in the outlet zone.
        api.jdbc().update("UPDATE sales_orders SET created_at = ? WHERE id = ?::uuid",
                Timestamp.from(today.atStartOfDay(JAKARTA).toInstant()), midnightOrder);
        api.jdbc().update("UPDATE sales_orders SET created_at = ? WHERE id = ?::uuid",
                Timestamp.from(yesterday.atTime(23, 30).atZone(JAKARTA).toInstant()), lateOrder);

        MvcResult todayOnly = listOrders(owner, today.toString(), today.toString(), null);
        assertStatus(200, todayOnly);
        assertEquals(2, ids(todayOnly).size());
        assertTrue(ids(todayOnly).containsAll(List.of(midnightOrder, freshOrder)), "00:00 today is inside today");
        MvcResult yesterdayOnly = listOrders(owner, yesterday.toString(), yesterday.toString(), null);
        assertEquals(List.of(lateOrder), ids(yesterdayOnly), "23:30 yesterday (outlet zone) is only in yesterday");
        MvcResult both = listOrders(owner, yesterday.toString(), today.toString(), null);
        assertEquals(3, ids(both).size());
        assertEquals(0, ids(listOrders(owner, today.plusDays(1).toString(), today.plusDays(2).toString(), null)).size());
        assertEquals(3, ids(listOrders(owner, null, null, null)).size(), "no filter returns everything");

        // from and to come as a pair; the range must be ordered and at most 366 days.
        assertStatus(400, listOrders(owner, today.toString(), null, null));
        assertStatus(400, listOrders(owner, null, today.toString(), null));
        assertStatus(400, listOrders(owner, today.toString(), yesterday.toString(), null));
        assertStatus(200, listOrders(owner, today.minusDays(365).toString(), today.toString(), null));
        assertStatus(400, listOrders(owner, today.minusDays(366).toString(), today.toString(), null));
        assertStatus(400, listOrders(owner, "not-a-date", today.toString(), null));

        // Exact order number, trimmed; partial and unknown numbers match nothing; blank is ignored; combines with dates.
        List<Object> numbers = JsonPath.read(listOrders(owner, null, null, null).getResponse().getContentAsString(),
                "$.data.orders[?(@.orderId=='" + freshOrder + "')].orderNumber");
        String number = String.valueOf(numbers.get(0));
        assertEquals(List.of(freshOrder), ids(listOrders(owner, null, null, number)));
        assertEquals(List.of(freshOrder), ids(listOrders(owner, null, null, "  " + number + "  ")));
        assertEquals(0, ids(listOrders(owner, null, null, number.substring(0, number.length() - 1))).size());
        assertEquals(0, ids(listOrders(owner, null, null, "POS-DOES-NOT-EXIST")).size());
        assertEquals(3, ids(listOrders(owner, null, null, "   ")).size());
        assertEquals(List.of(freshOrder), ids(listOrders(owner, today.toString(), today.toString(), number)));
        assertEquals(0, ids(listOrders(owner, yesterday.toString(), yesterday.toString(), number)).size());

        // Tenant isolation: another outlet never matches this outlet's order numbers or dates.
        assertEquals(0, ids(listOrders(other, null, null, number)).size());
        assertEquals(0, ids(listOrders(other, today.toString(), today.toString(), null)).size());
        assertStatus(404, api.call(get(orders(owner)).param("orderNumber", number).header(AUTH, other.bearer())));
    }
}
