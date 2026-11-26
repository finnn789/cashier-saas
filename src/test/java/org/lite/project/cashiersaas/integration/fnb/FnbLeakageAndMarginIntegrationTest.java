package org.lite.project.cashiersaas.integration.fnb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.enums.PlanCode;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lite.project.cashiersaas.integration.support.ApiTestSupport.assertStatus;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * TASK-084/085: ingredient leakage from stock counts and menu margins (HPP vs target), REST level, against real
 * PostgreSQL and the real security chain. The FnbCostAndStockCountIntegrationTest class holds the stock count basics.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FnbLeakageAndMarginIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;

    private ApiTestSupport api;
    private MerchantFixture free;
    private MerchantFixture gateOwner;
    private MerchantFixture gateCashier;

    @BeforeAll
    void setUpAll() throws Exception {
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        api.ensureFnbBusinessType();
        free = api.onboardOwner("lm-free", PlanCode.FREETIER);
        gateOwner = api.onboardOwner("lm-gate");
        gateCashier = api.onboardCashier(gateOwner, "lm-gate");
    }

    @AfterAll
    void cleanUp() {
        api.truncateTenantData();
    }

    // ------------------------------------------------------------------ helpers

    private static String merchant(MerchantFixture m) {
        return "/api/v1/merchant/outlets/" + m.outletId();
    }

    private static String stockItems(MerchantFixture m) {
        return "/api/v1/cashier/outlets/" + m.outletId() + "/fnb/inventory/items";
    }

    private static BigDecimal dec(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static void assertDec(String expected, Object actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(dec(actual)), message + ": expected " + expected + " but was " + actual);
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
        return ApiTestSupport.read(api.send(post(merchant(owner) + "/fnb/inventory/items").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"unit\":\"" + unit + "\",\"reorderLevel\":1}"), 201), "$.data.id");
    }

    private MvcResult movement(MerchantFixture actor, String itemId, String type, String quantity, String reason,
                               String unitCost) throws Exception {
        return api.call(post(stockItems(actor) + "/" + itemId + "/movements").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\",\"quantity\":" + quantity + ",\"reason\":\"" + reason + "\""
                        + (unitCost == null ? "" : ",\"unitCost\":" + unitCost)
                        + ",\"idempotencyKey\":\"mv-" + UUID.randomUUID() + "\"}"));
    }

    private MvcResult restock(MerchantFixture actor, String itemId, String quantity, String unitCost) throws Exception {
        MvcResult result = movement(actor, itemId, "RESTOCK", quantity, "Belanja", unitCost);
        assertStatus(201, result);
        return result;
    }

    /** Product with an optional one-line recipe (ingredient, quantityPerUnit). */
    private String product(MerchantFixture owner, String name, String price, String itemId, String perUnit) throws Exception {
        String productId = api.createProduct(owner, api.createCategory(owner, "Kat " + name), name, price);
        if (itemId != null) {
            api.send(put(merchant(owner) + "/fnb/products/" + productId + "/recipe").header(AUTH, owner.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[{\"inventoryItemId\":\"" + itemId
                            + "\",\"quantityPerUnit\":" + perUnit + "}]}"), 200);
        }
        return productId;
    }

    private void sell(MerchantFixture owner, String productId, int quantity) throws Exception {
        api.ensureShift(owner);
        api.payCashExact(owner, api.createPosOrder(owner, productId, quantity));
    }

    private String count(MerchantFixture owner, String... itemAndCounted) throws Exception {
        StringBuilder json = new StringBuilder("{\"idempotencyKey\":\"count-" + UUID.randomUUID() + "\",\"lines\":[");
        for (int i = 0; i < itemAndCounted.length; i += 2) {
            json.append(i > 0 ? "," : "").append("{\"inventoryItemId\":\"").append(itemAndCounted[i])
                    .append("\",\"countedQuantity\":").append(itemAndCounted[i + 1]).append('}');
        }
        return ApiTestSupport.read(api.send(post(merchant(owner) + "/fnb/inventory/stock-counts").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.append("]}").toString()), 201), "$.data.stockCountId");
    }

    private MvcResult leakage(MerchantFixture owner, String stockCountId) throws Exception {
        var request = get(merchant(owner) + "/fnb/reports/leakage").header(AUTH, owner.bearer());
        if (stockCountId != null) {
            request.param("stockCountId", stockCountId);
        }
        return api.send(request, 200);
    }

    private MvcResult menuMargins(MerchantFixture owner, boolean belowTargetOnly) throws Exception {
        return api.send(get(merchant(owner) + "/fnb/reports/menu-margins").param("belowTargetOnly", String.valueOf(belowTargetOnly))
                .header(AUTH, owner.bearer()), 200);
    }

    // ------------------------------------------------------------------ access and plan

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/merchant/outlets/%s/fnb/reports/leakage", "/api/v1/merchant/outlets/%s/fnb/inventory/stock-counts",
            "/api/v1/merchant/reports/leakage", "/api/v1/merchant/outlets/%s/fnb/reports/menu-margins"})
    void leakageAndMarginRoutesAreOwnerOnlyAndNeedThePro(String path) throws Exception {
        assertStatus(403, api.call(get(path.formatted(gateOwner.outletId())).header(AUTH, gateCashier.bearer())));

        MvcResult refused = api.call(get(path.formatted(free.outletId())).header(AUTH, free.bearer()));

        assertStatus(402, refused);
        assertEquals("Fitur ini tersedia di paket Pro.", ApiTestSupport.read(refused, "$.message"));
    }

    // ------------------------------------------------------------------ leakage

    @Test
    void leakageReportShowsShortageUsageVarianceAndLossPerCountPeriodAndTheHistoryKeepsZeroDifferenceLines() throws Exception {
        MerchantFixture owner = api.onboardOwner("lm-leak");
        String beras = ingredient(owner, "Beras", "g");
        String gula = ingredient(owner, "Gula", "g");
        restock(owner, beras, "1000", "10");
        restock(owner, gula, "100", "2");
        String nasi = product(owner, "Nasi", "10000", beras, "5");
        sell(owner, nasi, 3);
        sell(owner, nasi, 1); // 20 g of theoretical usage
        assertStatus(201, movement(owner, beras, "WASTE", "10", "Tumpah", null));

        String firstId = count(owner, beras, "950", gula, "100"); // ledger says 970 / 100

        MvcResult first = leakage(owner, null);
        assertEquals(firstId, ApiTestSupport.read(first, "$.data.stockCountId"), "the latest count is the default");
        Map<String, Object> berasLine = single(first, "$.data.lines[?(@.inventoryItemId=='" + beras + "')]");
        assertNull(berasLine.get("periodStart"), "never counted before");
        assertDec("970", berasLine.get("previousQuantity"), "ledger quantity before the count");
        assertDec("950", berasLine.get("countedQuantity"), "counted");
        assertDec("20", berasLine.get("shortage"), "previous - counted");
        assertDec("20", berasLine.get("theoreticalUsage"), "4 x 5 g");
        assertDec("10", berasLine.get("waste"), "waste");
        assertDec("1000", berasLine.get("restock"), "restock");
        assertDec("0", berasLine.get("manualAdjustment"), "manual adjustment");
        assertDec("100", berasLine.get("variancePct"), "20 / 20");
        assertDec("200", berasLine.get("lossAmount"), "20 x average 10");
        assertEquals(Boolean.TRUE, berasLine.get("flagged"));
        Map<String, Object> gulaLine = single(first, "$.data.lines[?(@.inventoryItemId=='" + gula + "')]");
        assertDec("0", gulaLine.get("shortage"), "no difference");
        assertNull(gulaLine.get("variancePct"), "no usage: a percentage is undefined");
        assertDec("0", gulaLine.get("lossAmount"), "no loss");
        assertEquals(Boolean.FALSE, gulaLine.get("flagged"));
        assertEquals(beras, ApiTestSupport.read(first, "$.data.lines[0].inventoryItemId"), "flagged lines first");
        assertDec("200", ApiTestSupport.read(first, "$.data.totalLoss"), "total loss");
        assertEquals("1", ApiTestSupport.read(first, "$.data.flaggedCount"));

        // Period two starts at the first count: the earlier waste and restock no longer count; a manual adjustment does.
        sell(owner, nasi, 2); // 10 g
        assertStatus(201, movement(owner, beras, "ADJUSTMENT", "5", "Koreksi", null)); // 940 + 5
        String secondId = count(owner, beras, "930");

        MvcResult second = leakage(owner, secondId);
        assertEquals(secondId, ApiTestSupport.read(leakage(owner, null), "$.data.stockCountId"));
        Map<String, Object> secondLine = single(second, "$.data.lines[?(@.inventoryItemId=='" + beras + "')]");
        assertEquals(ApiTestSupport.read(first, "$.data.countedAt"), String.valueOf(secondLine.get("periodStart")), "period start = previous count");
        assertDec("945", secondLine.get("previousQuantity"), "950 - 10 + 5");
        assertDec("15", secondLine.get("shortage"), "945 - 930");
        assertDec("10", secondLine.get("theoreticalUsage"), "one new sale of 2");
        assertDec("5", secondLine.get("manualAdjustment"), "manual adjustment");
        assertDec("0", secondLine.get("waste"), "the old waste is outside the period");
        assertDec("0", secondLine.get("restock"), "the old restock is outside the period");
        assertDec("150", secondLine.get("variancePct"), "15 / 10");
        assertDec("150", secondLine.get("lossAmount"), "15 x 10");
        assertDec("200", ApiTestSupport.read(leakage(owner, firstId), "$.data.totalLoss"), "an older count stays readable by id");

        // History: the zero-difference line counts as a line; the report ignores counts of other outlets and tenants.
        MvcResult history = api.send(get(merchant(owner) + "/fnb/inventory/stock-counts").header(AUTH, owner.bearer()), 200);
        assertEquals(secondId, ApiTestSupport.read(history, "$.data[0].stockCountId"), "newest first");
        Map<String, Object> firstSummary = single(history, "$.data[?(@.stockCountId=='" + firstId + "')]");
        assertEquals(2, ((Number) firstSummary.get("lineCount")).intValue());
        assertEquals(1, ((Number) firstSummary.get("flaggedCount")).intValue());
        assertDec("200", firstSummary.get("totalLoss"), "history total loss");

        MerchantFixture other = api.onboardOwner("lm-leak-other");
        assertStatus(404, api.call(get(merchant(other) + "/fnb/reports/leakage").param("stockCountId", firstId)
                .header(AUTH, other.bearer())));
        assertStatus(404, api.call(get(merchant(other) + "/fnb/reports/leakage").header(AUTH, other.bearer()))); // an outlet without any count has no report
    }

    @Test
    void consolidatedLeakageRanksOutletsByLossAndHonoursTheDateRange() throws Exception {
        MerchantFixture a = api.onboardOwner("lm-cons");
        api.jdbc().update("UPDATE subscriptions SET extra_outlets = 1 WHERE tenant_id = ? AND plan_code <> 'FREETIER'", a.tenantId());
        MerchantFixture b = secondOutlet(a);
        String susu = ingredient(a, "Susu", "ml");
        String telur = ingredient(b, "Telur", "pcs");
        restock(a, susu, "100", "10");
        restock(b, telur, "100", "10");
        count(a, susu, "98"); // loss 20
        count(b, telur, "90"); // loss 100: the second outlet ranks first
        LocalDate today = LocalDate.now(JAKARTA);

        MvcResult report = api.send(get("/api/v1/merchant/reports/leakage").param("from", today.minusDays(1).toString())
                .param("to", today.plusDays(1).toString()).header(AUTH, a.bearer()), 200);

        assertEquals(today.minusDays(1).toString(), ApiTestSupport.read(report, "$.data.from"));
        assertEquals(today.plusDays(1).toString(), ApiTestSupport.read(report, "$.data.to"));
        assertEquals(List.of(b.outletId().toString(), a.outletId().toString()),
                rows(report, "$.data.outlets[*]").stream().map(row -> String.valueOf(row.get("outletId"))).toList());
        assertDec("100", ApiTestSupport.read(report, "$.data.outlets[0].totalLoss"), "outlet with the larger loss first");
        assertDec("20", ApiTestSupport.read(report, "$.data.outlets[1].totalLoss"), "second");
        assertEquals("1", ApiTestSupport.read(report, "$.data.outlets[0].stockCountCount"));
        assertEquals("1", ApiTestSupport.read(report, "$.data.outlets[0].flaggedCount"));
        assertEquals("Telur", ApiTestSupport.read(report, "$.data.outlets[0].lines[0].name"));
        assertDec("120", ApiTestSupport.read(report, "$.data.totalLoss"), "sum over the outlets");

        MvcResult before = api.send(get("/api/v1/merchant/reports/leakage").param("from", today.minusDays(5).toString())
                .param("to", today.minusDays(5).toString()).header(AUTH, a.bearer()), 200);
        assertEquals("0", ApiTestSupport.read(before, "$.data.outlets.length()"), "outlets without a count in the range are omitted");
        assertDec("0", ApiTestSupport.read(before, "$.data.totalLoss"), "nothing to sum");
        assertEquals("2", ApiTestSupport.read(api.send(get("/api/v1/merchant/reports/leakage").header(AUTH, a.bearer()), 200),
                "$.data.outlets.length()"), "the default range is the last 30 days");
        assertStatus(400, api.call(get("/api/v1/merchant/reports/leakage").param("from", today.plusDays(1).toString())
                .param("to", today.toString()).header(AUTH, a.bearer())));
    }

    // ------------------------------------------------------------------ menu margin

    @Test
    void menuMarginsClassifyEveryMenuOrderWorstFirstAndRoundTheSuggestedPriceUpToRp500() throws Exception {
        MerchantFixture owner = api.onboardOwner("lm-margin");
        String biji = ingredient(owner, "Biji Kopi", "g");
        String es = ingredient(owner, "Es Batu", "pcs"); // never bought: no average cost
        restock(owner, biji, "1000", "10");
        product(owner, "Teh", "20000", biji, "100"); // cost 1000 -> 95.00%
        product(owner, "Susu", "3000", biji, "130"); // cost 1300 -> 56.67%, price 1300 / 0.4 = 3250 -> 3500
        product(owner, "Air", "3000", null, null);
        product(owner, "Kopi", "10000", biji, "500"); // cost 5000 -> 50.00%, price 12500
        product(owner, "Dingin", "5000", es, "1");

        MvcResult report = menuMargins(owner, false);

        assertDec("60", ApiTestSupport.read(report, "$.data.targetMarginPct"), "default target");
        assertEquals("2", ApiTestSupport.read(report, "$.data.belowTargetCount"));
        assertEquals(List.of("Kopi", "Susu", "Dingin", "Air", "Teh"),
                rows(report, "$.data.menus[*]").stream().map(row -> String.valueOf(row.get("name"))).toList(),
                "BELOW_TARGET (worst first), COST_UNKNOWN, NO_RECIPE, OK");
        Map<String, Object> kopi = single(report, "$.data.menus[?(@.name=='Kopi')]");
        assertEquals("BELOW_TARGET", kopi.get("status"));
        assertDec("5000", kopi.get("hpp"), "500 g x 10");
        assertDec("50", kopi.get("marginPct"), "(10000 - 5000) / 10000");
        assertDec("12500", kopi.get("suggestedPrice"), "exactly on the step");
        assertDec("3500", single(report, "$.data.menus[?(@.name=='Susu')]").get("suggestedPrice"), "3250 rounds up to the next 500");
        Map<String, Object> dingin = single(report, "$.data.menus[?(@.name=='Dingin')]");
        assertEquals("COST_UNKNOWN", dingin.get("status"));
        assertEquals(List.of("Es Batu"), dingin.get("missingCostItems"));
        assertNull(dingin.get("marginPct"));
        assertEquals("NO_RECIPE", single(report, "$.data.menus[?(@.name=='Air')]").get("status"));
        Map<String, Object> teh = single(report, "$.data.menus[?(@.name=='Teh')]");
        assertEquals("OK", teh.get("status"));
        assertDec("95", teh.get("marginPct"), "(20000 - 1000) / 20000");
        assertNull(teh.get("suggestedPrice"));

        assertEquals(List.of("Kopi", "Susu"), rows(menuMargins(owner, true), "$.data.menus[*]").stream()
                .map(row -> String.valueOf(row.get("name"))).toList(), "belowTargetOnly");
    }

    @Test
    void theTargetMarginFromTheBusinessSettingsDrivesTheMenuMarginReport() throws Exception {
        MerchantFixture owner = api.onboardOwner("lm-target");
        String biji = ingredient(owner, "Biji Kopi", "g");
        restock(owner, biji, "1000", "10");
        product(owner, "Kopi", "10000", biji, "500"); // margin 50.00%

        assertStatus(200, putSettings(owner, "40"));
        assertDec("40", ApiTestSupport.read(api.send(get(merchant(owner) + "/business-settings").header(AUTH, owner.bearer()), 200),
                "$.data.targetMarginPct"), "settings read back");
        MvcResult atFortyPercent = menuMargins(owner, false);
        assertDec("40", ApiTestSupport.read(atFortyPercent, "$.data.targetMarginPct"), "report target");
        assertEquals("OK", ApiTestSupport.read(atFortyPercent, "$.data.menus[0].status"));
        assertEquals("0", ApiTestSupport.read(atFortyPercent, "$.data.belowTargetCount"));

        assertStatus(200, putSettings(owner, null));
        assertDec("40", ApiTestSupport.read(menuMargins(owner, false), "$.data.targetMarginPct"), "an omitted target is unchanged");
        assertStatus(400, putSettings(owner, "100"));

        assertStatus(200, putSettings(owner, "80"));
        Map<String, Object> kopi = single(menuMargins(owner, false), "$.data.menus[?(@.name=='Kopi')]");
        assertEquals("BELOW_TARGET", kopi.get("status"));
        assertDec("25000", kopi.get("suggestedPrice"), "5000 / 0.2");
    }

    @Test
    void onlyAnOwnerRestockThatMovesTheAverageReturnsMarginWarnings() throws Exception {
        MerchantFixture owner = api.onboardOwner("lm-warn");
        MerchantFixture cashier = api.onboardCashier(owner, "lm-warn");
        String biji = ingredient(owner, "Biji Kopi", "g");
        restock(owner, biji, "1000", "10");
        product(owner, "Kopi", "20000", biji, "500"); // 75% at 10, 50% at 20
        product(owner, "Teh", "30000", biji, "100"); // still 93% at 20

        List<String> warnings = JsonPath.read(restock(owner, biji, "1000", "30").getResponse().getContentAsString(),
                "$.data.marginWarnings");
        assertEquals(1, warnings.size(), "only the menu that falls below the 60% target");
        assertTrue(warnings.get(0).startsWith("Margin Kopi turun ke "), warnings.get(0));

        Object forCashier = JsonPath.<Map<String, Object>>read(restock(cashier, biji, "1000", "50").getResponse()
                .getContentAsString(), "$.data").get("marginWarnings");
        assertTrue(forCashier == null || ((List<?>) forCashier).isEmpty(), "cashiers get no cost data: " + forCashier);

        // The average is 30 now; a price equal to it does not move it, so there is nothing to warn about.
        List<String> unchanged = JsonPath.read(restock(owner, biji, "1000", "30").getResponse().getContentAsString(),
                "$.data.marginWarnings");
        assertTrue(unchanged.isEmpty(), unchanged.toString());
    }

    // ------------------------------------------------------------------ settings and outlet helpers

    private MvcResult putSettings(MerchantFixture owner, String targetMarginPct) throws Exception {
        String url = merchant(owner) + "/business-settings";
        JsonNode current = JSON.readTree(api.send(get(url).header(AUTH, owner.bearer()), 200).getResponse().getContentAsString())
                .path("data");
        ObjectNode body = ((ObjectNode) current.deepCopy());
        body.remove("outletId");
        body.remove("targetMarginPct");
        if (targetMarginPct != null) {
            body.put("targetMarginPct", new BigDecimal(targetMarginPct));
        }
        return api.call(put(url).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** Creates the add-on outlet through the API and returns the owner's token scoped to it (select-merchant). */
    private MerchantFixture secondOutlet(MerchantFixture owner) throws Exception {
        UUID outletId = UUID.fromString(ApiTestSupport.read(api.send(post("/api/v1/merchant/outlets").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Cabang\",\"timeZone\":\"Asia/Jakarta\"}"), 201),
                "$.data.id"));
        String email = api.jdbc().queryForObject("SELECT u.email FROM tenant_memberships m JOIN staff_users u ON u.id = m.user_id "
                + "WHERE m.tenant_id = ? AND m.role = 'OWNERMERCHANT'", String.class, owner.tenantId());
        String account = ApiTestSupport.read(api.send(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!23\"}"), 200), "$.data.accessToken");
        String token = ApiTestSupport.read(api.send(post("/api/v1/auth/select-merchant").header(AUTH, "Bearer " + account)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + owner.tenantId() + "\",\"outletId\":\""
                        + outletId + "\"}"), 200), "$.data.accessToken");
        return new MerchantFixture(owner.tenantId(), outletId, token);
    }
}
