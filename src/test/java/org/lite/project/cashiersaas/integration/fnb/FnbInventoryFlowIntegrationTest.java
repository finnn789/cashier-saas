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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * End-to-end ingredient inventory, recipe and stock-warning flow (TASK-061) against real PostgreSQL,
 * real Flyway schema (V1..V4) and the real security chain.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FnbInventoryFlowIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final String PRICE = "25000";

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

    private static String productUrl(MerchantFixture merchant, String productId) {
        return "/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/products/" + productId;
    }

    private static String productRecipeUrl(MerchantFixture merchant, String productId) {
        return productUrl(merchant, productId) + "/recipe";
    }

    private static String optionRecipeUrl(MerchantFixture merchant, String productId, String groupId, String optionId) {
        return productUrl(merchant, productId) + "/groups/" + groupId + "/options/" + optionId + "/recipe";
    }

    private static String itemJson(String name, String unit, String reorderLevel, String initialQuantity) {
        return "{\"name\":\"" + name + "\",\"unit\":\"" + unit + "\",\"reorderLevel\":" + reorderLevel
                + (initialQuantity == null ? "" : ",\"initialQuantity\":" + initialQuantity) + "}";
    }

    /** Pairs of (inventoryItemId, quantityPerUnit). */
    private static String recipeJson(String... pairs) {
        StringBuilder json = new StringBuilder("{\"lines\":[");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"inventoryItemId\":\"").append(pairs[i]).append("\",\"quantityPerUnit\":")
                    .append(pairs[i + 1]).append('}');
        }
        return json.append("]}").toString();
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

    private MvcResult putRecipe(MerchantFixture owner, String url, String body) throws Exception {
        return api.call(put(url).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void setRecipe(MerchantFixture owner, String url, String body) throws Exception {
        MvcResult result = putRecipe(owner, url, body);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private String createGroup(MerchantFixture owner, String productId) throws Exception {
        return ApiTestSupport.read(api.send(post(productUrl(owner, productId) + "/groups")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tambahan\",\"required\":false,\"minimumSelections\":0,"
                        + "\"maximumSelections\":3,\"displayOrder\":1}"), 201), "$.data.id");
    }

    private String createOption(MerchantFixture owner, String productId, String groupId, String name, String price)
            throws Exception {
        return ApiTestSupport.read(api.send(post(productUrl(owner, productId) + "/groups/" + groupId + "/options")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"priceAdjustment\":" + price
                        + ",\"available\":true,\"displayOrder\":1}"), 201), "$.data.id");
    }

    /** Creates an unpaid POS takeaway order (no table needed) and returns its id. */
    private String createOrder(MerchantFixture actor, String productId, int quantity, String... optionIds)
            throws Exception {
        StringBuilder options = new StringBuilder();
        for (int i = 0; i < optionIds.length; i++) {
            if (i > 0) {
                options.append(',');
            }
            options.append('"').append(optionIds[i]).append('"');
        }
        String body = "{\"serviceType\":\"TAKEAWAY\",\"customerLabel\":\"Budi\",\"items\":[{\"productId\":\""
                + productId + "\",\"quantity\":" + quantity + ",\"optionIds\":[" + options + "]}]}";
        MvcResult created = api.send(post(orders(actor)).header(AUTH, actor.bearer())
                .header("Idempotency-Key", "pos-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(body), 201);
        assertEquals("UNPAID", ApiTestSupport.read(created, "$.data.paymentStatus"));
        return ApiTestSupport.read(created, "$.data.orderId");
    }

    private MvcResult getOrder(MerchantFixture actor, String orderId) throws Exception {
        return api.send(get(orders(actor) + "/" + orderId).header(AUTH, actor.bearer()), 200);
    }

    private MvcResult payCash(MerchantFixture actor, String orderId, String key) throws Exception {
        return api.send(post(orders(actor) + "/" + orderId + "/payments").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson("CASH", "1000000", key)), 200, 201);
    }

    private MvcResult postMovement(MerchantFixture actor, String itemId, String body) throws Exception {
        return api.call(post(stockItems(actor) + "/" + itemId + "/movements").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult recordMovement(MerchantFixture actor, String itemId, String type, String quantity, String reason)
            throws Exception {
        MvcResult result = postMovement(actor, itemId,
                movementJson(type, quantity, reason, "mv-" + UUID.randomUUID()));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result;
    }

    private MvcResult listStock(MerchantFixture actor, String status) throws Exception {
        if (status == null) {
            return api.send(get(stockItems(actor)).header(AUTH, actor.bearer()), 200);
        }
        return api.send(get(stockItems(actor)).param("status", status).header(AUTH, actor.bearer()), 200);
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, status(result), result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ JSON and SQL helpers

    private static List<String> readList(MvcResult result, String path) throws Exception {
        List<Object> raw = JsonPath.read(result.getResponse().getContentAsString(), path);
        List<String> values = new ArrayList<>();
        for (Object value : raw) {
            values.add(String.valueOf(value));
        }
        return values;
    }

    private static List<String> warnings(MvcResult paymentResult) throws Exception {
        return readList(paymentResult, "$.data.stockWarnings");
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

    private int saleMovements(String orderId) {
        return count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid AND movement_type = 'SALE'",
                orderId);
    }

    private int movementsOfItem(String itemId) {
        return count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid", itemId);
    }

    private int recipeLinesOfProduct(String productId) {
        return count("SELECT count(*) FROM recipe_lines WHERE product_id = ?::uuid", productId);
    }

    /** The movement ledger must always add up to the cached balance. */
    private void assertLedgerMatchesBalance(String itemId) {
        BigDecimal sum = api.jdbc().queryForObject(
                "SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_movements WHERE inventory_item_id = ?::uuid",
                BigDecimal.class, itemId);
        assertEquals(0, sum.compareTo(balance(itemId)),
                "SUM(quantity_delta)=" + sum + " must equal quantity_on_hand=" + balance(itemId));
    }

    private static String stockStatusOf(MvcResult list, String itemId) throws Exception {
        List<String> values = readList(list, "$.data[?(@.id=='" + itemId + "')].stockStatus");
        assertEquals(1, values.size(), "item " + itemId + " must appear exactly once in the stock list");
        return values.get(0);
    }

    // ------------------------------------------------------------------ 1. happy path

    @Test
    void paidOrderDeductsProductAndOptionRecipeWithOneSaleMovementPerIngredient() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-happy");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi Susu", PRICE);
        String kopi = createIngredient(owner, "Biji Kopi", "g", "100", "1000");
        String susu = createIngredient(owner, "Susu", "ml", "50", "500");
        setRecipe(owner, productRecipeUrl(owner, productId), recipeJson(kopi, "18", susu, "100"));
        String groupId = createGroup(owner, productId);
        String shotId = createOption(owner, productId, groupId, "Extra Shot", "5000");
        setRecipe(owner, optionRecipeUrl(owner, productId, groupId, shotId), recipeJson(kopi, "18"));

        String orderId = createOrder(owner, productId, 2, shotId);
        MvcResult paid = payCash(owner, orderId, "pay-" + UUID.randomUUID());

        assertTrue(warnings(paid).isEmpty(), "stock stays above reorder level: " + warnings(paid));
        // Kopi: 2 x (18 product + 18 option) = 72, Susu: 2 x 100 = 200.
        assertDecimal("928", balance(kopi), "kopi balance");
        assertDecimal("300", balance(susu), "susu balance");
        assertEquals(2, saleMovements(orderId), "exactly one SALE movement per ingredient");
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid "
                + "AND inventory_item_id = ?::uuid AND movement_type = 'SALE'", orderId, kopi),
                "product and option recipe on the same ingredient are summed into one movement");
        assertDecimal("-72", api.jdbc().queryForObject("SELECT quantity_delta FROM inventory_movements "
                + "WHERE order_id = ?::uuid AND inventory_item_id = ?::uuid", BigDecimal.class, orderId, kopi),
                "kopi sale delta");
        assertDecimal("-200", api.jdbc().queryForObject("SELECT quantity_delta FROM inventory_movements "
                + "WHERE order_id = ?::uuid AND inventory_item_id = ?::uuid", BigDecimal.class, orderId, susu),
                "susu sale delta");
        assertEquals("sale:" + orderId + ":" + kopi, api.jdbc().queryForObject(
                "SELECT idempotency_key FROM inventory_movements WHERE order_id = ?::uuid AND inventory_item_id = ?::uuid",
                String.class, orderId, kopi));
        assertLedgerMatchesBalance(kopi);
        assertLedgerMatchesBalance(susu);

        MvcResult list = listStock(owner, null);
        assertDecimal("928", decimal(readList(list, "$.data[?(@.id=='" + kopi + "')].quantityOnHand").get(0)),
                "list quantityOnHand");
        assertEquals("OK", stockStatusOf(list, kopi));
        assertEquals("OK", stockStatusOf(list, susu));

        MvcResult history = api.send(get(stockItems(owner) + "/" + kopi + "/movements")
                .header(AUTH, owner.bearer()), 200);
        assertEquals(List.of(orderId), readList(history, "$.data[?(@.type=='SALE')].orderId"));
        assertEquals(List.of("RESTOCK"), readList(history, "$.data[?(@.type=='RESTOCK')].type"));
    }

    // ------------------------------------------------------------------ 2. negative stock allowed

    @Test
    void stockMayGoNegativeWithoutBlockingPaymentAndIsReportedAsNegative() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-negative");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Latte", PRICE);
        String susu = createIngredient(owner, "Susu", "ml", "5", "10");
        setRecipe(owner, productRecipeUrl(owner, productId), recipeJson(susu, "18"));

        String orderId = createOrder(owner, productId, 2);
        MvcResult paid = payCash(owner, orderId, "pay-" + UUID.randomUUID());

        assertEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
        List<String> warnings = warnings(paid);
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals("Susu: sisa -26 ml (minus)", warnings.get(0));
        assertDecimal("-26", balance(susu), "balance goes negative");
        assertLedgerMatchesBalance(susu);

        MvcResult all = listStock(owner, null);
        assertEquals("NEGATIVE", stockStatusOf(all, susu));
        assertDecimal("-26", decimal(readList(all, "$.data[?(@.id=='" + susu + "')].quantityOnHand").get(0)),
                "list quantityOnHand");
        assertEquals(List.of(susu), readList(listStock(owner, "NEGATIVE"), "$.data[*].id"));
        assertTrue(readList(listStock(owner, "OK"), "$.data[*].id").isEmpty(), "status filter excludes it");
        assertTrue(readList(listStock(owner, "LOW"), "$.data[*].id").isEmpty());
    }

    // ------------------------------------------------------------------ 3. LOW (and OUT) warning

    @Test
    void stockAtOrBelowReorderLevelWarnsLowAndExactlyZeroWarnsOut() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-low");
        String productId = api.createProduct(owner, api.createCategory(owner, "Makanan"), "Telur Dadar", PRICE);
        String telur = createIngredient(owner, "Telur", "butir", "70", "100");
        setRecipe(owner, productRecipeUrl(owner, productId), recipeJson(telur, "20"));

        MvcResult low = payCash(owner, createOrder(owner, productId, 2), "pay-" + UUID.randomUUID());
        assertEquals(List.of("Telur: sisa 60 butir (menipis)"), warnings(low));
        assertDecimal("60", balance(telur), "balance after first order");
        assertEquals("LOW", stockStatusOf(listStock(owner, null), telur));
        assertEquals(List.of(telur), readList(listStock(owner, "LOW"), "$.data[*].id"));

        MvcResult out = payCash(owner, createOrder(owner, productId, 3), "pay-" + UUID.randomUUID());
        assertEquals(List.of("Telur: sisa 0 butir (habis)"), warnings(out));
        assertDecimal("0", balance(telur), "balance drained to exactly zero");
        assertEquals("OUT", stockStatusOf(listStock(owner, null), telur));
        assertLedgerMatchesBalance(telur);
    }

    // ------------------------------------------------------------------ 4. mock gateway

    @Test
    void mockGatewayDeductsStockOnlyWhenResolvedSucceededAndExactlyOnce() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-gateway");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Matcha", PRICE);
        String matcha = createIngredient(owner, "Bubuk Matcha", "g", "10", "100");
        setRecipe(owner, productRecipeUrl(owner, productId), recipeJson(matcha, "18"));
        String orderId = createOrder(owner, productId, 2);
        String total = ApiTestSupport.read(getOrder(owner, orderId), "$.data.total");
        String payUrl = orders(owner) + "/" + orderId + "/payments";

        // Attempt 1 is initiated and resolved FAILED: nothing is deducted.
        MvcResult first = api.send(post(payUrl).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("MOCK_GATEWAY", total, "gw-" + UUID.randomUUID())), 200, 201);
        assertEquals("PENDING", ApiTestSupport.read(first, "$.data.gatewayOutcome"));
        String failedAttempt = ApiTestSupport.read(first, "$.data.attemptId");
        assertDecimal("100", balance(matcha), "initiate must not deduct");
        assertEquals(0, saleMovements(orderId));

        MvcResult failed = api.send(post(orders(owner) + "/" + orderId + "/mock-payments/" + failedAttempt + "/resolve")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"FAILED\"}"), 200);
        assertEquals("FAILED", ApiTestSupport.read(failed, "$.data.gatewayOutcome"));
        assertTrue(warnings(failed).isEmpty());
        assertDecimal("100", balance(matcha), "FAILED outcome deducts nothing");
        assertEquals(0, saleMovements(orderId));
        assertEquals("UNPAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));

        // Attempt 2 succeeds: deducted once, with a warning-free response (64 left, above reorder level 10).
        MvcResult second = api.send(post(payUrl).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("MOCK_GATEWAY", total, "gw-" + UUID.randomUUID())), 200, 201);
        String attemptId = ApiTestSupport.read(second, "$.data.attemptId");
        String resolveUrl = orders(owner) + "/" + orderId + "/mock-payments/" + attemptId + "/resolve";
        MvcResult settled = api.send(post(resolveUrl).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"SUCCEEDED\"}"), 200);
        assertEquals("SUCCEEDED", ApiTestSupport.read(settled, "$.data.gatewayOutcome"));
        assertTrue(warnings(settled).isEmpty());
        assertDecimal("64", balance(matcha), "SUCCEEDED deducts 2 x 18");
        assertEquals(1, saleMovements(orderId));
        assertEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));

        // Resolving the same terminal outcome again is a replay and must not deduct again.
        api.send(post(resolveUrl).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"SUCCEEDED\"}"), 200);
        assertDecimal("64", balance(matcha), "terminal replay deducts nothing more");
        assertEquals(1, saleMovements(orderId));
        assertLedgerMatchesBalance(matcha);
    }

    // ------------------------------------------------------------------ 5. idempotent payment replay

    @Test
    void replayingTheSamePaymentKeyDoesNotDeductTwice() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-replay");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Es Teh", PRICE);
        String teh = createIngredient(owner, "Daun Teh", "g", "5", "200");
        setRecipe(owner, productRecipeUrl(owner, productId), recipeJson(teh, "10"));
        String orderId = createOrder(owner, productId, 3);
        String key = "pay-" + UUID.randomUUID();

        MvcResult first = payCash(owner, orderId, key);
        assertDecimal("170", balance(teh), "3 x 10 deducted once");
        assertEquals(1, saleMovements(orderId));

        MvcResult replay = payCash(owner, orderId, key);
        assertEquals(ApiTestSupport.read(first, "$.data.receiptNumber"),
                ApiTestSupport.read(replay, "$.data.receiptNumber"), "replay returns the same payment");
        assertDecimal("170", balance(teh), "replay must not deduct again");
        assertEquals(1, saleMovements(orderId));
        assertEquals(2, movementsOfItem(teh), "initial restock + one sale only");

        // A new key on an already paid order is refused and also leaves stock alone.
        MvcResult second = api.call(post(orders(owner) + "/" + orderId + "/payments").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson("CASH", "1000000", "pay-" + UUID.randomUUID())));
        assertStatus(409, second);
        assertDecimal("170", balance(teh), "refused payment deducts nothing");
        assertEquals(2, movementsOfItem(teh));
        assertLedgerMatchesBalance(teh);
    }

    // ------------------------------------------------------------------ 6. product without recipe

    @Test
    void productWithoutRecipePaysNormallyWithoutMovementsOrWarnings() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-norecipe");
        String category = api.createCategory(owner, "Minuman");
        String plain = api.createProduct(owner, category, "Air Mineral", PRICE);
        String withRecipe = api.createProduct(owner, category, "Kopi", PRICE);
        String kopi = createIngredient(owner, "Biji Kopi", "g", "10", "100");
        setRecipe(owner, productRecipeUrl(owner, withRecipe), recipeJson(kopi, "18"));

        String orderId = createOrder(owner, plain, 2);
        MvcResult paid = payCash(owner, orderId, "pay-" + UUID.randomUUID());

        assertEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
        assertTrue(warnings(paid).isEmpty());
        assertEquals(0, count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid", orderId));
        assertEquals(0, count("SELECT count(*) FROM inventory_movements WHERE tenant_id = ?::uuid "
                + "AND movement_type = 'SALE'", owner.tenantId().toString()));
        assertDecimal("100", balance(kopi), "unrelated ingredient untouched");
    }

    // ------------------------------------------------------------------ 7. cancelled unpaid order

    @Test
    void cancellingAnUnpaidOrderCreatesNoInventoryMovements() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-cancel");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String kopi = createIngredient(owner, "Biji Kopi", "g", "10", "100");
        setRecipe(owner, productRecipeUrl(owner, productId), recipeJson(kopi, "18"));
        String orderId = createOrder(owner, productId, 2);

        // Creating the order alone reserves nothing.
        assertEquals(0, count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid", orderId));

        api.send(post(orders(owner) + "/" + orderId + "/cancel").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Salah pesan\"}"), 200);

        assertEquals("CANCELLED", ApiTestSupport.read(getOrder(owner, orderId), "$.data.status"));
        assertEquals(0, count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid", orderId));
        assertDecimal("100", balance(kopi), "cancelled unpaid order leaves stock untouched");
        assertEquals(1, movementsOfItem(kopi), "only the initial restock exists");
        assertLedgerMatchesBalance(kopi);
    }

    // ------------------------------------------------------------------ 8. roles

    @Test
    void cashierMayRestockAndWasteButOnlyOwnerAdjustsAndManagesIngredientsAndRecipes() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-roles");
        MerchantFixture cashier = api.onboardCashier(owner, "inv-roles-cashier");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi", PRICE);
        String kopi = createIngredient(owner, "Biji Kopi", "g", "10", "100");

        // Cashier: RESTOCK +50 and WASTE -20 are allowed and carry the cashier's membership as actor.
        MvcResult restock = recordMovement(cashier, kopi, "RESTOCK", "50", "Belanja pasar");
        assertEquals("RESTOCK", ApiTestSupport.read(restock, "$.data.type"));
        recordMovement(cashier, kopi, "WASTE", "20", "Tumpah");
        assertDecimal("130", balance(kopi), "100 + 50 - 20");
        String cashierMembership = ApiTestSupport.read(restock, "$.data.actorMembershipId");
        String ownerMembership = api.jdbc().queryForObject(
                "SELECT actor_membership_id FROM inventory_movements WHERE idempotency_key = ?",
                String.class, "init:" + kopi);
        assertNotEquals(ownerMembership, cashierMembership, "actor is the cashier, not the owner");

        // Cashier ADJUSTMENT is hidden behind requireOwner: 404, and nothing changes.
        MvcResult adjust = postMovement(cashier, kopi,
                movementJson("ADJUSTMENT", "-30", "Stok opname", "adj-" + UUID.randomUUID()));
        assertStatus(404, adjust);
        assertDecimal("130", balance(kopi), "cashier adjustment must not apply");

        // Cashier may read the stock list and history.
        assertEquals("OK", stockStatusOf(listStock(cashier, null), kopi));
        api.send(get(stockItems(cashier) + "/" + kopi + "/movements").header(AUTH, cashier.bearer()), 200);

        // Cashier cannot manage ingredients or recipes: 403 from the /merchant/** security rule.
        assertStatus(403, api.call(post(adminItems(cashier)).header(AUTH, cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Gula", "g", "5", "10"))));
        assertStatus(403, api.call(put(adminItems(cashier) + "/" + kopi).header(AUTH, cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Biji Kopi", "g", "99", null))));
        assertStatus(403, api.call(delete(adminItems(cashier) + "/" + kopi).header(AUTH, cashier.bearer())));
        assertStatus(403, putRecipe(cashier, productRecipeUrl(cashier, productId), recipeJson(kopi, "18")));
        assertEquals(0, recipeLinesOfProduct(productId));
        assertEquals(1, count("SELECT count(*) FROM inventory_items WHERE tenant_id = ?::uuid",
                owner.tenantId().toString()));
        assertDecimal("10", api.jdbc().queryForObject(
                "SELECT reorder_level FROM inventory_items WHERE id = ?::uuid", BigDecimal.class, kopi),
                "cashier update must not change the item");

        // Owner ADJUSTMENT works in both directions.
        MvcResult down = recordMovement(owner, kopi, "ADJUSTMENT", "-30", "Stok opname turun");
        assertDecimal("-30", decimal(ApiTestSupport.read(down, "$.data.quantityDelta")), "signed negative delta");
        recordMovement(owner, kopi, "ADJUSTMENT", "10", "Stok opname naik");
        assertDecimal("110", balance(kopi), "130 - 30 + 10");
        assertEquals(5, movementsOfItem(kopi));
        assertLedgerMatchesBalance(kopi);
    }

    // ------------------------------------------------------------------ 9. movement idempotency and validation

    @Test
    void movementIdempotencyReplayConflictAndValidationRules() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-idem");
        String kopi = createIngredient(owner, "Biji Kopi", "g", "10", "100");
        String gula = createIngredient(owner, "Gula", "g", "10", "100");
        String key = "restock-" + UUID.randomUUID();
        String body = movementJson("RESTOCK", "50", "Belanja", key);

        MvcResult first = postMovement(owner, kopi, body);
        assertStatus(201, first);
        String movementId = ApiTestSupport.read(first, "$.data.id");
        assertDecimal("150", balance(kopi), "100 + 50");

        MvcResult replay = postMovement(owner, kopi, body);
        assertStatus(201, replay);
        assertEquals(movementId, ApiTestSupport.read(replay, "$.data.id"), "same movement returned");
        assertDecimal("150", balance(kopi), "replay does not apply twice");
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE idempotency_key = ?", key));

        assertStatus(409, postMovement(owner, kopi, movementJson("RESTOCK", "75", "Belanja", key)));
        assertDecimal("150", balance(kopi), "conflicting reuse of the key applies nothing");
        assertStatus(409, postMovement(owner, gula, body));
        assertDecimal("100", balance(gula), "the key cannot be reused for another ingredient");

        // Rejected inputs (all 400) leave balance and ledger untouched.
        assertStatus(400, postMovement(owner, kopi, movementJson("SALE", "5", "Manual", "k-" + UUID.randomUUID())));
        assertStatus(400, postMovement(owner, kopi, movementJson("WASTE", "5", null, "k-" + UUID.randomUUID())));
        assertStatus(400, postMovement(owner, kopi, movementJson("WASTE", "5", "   ", "k-" + UUID.randomUUID())));
        assertStatus(400, postMovement(owner, kopi,
                movementJson("ADJUSTMENT", "5", null, "k-" + UUID.randomUUID())));
        assertStatus(400, postMovement(owner, kopi, movementJson("RESTOCK", "0", "Nol", "k-" + UUID.randomUUID())));
        assertStatus(400, postMovement(owner, kopi, movementJson("RESTOCK", "-5", "Minus", "k-" + UUID.randomUUID())));
        assertStatus(400, postMovement(owner, kopi, movementJson("WASTE", "-5", "Minus", "k-" + UUID.randomUUID())));
        assertStatus(400, postMovement(owner, kopi,
                movementJson("ADJUSTMENT", "0", "Nol", "k-" + UUID.randomUUID())));
        assertStatus(400, postMovement(owner, kopi, "{\"type\":\"RESTOCK\",\"quantity\":5}"));
        assertDecimal("150", balance(kopi), "no rejected request changed the balance");
        assertEquals(2, movementsOfItem(kopi), "initial restock + the single accepted restock");

        // A blank RESTOCK reason defaults to "Restock".
        MvcResult defaulted = recordMovement(owner, kopi, "RESTOCK", "1", null);
        assertEquals("Restock", ApiTestSupport.read(defaulted, "$.data.reason"));
        assertDecimal("151", balance(kopi), "150 + 1");
        assertLedgerMatchesBalance(kopi);
        assertLedgerMatchesBalance(gula);
    }

    // ------------------------------------------------------------------ 10. recipe replacement

    @Test
    void recipeReplaceIsRepeatableClearableValidatedAndReadable() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-recipe");
        MerchantFixture other = api.onboardOwner("inv-recipe-other");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Kopi Susu", PRICE);
        String otherProductId = api.createProduct(owner, api.createCategory(owner, "Lain"), "Teh", PRICE);
        String kopi = createIngredient(owner, "Biji Kopi", "g", "10", "100");
        String susu = createIngredient(owner, "Susu", "ml", "10", "100");
        String foreign = createIngredient(other, "Biji Kopi", "g", "10", "100");
        String url = productRecipeUrl(owner, productId);

        MvcResult created = putRecipe(owner, url, recipeJson(kopi, "18", susu, "120.5"));
        assertStatus(200, created);
        assertEquals(2, recipeLinesOfProduct(productId));

        // PUT of the identical recipe again re-inserts the same product/item pairs.
        assertStatus(200, putRecipe(owner, url, recipeJson(kopi, "18", susu, "120.5")));
        assertEquals(2, recipeLinesOfProduct(productId), "no duplicates after an identical replace");

        MvcResult read = api.send(get(url).header(AUTH, owner.bearer()), 200);
        assertEquals(2, readList(read, "$.data.lines[*].inventoryItemId").size());
        assertEquals(List.of("Biji Kopi"), readList(read, "$.data.lines[?(@.inventoryItemId=='" + kopi + "')].inventoryItemName"));
        assertEquals(List.of("g"), readList(read, "$.data.lines[?(@.inventoryItemId=='" + kopi + "')].unit"));
        assertDecimal("18", decimal(readList(read,
                "$.data.lines[?(@.inventoryItemId=='" + kopi + "')].quantityPerUnit").get(0)), "kopi quantity");
        assertEquals(List.of("Susu"), readList(read, "$.data.lines[?(@.inventoryItemId=='" + susu + "')].inventoryItemName"));
        assertEquals(List.of("ml"), readList(read, "$.data.lines[?(@.inventoryItemId=='" + susu + "')].unit"));
        assertDecimal("120.5", decimal(readList(read,
                "$.data.lines[?(@.inventoryItemId=='" + susu + "')].quantityPerUnit").get(0)), "susu quantity");

        // Replacing with a different set swaps the lines.
        assertStatus(200, putRecipe(owner, url, recipeJson(kopi, "20")));
        assertEquals(1, recipeLinesOfProduct(productId));
        assertDecimal("20", api.jdbc().queryForObject(
                "SELECT quantity_per_unit FROM recipe_lines WHERE product_id = ?::uuid", BigDecimal.class, productId),
                "replaced quantity");

        // Invalid requests are rejected before anything is deleted: the recipe stays [kopi 20].
        assertStatus(400, putRecipe(owner, url, recipeJson(kopi, "5", kopi, "6")));
        assertStatus(404, putRecipe(owner, url, recipeJson(UUID.randomUUID().toString(), "5")));
        assertStatus(404, putRecipe(owner, url, recipeJson(foreign, "5")));
        assertStatus(400, putRecipe(owner, url, recipeJson(kopi, "0")));
        assertStatus(400, putRecipe(owner, url, recipeJson(kopi, "-1")));
        assertStatus(400, putRecipe(owner, url, "{}"));
        assertEquals(1, recipeLinesOfProduct(productId), "failed replaces must keep the previous recipe");
        assertDecimal("20", api.jdbc().queryForObject(
                "SELECT quantity_per_unit FROM recipe_lines WHERE product_id = ?::uuid", BigDecimal.class, productId),
                "recipe unchanged by rejected requests");
        assertStatus(404, putRecipe(owner, productRecipeUrl(owner, UUID.randomUUID().toString()),
                recipeJson(kopi, "5")));

        // An empty list clears the recipe.
        assertStatus(200, putRecipe(owner, url, "{\"lines\":[]}"));
        assertEquals(0, recipeLinesOfProduct(productId));
        assertTrue(readList(api.send(get(url).header(AUTH, owner.bearer()), 200), "$.data.lines[*]").isEmpty());
        assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE tenant_id = ?::uuid AND action = 'FNB_RECIPE_CHANGED' "
                + "AND details = 'lines=0'", owner.tenantId().toString()));

        // Option recipe: same rules, and the option must belong to the given group and product.
        String groupId = createGroup(owner, productId);
        String otherGroupId = createGroup(owner, productId);
        String optionId = createOption(owner, productId, groupId, "Extra Shot", "5000");
        String optionUrl = optionRecipeUrl(owner, productId, groupId, optionId);
        assertStatus(200, putRecipe(owner, optionUrl, recipeJson(kopi, "18")));
        assertStatus(200, putRecipe(owner, optionUrl, recipeJson(kopi, "18")));
        assertEquals(1, count("SELECT count(*) FROM recipe_lines WHERE product_option_id = ?::uuid", optionId));
        MvcResult optionRead = api.send(get(optionUrl).header(AUTH, owner.bearer()), 200);
        assertEquals(List.of("Biji Kopi"), readList(optionRead, "$.data.lines[*].inventoryItemName"));
        assertStatus(404, putRecipe(owner, optionRecipeUrl(owner, productId, otherGroupId, optionId),
                recipeJson(kopi, "18")));
        assertStatus(404, putRecipe(owner, optionRecipeUrl(owner, otherProductId, groupId, optionId),
                recipeJson(kopi, "18")));
        assertStatus(400, putRecipe(owner, optionUrl, recipeJson(kopi, "1", kopi, "2")));
        assertStatus(404, putRecipe(owner, optionUrl, recipeJson(foreign, "1")));
        assertEquals(1, count("SELECT count(*) FROM recipe_lines WHERE product_option_id = ?::uuid", optionId));
        assertStatus(200, putRecipe(owner, optionUrl, "{\"lines\":[]}"));
        assertEquals(0, count("SELECT count(*) FROM recipe_lines WHERE product_option_id = ?::uuid", optionId));
    }

    // ------------------------------------------------------------------ 11. tenant isolation

    @Test
    void anotherTenantCannotReadChangeDeactivateMoveOrRecipeAnIngredient() throws Exception {
        MerchantFixture a = api.onboardOwner("inv-tenantA");
        MerchantFixture b = api.onboardOwner("inv-tenantB");
        String itemA = createIngredient(a, "Biji Kopi", "g", "10", "100");
        // Names are unique per outlet, not globally: tenant B may use the same name.
        String itemB = createIngredient(b, "Biji Kopi", "g", "10", "100");
        String productB = api.createProduct(b, api.createCategory(b, "Minuman"), "Kopi", PRICE);

        // B principal against B's own outlet but A's ingredient id: nothing reveals that the record exists.
        assertStatus(404, api.call(get(stockItems(b) + "/" + itemA + "/movements").header(AUTH, b.bearer())));
        assertStatus(404, api.call(put(adminItems(b) + "/" + itemA).header(AUTH, b.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Dibajak", "g", "1", null))));
        assertStatus(404, api.call(delete(adminItems(b) + "/" + itemA).header(AUTH, b.bearer())));
        assertStatus(404, postMovement(b, itemA, movementJson("RESTOCK", "999", "Curang", "k-" + UUID.randomUUID())));
        assertStatus(404, postMovement(b, itemA, movementJson("WASTE", "5", "Curang", "k-" + UUID.randomUUID())));
        assertStatus(404, postMovement(b, itemA,
                movementJson("ADJUSTMENT", "-5", "Curang", "k-" + UUID.randomUUID())));
        assertStatus(404, putRecipe(b, productRecipeUrl(b, productB), recipeJson(itemA, "18")));
        assertEquals(0, recipeLinesOfProduct(productB));

        // B principal against A's outlet path: 404 on both the cashier and the merchant surface.
        assertStatus(404, api.call(get(stockItems(a)).header(AUTH, b.bearer())));
        assertStatus(404, api.call(get(stockItems(a) + "/" + itemA + "/movements").header(AUTH, b.bearer())));
        assertStatus(404, api.call(post(stockItems(a) + "/" + itemA + "/movements").header(AUTH, b.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(movementJson("RESTOCK", "999", "Curang", "k-" + UUID.randomUUID()))));
        assertStatus(404, api.call(post(adminItems(a)).header(AUTH, b.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Susup", "g", "1", "1"))));
        assertStatus(404, api.call(put(adminItems(a) + "/" + itemA).header(AUTH, b.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Dibajak", "g", "1", null))));
        assertStatus(404, api.call(delete(adminItems(a) + "/" + itemA).header(AUTH, b.bearer())));

        // A's data is untouched.
        assertDecimal("100", balance(itemA), "A balance untouched");
        assertEquals(1, movementsOfItem(itemA), "no movement was written for A's item");
        assertEquals("Biji Kopi", api.jdbc().queryForObject(
                "SELECT name FROM inventory_items WHERE id = ?::uuid", String.class, itemA));
        assertTrue(api.jdbc().queryForObject(
                "SELECT active FROM inventory_items WHERE id = ?::uuid", Boolean.class, itemA), "still active");
        assertEquals(1, count("SELECT count(*) FROM inventory_items WHERE tenant_id = ?::uuid",
                a.tenantId().toString()));

        // Each list only contains its own data.
        assertEquals(List.of(itemA), readList(listStock(a, null), "$.data[*].id"));
        assertEquals(List.of(itemB), readList(listStock(b, null), "$.data[*].id"));
    }

    // ------------------------------------------------------------------ 12. unique name and unit lock

    @Test
    void ingredientNameIsUniquePerOutletAndUnitIsLockedOnceMovementsExist() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-unique");

        String kopi = createIngredient(owner, "Biji Kopi", "g", "10", "100");
        // GlobalExceptionHandler maps DataIntegrityViolationException to 409.
        MvcResult duplicate = api.call(post(adminItems(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Biji Kopi", "kg", "1", "5")));
        assertStatus(409, duplicate);
        assertEquals(1, count("SELECT count(*) FROM inventory_items WHERE outlet_id = ?::uuid AND name = 'Biji Kopi'",
                owner.outletId().toString()), "the duplicate must not leave a row");
        assertEquals(1, count("SELECT count(*) FROM inventory_balances WHERE outlet_id = ?::uuid",
                owner.outletId().toString()), "and no orphan balance");
        assertEquals(List.of(kopi), readList(listStock(owner, null), "$.data[*].id"));

        // Renaming onto an existing name is the same violation.
        String gula = createIngredient(owner, "Gula", "g", "10", null);
        assertStatus(409, api.call(put(adminItems(owner) + "/" + gula).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Biji Kopi", "g", "10", null))));
        assertEquals("Gula", api.jdbc().queryForObject(
                "SELECT name FROM inventory_items WHERE id = ?::uuid", String.class, gula));

        // Unit change is rejected once a movement exists (kopi has its initial restock).
        assertStatus(400, api.call(put(adminItems(owner) + "/" + kopi).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Biji Kopi", "kg", "10", null))));
        assertEquals("g", api.jdbc().queryForObject(
                "SELECT unit FROM inventory_items WHERE id = ?::uuid", String.class, kopi));
        // Other fields remain editable with an unchanged unit, and initialQuantity is ignored on update.
        MvcResult renamed = api.send(put(adminItems(owner) + "/" + kopi).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Kopi Arabika", "g", "25", "999")), 200);
        assertEquals("Kopi Arabika", ApiTestSupport.read(renamed, "$.data.name"));
        assertDecimal("25", decimal(ApiTestSupport.read(renamed, "$.data.reorderLevel")), "reorder level updated");
        assertDecimal("100", balance(kopi), "initialQuantity is ignored on update");

        // Without any movement the unit may still be changed.
        MvcResult unitChanged = api.send(put(adminItems(owner) + "/" + gula).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Gula", "kg", "10", null)), 200);
        assertEquals("kg", ApiTestSupport.read(unitChanged, "$.data.unit"));
        assertEquals("OUT", ApiTestSupport.read(unitChanged, "$.data.stockStatus"));
    }

    // ------------------------------------------------------------------ 13. deactivated ingredient

    @Test
    void deactivatedIngredientLeavesListAndNewRecipesButExistingRecipeLineStillDeducts() throws Exception {
        MerchantFixture owner = api.onboardOwner("inv-deactivate");
        String category = api.createCategory(owner, "Minuman");
        String productId = api.createProduct(owner, category, "Kopi", PRICE);
        String secondProductId = api.createProduct(owner, category, "Espresso", PRICE);
        String kopi = createIngredient(owner, "Biji Kopi", "g", "10", "100");
        String susu = createIngredient(owner, "Susu", "ml", "10", "100");
        setRecipe(owner, productRecipeUrl(owner, productId), recipeJson(kopi, "18"));

        MvcResult deactivated = api.send(delete(adminItems(owner) + "/" + kopi).header(AUTH, owner.bearer()), 200);
        assertEquals("false", ApiTestSupport.read(deactivated, "$.data.active"));
        assertFalse(api.jdbc().queryForObject(
                "SELECT active FROM inventory_items WHERE id = ?::uuid", Boolean.class, kopi));

        // Gone from the list, cannot be updated, moved, or added to a new recipe.
        assertEquals(List.of(susu), readList(listStock(owner, null), "$.data[*].id"));
        assertStatus(404, putRecipe(owner, productRecipeUrl(owner, secondProductId), recipeJson(kopi, "5")));
        assertEquals(0, recipeLinesOfProduct(secondProductId));
        assertStatus(404, postMovement(owner, kopi, movementJson("RESTOCK", "5", "Belanja", "k-" + UUID.randomUUID())));
        assertStatus(404, api.call(put(adminItems(owner) + "/" + kopi).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(itemJson("Biji Kopi", "g", "10", null))));
        assertStatus(404, api.call(get(stockItems(owner) + "/" + kopi + "/movements").header(AUTH, owner.bearer())));

        // Documented TASK-061 behaviour: the existing recipe line is kept and still deducts on payment.
        assertEquals(1, recipeLinesOfProduct(productId));
        MvcResult recipe = api.send(get(productRecipeUrl(owner, productId)).header(AUTH, owner.bearer()), 200);
        assertEquals(List.of(kopi), readList(recipe, "$.data.lines[*].inventoryItemId"));

        String orderId = createOrder(owner, productId, 1);
        MvcResult paid = payCash(owner, orderId, "pay-" + UUID.randomUUID());
        assertTrue(warnings(paid).isEmpty(), warnings(paid).toString());
        assertEquals("PAID", ApiTestSupport.read(getOrder(owner, orderId), "$.data.paymentStatus"));
        assertEquals(1, saleMovements(orderId), "the deactivated ingredient is still deducted");
        assertDecimal("82", balance(kopi), "100 - 18");
        assertLedgerMatchesBalance(kopi);
    }
}
