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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * M6 (TASK-079): V9 schema, moving-average unit cost, per-item COGS, inactive ingredients, refund cost copy,
 * batch stock count and the owner report additions, against real PostgreSQL and the real security chain.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FnbCostAndStockCountIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

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

    // ------------------------------------------------------------------ paths and helpers

    private static String adminItems(MerchantFixture m) {
        return "/api/v1/merchant/outlets/" + m.outletId() + "/fnb/inventory/items";
    }

    private static String stockItems(MerchantFixture m) {
        return "/api/v1/cashier/outlets/" + m.outletId() + "/fnb/inventory/items";
    }

    private static String stockCounts(MerchantFixture m) {
        return "/api/v1/merchant/outlets/" + m.outletId() + "/fnb/inventory/stock-counts";
    }

    private static String orders(MerchantFixture m) {
        return "/api/v1/cashier/outlets/" + m.outletId() + "/fnb/orders";
    }

    private static String optionGroups(MerchantFixture m) {
        return "/api/v1/merchant/outlets/" + m.outletId() + "/fnb/option-groups";
    }

    private static String productUrl(MerchantFixture m, String productId) {
        return "/api/v1/merchant/outlets/" + m.outletId() + "/fnb/products/" + productId;
    }

    private static BigDecimal dec(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static void assertDec(String expected, Object actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(dec(actual)),
                message + ": expected " + expected + " but was " + actual);
    }

    private static void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private static Map<String, Object> single(MvcResult result, String path) throws Exception {
        List<Map<String, Object>> found = rows(result, path);
        assertEquals(1, found.size(), path);
        return found.get(0);
    }

    private String ingredient(MerchantFixture owner, String name, String unit) throws Exception {
        return ApiTestSupport.read(api.send(post(adminItems(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"unit\":\"" + unit + "\",\"reorderLevel\":1}"), 201), "$.data.id");
    }

    private static String movementJson(String type, String quantity, String reason, String unitCost, String key) {
        return "{\"type\":\"" + type + "\",\"quantity\":" + quantity
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + (unitCost == null ? "" : ",\"unitCost\":" + unitCost)
                + ",\"idempotencyKey\":\"" + key + "\"}";
    }

    private MvcResult movement(MerchantFixture actor, String itemId, String type, String quantity, String reason,
                               String unitCost) throws Exception {
        return api.call(post(stockItems(actor) + "/" + itemId + "/movements").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(movementJson(type, quantity, reason, unitCost, "mv-" + UUID.randomUUID())));
    }

    private MvcResult restock(MerchantFixture actor, String itemId, String quantity, String unitCost) throws Exception {
        MvcResult result = movement(actor, itemId, "RESTOCK", quantity, "Belanja", unitCost);
        assertStatus(201, result);
        return result;
    }

    private void setRecipe(MerchantFixture owner, String url, String... itemAndQuantity) throws Exception {
        StringBuilder json = new StringBuilder("{\"lines\":[");
        for (int i = 0; i < itemAndQuantity.length; i += 2) {
            json.append(i > 0 ? "," : "").append("{\"inventoryItemId\":\"").append(itemAndQuantity[i])
                    .append("\",\"quantityPerUnit\":").append(itemAndQuantity[i + 1]).append('}');
        }
        api.send(put(url).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.append("]}").toString()), 200);
    }

    /** Product with a recipe of (ingredient, quantityPerUnit) pairs. */
    private String productWithRecipe(MerchantFixture owner, String name, String price, String... recipe)
            throws Exception {
        String productId = api.createProduct(owner, api.createCategory(owner, "Kat " + name), name, price);
        if (recipe.length > 0) {
            setRecipe(owner, productUrl(owner, productId) + "/recipe", recipe);
        }
        return productId;
    }

    /** Order of one product with an optional option id; returns the order id. */
    private String order(MerchantFixture actor, String productId, int quantity, String optionId) throws Exception {
        String body = "{\"serviceType\":\"TAKEAWAY\",\"customerLabel\":\"Budi\",\"items\":[{\"productId\":\""
                + productId + "\",\"quantity\":" + quantity + ",\"optionIds\":["
                + (optionId == null ? "" : "\"" + optionId + "\"") + "]}]}";
        return ApiTestSupport.read(api.send(post(orders(actor)).header(AUTH, actor.bearer())
                .header("Idempotency-Key", "pos-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(body), 201), "$.data.orderId");
    }

    private String pay(MerchantFixture actor, String orderId) throws Exception {
        api.ensureShift(actor);
        api.payCashExact(actor, orderId);
        return orderId;
    }

    private MvcResult refund(MerchantFixture owner, String orderId) throws Exception {
        MvcResult result = api.call(post("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/orders/" + orderId
                + "/refund").header(AUTH, owner.bearer()).header("Idempotency-Key", "refund-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Salah pesan\"}"));
        assertStatus(200, result);
        return result;
    }

    private MvcResult report(MerchantFixture owner) throws Exception {
        LocalDate today = LocalDate.now(JAKARTA);
        return api.send(get("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/reports/sales")
                .param("startDate", today.minusDays(1).toString()).param("endDate", today.plusDays(1).toString())
                .header(AUTH, owner.bearer()), 200);
    }

    private BigDecimal balance(String itemId) {
        return api.jdbc().queryForObject(
                "SELECT quantity_on_hand FROM inventory_balances WHERE inventory_item_id = ?::uuid", BigDecimal.class, itemId);
    }

    private BigDecimal average(String itemId) {
        return api.jdbc().queryForObject(
                "SELECT average_unit_cost FROM inventory_balances WHERE inventory_item_id = ?::uuid", BigDecimal.class,
                itemId);
    }

    private BigDecimal movementCost(String orderId, String itemId, String type) {
        return api.jdbc().queryForObject("SELECT unit_cost FROM inventory_movements WHERE order_id = ?::uuid "
                + "AND inventory_item_id = ?::uuid AND movement_type = ?", BigDecimal.class, orderId, itemId, type);
    }

    private int count(String sql, Object... args) {
        Integer value = api.jdbc().queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private BigDecimal itemCost(String orderId) {
        return api.jdbc().queryForObject("SELECT cost_amount FROM order_items WHERE sales_order_id = ?::uuid",
                BigDecimal.class, orderId);
    }

    private BigDecimal netSales(String orderId) {
        return api.jdbc().queryForObject("SELECT subtotal - discount_amount FROM sales_orders WHERE id = ?::uuid",
                BigDecimal.class, orderId);
    }

    // ------------------------------------------------------------------ V9 + weighted average + COGS

    @Test
    void v9AppliesAndWeightedAverageFeedsSaleUnitCostAndItemCostForProductAndOptionRecipes() throws Exception {
        assertEquals(3, count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND "
                + "((table_name = 'inventory_balances' AND column_name = 'average_unit_cost') OR "
                + "(table_name = 'inventory_movements' AND column_name = 'unit_cost') OR "
                + "(table_name = 'order_items' AND column_name = 'cost_amount'))"));
        assertEquals(1, count("SELECT count(*) FROM flyway_schema_history WHERE version = '9' AND success"));

        MerchantFixture owner = api.onboardOwner("cost-avg");
        String biji = ingredient(owner, "Biji Kopi", "g");
        String susu = ingredient(owner, "Susu", "ml");
        restock(owner, biji, "1000", "10");
        MvcResult second = restock(owner, biji, "3000", "14");
        assertDec("14", ApiTestSupport.read(second, "$.data.unitCost"), "RESTOCK movement keeps the entered price");
        restock(owner, susu, "500", "8");
        assertDec("13", average(biji), "(1000*10 + 3000*14) / 4000");
        assertDec("8", average(susu), "first price");
        assertDec("13", single(api.send(get(stockItems(owner)).header(AUTH, owner.bearer()), 200),
                "$.data[?(@.id=='" + biji + "')]").get("averageUnitCost"), "owner sees the average in the list");

        String productId = productWithRecipe(owner, "Kopi Susu", "25000", biji, "18", susu, "100");
        String groupId = ApiTestSupport.read(api.send(post(optionGroups(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Tambahan\",\"required\":false,"
                        + "\"minimumSelections\":0,\"maximumSelections\":3,\"displayOrder\":1}"), 201), "$.data.id");
        api.send(put(productUrl(owner, productId) + "/option-groups").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"groupIds\":[\"" + groupId + "\"]}"), 200);
        String shot = ApiTestSupport.read(api.send(post(optionGroups(owner) + "/" + groupId + "/options")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Extra Shot\",\"priceAdjustment\":5000,\"available\":true,\"displayOrder\":1}"), 201),
                "$.data.id");
        setRecipe(owner, optionGroups(owner) + "/" + groupId + "/options/" + shot + "/recipe", biji, "18");

        String orderId = pay(owner, order(owner, productId, 2, shot));

        assertDec("3928", balance(biji), "4000 - 2 x (18 + 18)");
        assertDec("13", movementCost(orderId, biji, "SALE"), "SALE unit cost = average at sale time");
        assertDec("8", movementCost(orderId, susu, "SALE"), "SALE unit cost = average at sale time");
        // 2 x (18*13 + 100*8) + 2 x (18*13) = 2068 + 468
        assertDec("2536.00", itemCost(orderId), "cost_amount of the item");
        assertDec("13", average(biji), "SALE does not change the average");

        // RESTOCK without a price keeps the average and records it on the movement (accepted deviation).
        restock(owner, biji, "100", null);
        assertDec("13", average(biji), "average unchanged");
        assertDec("13", api.jdbc().queryForObject("SELECT unit_cost FROM inventory_movements WHERE inventory_item_id = "
                + "?::uuid AND movement_type = 'RESTOCK' AND quantity_delta = 100", BigDecimal.class, biji),
                "unpriced restock records the balance average");
        // WASTE records the average; a price outside RESTOCK is rejected.
        assertStatus(201, movement(owner, biji, "WASTE", "10", "Tumpah", null));
        assertDec("13", api.jdbc().queryForObject("SELECT unit_cost FROM inventory_movements WHERE inventory_item_id = "
                + "?::uuid AND movement_type = 'WASTE'", BigDecimal.class, biji), "WASTE unit cost");
        assertStatus(400, movement(owner, biji, "WASTE", "1", "Tumpah", "5"));
        assertDec("13", average(biji), "rejected request changed nothing");
    }

    @Test
    void deactivatedIngredientIsNotDeductedAndAddsNoCost() throws Exception {
        MerchantFixture owner = api.onboardOwner("cost-inactive");
        String a = ingredient(owner, "Tepung", "g");
        String b = ingredient(owner, "Keju", "g");
        restock(owner, a, "1000", "5");
        restock(owner, b, "100", "7");
        String mixed = productWithRecipe(owner, "Roti Keju", "20000", a, "10", b, "20");
        String onlyInactive = productWithRecipe(owner, "Keju Saja", "9000", b, "20");
        api.send(delete(adminItems(owner) + "/" + b).header(AUTH, owner.bearer()), 200);

        String first = pay(owner, order(owner, mixed, 3, null));
        assertDec("970", balance(a), "active ingredient deducted 3 x 10");
        assertDec("100", balance(b), "inactive ingredient untouched");
        assertEquals(0, count("SELECT count(*) FROM inventory_movements WHERE order_id = ?::uuid AND inventory_item_id "
                + "= ?::uuid", first, b));
        assertDec("150.00", itemCost(first), "only the active ingredient adds cost: 3 x 10 x 5");

        String second = pay(owner, order(owner, onlyInactive, 1, null));
        assertDec("100", balance(b), "still untouched");
        assertNull(itemCost(second), "an item with no active recipe line has no cost");
    }

    @Test
    void ownerRefundCopiesTheSaleUnitCostToTheReversal() throws Exception {
        MerchantFixture owner = api.onboardOwner("cost-refund");
        String item = ingredient(owner, "Gula", "g");
        restock(owner, item, "1000", "12.5");
        String productId = productWithRecipe(owner, "Teh Manis", "8000", item, "4");
        String orderId = pay(owner, order(owner, productId, 2, null));
        assertDec("12.5", movementCost(orderId, item, "SALE"), "sale unit cost");
        // The average moves after the sale; the reversal must still use the sale's cost.
        restock(owner, item, "1000", "20");
        assertTrue(average(item).compareTo(new BigDecimal("12.5")) > 0, "average moved");

        refund(owner, orderId);

        assertDec("12.5", movementCost(orderId, item, "SALE_REVERSAL"), "reversal unit cost = sale unit cost");
        assertDec("2000", balance(item), "1000 - 8 + 1000 + 8 after the reversal");
    }

    // ------------------------------------------------------------------ cost visibility

    @Test
    void cashierMayRestockWithAPriceButNeverSeesCostsWhileOwnerDoes() throws Exception {
        MerchantFixture owner = api.onboardOwner("cost-vis");
        MerchantFixture cashier = api.onboardCashier(owner, "cost-vis");
        String item = ingredient(owner, "Beras", "kg");
        restock(owner, item, "100", "10");

        MvcResult cashierRestock = restock(cashier, item, "100", "20");
        assertNull(JsonPath.<Map<String, Object>>read(cashierRestock.getResponse().getContentAsString(), "$.data")
                .get("unitCost"), "cashier response has no unit cost");
        assertDec("15", average(item), "(100*10 + 100*20) / 200 even when a cashier enters the price");

        Map<String, Object> cashierItem = single(api.send(get(stockItems(cashier)).header(AUTH, cashier.bearer()), 200),
                "$.data[?(@.id=='" + item + "')]");
        assertNull(cashierItem.get("averageUnitCost"));
        for (Map<String, Object> row : rows(api.send(get(stockItems(cashier) + "/" + item + "/movements")
                .header(AUTH, cashier.bearer()), 200), "$.data[*]")) {
            assertNull(row.get("unitCost"), "cashier movement row " + row);
        }

        assertDec("15", single(api.send(get(stockItems(owner)).header(AUTH, owner.bearer()), 200),
                "$.data[?(@.id=='" + item + "')]").get("averageUnitCost"), "owner sees the average");
        Map<String, Object> ownerRestock = single(api.send(get(stockItems(owner) + "/" + item + "/movements")
                .header(AUTH, owner.bearer()), 200), "$.data[?(@.reason=='Belanja' && @.unitCost==20)]");
        assertNotNull(ownerRestock.get("unitCost"));
    }

    // ------------------------------------------------------------------ stock count

    private static String countJson(String key, String note, String... itemAndCounted) {
        StringBuilder json = new StringBuilder("{\"idempotencyKey\":\"" + key + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + ",\"lines\":[");
        for (int i = 0; i < itemAndCounted.length; i += 2) {
            json.append(i > 0 ? "," : "").append("{\"inventoryItemId\":\"").append(itemAndCounted[i])
                    .append("\",\"countedQuantity\":").append(itemAndCounted[i + 1]).append('}');
        }
        return json.append("]}").toString();
    }

    private MvcResult postCount(MerchantFixture actor, String body) throws Exception {
        return api.call(post(stockCounts(actor)).header(AUTH, actor.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void stockCountAdjustsInOneBatchReplaysConflictsAndIsOwnerAndTenantScoped() throws Exception {
        MerchantFixture owner = api.onboardOwner("count-main");
        MerchantFixture cashier = api.onboardCashier(owner, "count-main");
        MerchantFixture other = api.onboardOwner("count-other");
        String x = ingredient(owner, "Bahan X", "g");
        String y = ingredient(owner, "Bahan Y", "g");
        String foreign = ingredient(other, "Bahan Asing", "g");
        restock(owner, x, "100", "5");
        restock(owner, y, "50", "4");

        String key = "count-" + UUID.randomUUID();
        String body = countJson(key, null, x, "80.5", y, "50");
        MvcResult first = postCount(owner, body);
        assertStatus(201, first);
        Map<String, Object> lineX = single(first, "$.data.lines[?(@.inventoryItemId=='" + x + "')]");
        assertDec("100", lineX.get("previousQuantity"), "previous");
        assertDec("80.5", lineX.get("countedQuantity"), "counted");
        assertDec("-19.5", lineX.get("difference"), "difference");
        assertNotNull(lineX.get("movementId"));
        Map<String, Object> lineY = single(first, "$.data.lines[?(@.inventoryItemId=='" + y + "')]");
        assertDec("0", lineY.get("difference"), "no difference");
        assertNull(lineY.get("movementId"), "no movement for a zero difference");
        assertDec("80.5", balance(x), "balance after the count");
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE movement_type = 'ADJUSTMENT' AND "
                + "inventory_item_id = ?::uuid AND reason = 'Stok opname' AND quantity_delta = -19.5 "
                + "AND unit_cost = 5", x), "ADJUSTMENT with the stock count reason and the average as unit cost");
        assertEquals(0, count("SELECT count(*) FROM inventory_movements WHERE movement_type = 'ADJUSTMENT' AND "
                + "inventory_item_id = ?::uuid", y));

        // Replay: same key and payload returns the same response and changes nothing.
        MvcResult replay = postCount(owner, body);
        assertStatus(201, replay);
        List<Map<String, Object>> firstLines = rows(first, "$.data.lines[*]");
        List<Map<String, Object>> replayLines = rows(replay, "$.data.lines[*]");
        assertEquals(firstLines.size(), replayLines.size());
        for (int i = 0; i < firstLines.size(); i++) {
            Map<String, Object> before = firstLines.get(i);
            Map<String, Object> again = replayLines.get(i);
            for (String field : List.of("inventoryItemId", "name", "unit", "movementId")) {
                assertEquals(before.get(field), again.get(field), field);
            }
            for (String field : List.of("previousQuantity", "countedQuantity", "difference")) {
                assertDec(dec(before.get(field)).toPlainString(), again.get(field), field);
            }
        }
        assertDec("80.5", balance(x), "replay does not apply again");
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE movement_type = 'ADJUSTMENT' AND "
                + "inventory_item_id = ?::uuid", x));

        // Same key, different payload.
        assertStatus(409, postCount(owner, countJson(key, null, x, "70", y, "50")));
        assertDec("80.5", balance(x), "conflict changes nothing");

        // Roles, tenant isolation and validation.
        assertStatus(403, postCount(cashier, countJson("count-" + UUID.randomUUID(), null, x, "1")));
        assertStatus(404, postCount(owner, countJson("count-" + UUID.randomUUID(), null, x, "1", foreign, "1")));
        assertDec("80.5", balance(x), "a rejected batch applies no line");
        assertDec("0", balance(foreign), "the other tenant's stock is untouched");
        assertStatus(400, postCount(owner, countJson("count-" + UUID.randomUUID(), null, x, "1", x, "2")));
        assertStatus(400, postCount(owner, countJson("count-" + UUID.randomUUID(), null)));
        assertStatus(400, postCount(owner, countJson("count-" + UUID.randomUUID(), null, x, "-1")));

        // A note extends the reason.
        assertStatus(201, postCount(owner, countJson("count-" + UUID.randomUUID(), "Akhir bulan", x, "80")));
        assertEquals(1, count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid AND "
                + "reason = 'Stok opname: Akhir bulan' AND quantity_delta = -0.5", x));
        assertDec("80", balance(x), "balance after the second count");
    }

    // ------------------------------------------------------------------ owner sales report

    @Test
    void salesReportShowsCogsGrossProfitRefundsHourlyCashiersAndShifts() throws Exception {
        MerchantFixture owner = api.onboardOwner("rep");
        MerchantFixture cashier = api.onboardCashier(owner, "rep");
        String item = ingredient(owner, "Beras", "g");
        restock(owner, item, "10000", "10");
        String nasi = productWithRecipe(owner, "Nasi", "10000", item, "5");
        String teh = productWithRecipe(owner, "Teh", "20000");

        api.openShift(owner, "0.00");
        api.openShift(cashier, "0.00");
        String o1 = pay(owner, order(owner, nasi, 2, null));
        String o2 = pay(cashier, order(cashier, nasi, 1, null));
        String o3 = pay(cashier, order(cashier, teh, 1, null));
        String o4 = pay(owner, order(owner, nasi, 1, null));
        refund(owner, o4);

        BigDecimal n1 = netSales(o1);
        BigDecimal n2 = netSales(o2);
        BigDecimal n3 = netSales(o3);
        BigDecimal cashierNet = n2.add(n3);
        BigDecimal net = n1.add(cashierNet);
        BigDecimal cashierTotal = api.jdbc().queryForObject("SELECT sum(total) FROM sales_orders WHERE id IN "
                + "(?::uuid, ?::uuid)", BigDecimal.class, o2, o3);
        // Counted cash equals what the cashier collected, so the shift closes without a variance.
        assertStatus(200, api.call(post("/api/v1/cashier/outlets/" + cashier.outletId() + "/fnb/shifts/current/close")
                .header(AUTH, cashier.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":" + cashierTotal.toPlainString() + "}")));

        MvcResult report = report(owner);
        Map<String, Object> summary = JsonPath.<Map<String, Object>>read(report.getResponse().getContentAsString(), "$.data.summary");
        assertEquals(3, ((Number) summary.get("transactionCount")).intValue());
        assertDec(net.toPlainString(), summary.get("netSales"), "net sales");
        assertDec("150.00", summary.get("costOfGoodsSold"), "COGS = 3 x 5 x 10");
        assertDec(net.subtract(new BigDecimal("150")).toPlainString(), summary.get("grossProfit"), "net sales - COGS");
        assertEquals(1, ((Number) summary.get("itemsWithoutCost")).intValue(), "the Teh item has no recipe");
        assertEquals(1, ((Number) summary.get("refundCount")).intValue());
        assertDec(api.jdbc().queryForObject("SELECT amount FROM payment_refunds WHERE sales_order_id = ?::uuid",
                BigDecimal.class, o4).toPlainString(), summary.get("refundAmount"), "refund amount");

        Map<String, Object> nasiRow = single(report, "$.data.topProducts[?(@.productId=='" + nasi + "')]");
        assertEquals(3, ((Number) nasiRow.get("quantity")).intValue());
        assertDec("30000", nasiRow.get("grossSales"), "gross sales");
        assertDec("150.00", nasiRow.get("costOfGoodsSold"), "product COGS");
        assertDec("29850", nasiRow.get("grossProfit"), "grossSales - COGS before discount");
        assertEquals(0, ((Number) nasiRow.get("itemsWithoutCost")).intValue());
        Map<String, Object> tehRow = single(report, "$.data.topProducts[?(@.productId=='" + teh + "')]");
        assertDec("0", tehRow.get("costOfGoodsSold"), "no cost known");
        assertDec("20000", tehRow.get("grossProfit"), "gross profit equals gross sales without cost");
        assertEquals(1, ((Number) tehRow.get("itemsWithoutCost")).intValue());

        // Hourly buckets follow the outlet zone (UTC+7), derived from the payments' own paid time.
        Map<Integer, Long> expectedCount = new TreeMap<>();
        Map<Integer, BigDecimal> expectedNet = new TreeMap<>();
        for (String orderId : List.of(o1, o2, o3)) {
            Timestamp paidAt = api.jdbc().queryForObject("SELECT paid_at FROM payments WHERE sales_order_id = ?::uuid",
                    Timestamp.class, orderId);
            int hour = paidAt.toInstant().atZone(JAKARTA).getHour();
            assertTrue(hour != paidAt.toInstant().atZone(ZoneOffset.UTC).getHour(), "the zone is not UTC");
            expectedCount.merge(hour, 1L, Long::sum);
            expectedNet.merge(hour, netSales(orderId), BigDecimal::add);
        }
        List<Map<String, Object>> hourly = rows(report, "$.data.hourly[*]");
        assertEquals(expectedCount.size(), hourly.size());
        int index = 0;
        for (Map.Entry<Integer, Long> entry : expectedCount.entrySet()) {
            Map<String, Object> bucket = hourly.get(index++);
            assertEquals(entry.getKey().intValue(), ((Number) bucket.get("hour")).intValue(), "ascending hour buckets");
            assertEquals(entry.getValue().longValue(), ((Number) bucket.get("transactionCount")).longValue());
            assertDec(expectedNet.get(entry.getKey()).toPlainString(), bucket.get("netSales"), "hour net sales");
        }

        // Cashiers: ordered by net sales desc; the owner's refunded sale is not counted.
        List<Map<String, Object>> cashiers = rows(report, "$.data.cashiers[*]");
        assertEquals(2, cashiers.size());
        assertEquals("Cashier rep", cashiers.get(0).get("fullName"));
        assertEquals(2, ((Number) cashiers.get(0).get("transactionCount")).intValue());
        assertDec(cashierNet.toPlainString(), cashiers.get(0).get("netSales"), "cashier net sales");
        assertEquals("Owner rep", cashiers.get(1).get("fullName"));
        assertEquals(1, ((Number) cashiers.get(1).get("transactionCount")).intValue());
        assertDec(n1.toPlainString(), cashiers.get(1).get("netSales"), "owner net sales");
        assertDec(summary.get("amountCollected").toString(), dec(cashiers.get(0).get("amountCollected"))
                .add(dec(cashiers.get(1).get("amountCollected"))), "collected amounts add up");

        // Shifts: newest first; the cashier shift is closed, the owner shift still open.
        List<Map<String, Object>> shifts = rows(report, "$.data.shifts[*]");
        assertEquals(2, shifts.size());
        assertEquals("Cashier rep", shifts.get(0).get("cashierName"));
        assertEquals("CLOSED", shifts.get(0).get("status"));
        assertNotNull(shifts.get(0).get("closedAt"));
        assertNotNull(shifts.get(0).get("shiftId"));
        assertEquals(2, ((Number) shifts.get(0).get("transactionCount")).intValue());
        assertDec(cashierNet.toPlainString(), shifts.get(0).get("netSales"), "cashier shift net sales");
        assertEquals("Owner rep", shifts.get(1).get("cashierName"));
        assertEquals("OPEN", shifts.get(1).get("status"));
        assertNull(shifts.get(1).get("closedAt"));
        assertEquals(1, ((Number) shifts.get(1).get("transactionCount")).intValue());
        assertDec(n1.toPlainString(), shifts.get(1).get("netSales"), "owner shift net sales");

    }
}
