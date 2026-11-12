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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Reusable (outlet-level) option groups attached to many products (TASK-064) against real PostgreSQL,
 * real Flyway schema (V1..V5) and the real security chain.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FnbReusableOptionGroupIntegrationTest extends AbstractPostgresIntegrationTest {

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

    private static String admin(MerchantFixture merchant) {
        return "/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb";
    }

    private static String staff(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb";
    }

    private static String groupsUrl(MerchantFixture merchant) {
        return admin(merchant) + "/option-groups";
    }

    private static String libraryUrl(MerchantFixture merchant) {
        return staff(merchant) + "/option-groups";
    }

    private static String attachUrl(MerchantFixture merchant, String productId) {
        return admin(merchant) + "/products/" + productId + "/option-groups";
    }

    private static String productGroupsUrl(MerchantFixture merchant, String productId) {
        return staff(merchant) + "/products/" + productId + "/option-groups";
    }

    private static String optionsUrl(MerchantFixture merchant, String groupId) {
        return groupsUrl(merchant) + "/" + groupId + "/options";
    }

    private static String optionsReadUrl(MerchantFixture merchant, String groupId) {
        return libraryUrl(merchant) + "/" + groupId + "/options";
    }

    private static String optionRecipeUrl(MerchantFixture merchant, String groupId, String optionId) {
        return optionsUrl(merchant, groupId) + "/" + optionId + "/recipe";
    }

    private static String orders(MerchantFixture merchant) {
        return staff(merchant) + "/orders";
    }

    private static String stockItems(MerchantFixture merchant) {
        return staff(merchant) + "/inventory/items";
    }

    private static String groupJson(String name, boolean required, int minimum, int maximum, int displayOrder) {
        return "{\"name\":\"" + name + "\",\"required\":" + required + ",\"minimumSelections\":" + minimum
                + ",\"maximumSelections\":" + maximum + ",\"displayOrder\":" + displayOrder + "}";
    }

    private static String optionJson(String name, String price) {
        return "{\"name\":\"" + name + "\",\"priceAdjustment\":" + price + ",\"available\":true,\"displayOrder\":1}";
    }

    private static String attachJson(String... groupIds) {
        StringBuilder json = new StringBuilder("{\"groupIds\":[");
        for (int i = 0; i < groupIds.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(groupIds[i]).append('"');
        }
        return json.append("]}").toString();
    }

    private static String itemJson(String name, String unit, String reorderLevel, String initialQuantity) {
        return "{\"name\":\"" + name + "\",\"unit\":\"" + unit + "\",\"reorderLevel\":" + reorderLevel
                + ",\"initialQuantity\":" + initialQuantity + "}";
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

    // ------------------------------------------------------------------ API helpers

    private String createGroup(MerchantFixture owner, String name, boolean required, int minimum, int maximum,
                               int displayOrder) throws Exception {
        return ApiTestSupport.read(api.send(post(groupsUrl(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(groupJson(name, required, minimum, maximum, displayOrder)),
                201), "$.data.id");
    }

    private String createOption(MerchantFixture owner, String groupId, String name, String price) throws Exception {
        return ApiTestSupport.read(api.send(post(optionsUrl(owner, groupId)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(optionJson(name, price)), 201), "$.data.id");
    }

    private String createIngredient(MerchantFixture owner, String name, String unit, String reorderLevel,
                                    String initialQuantity) throws Exception {
        return ApiTestSupport.read(api.send(post(admin(owner) + "/inventory/items")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(itemJson(name, unit, reorderLevel, initialQuantity)), 201), "$.data.id");
    }

    private void setRecipe(MerchantFixture owner, String url, String body) throws Exception {
        api.send(put(url).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON).content(body), 200);
    }

    private MvcResult attach(MerchantFixture actor, String productId, String body) throws Exception {
        return api.call(put(attachUrl(actor, productId)).header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void attachOk(MerchantFixture owner, String productId, String... groupIds) throws Exception {
        assertStatus(200, attach(owner, productId, attachJson(groupIds)));
    }

    private MvcResult tryOrder(MerchantFixture actor, String productId, String... optionIds) throws Exception {
        StringBuilder options = new StringBuilder();
        for (int i = 0; i < optionIds.length; i++) {
            if (i > 0) {
                options.append(',');
            }
            options.append('"').append(optionIds[i]).append('"');
        }
        String body = "{\"serviceType\":\"TAKEAWAY\",\"customerLabel\":\"Budi\",\"items\":[{\"productId\":\""
                + productId + "\",\"quantity\":1,\"optionIds\":[" + options + "]}]}";
        return api.call(post(orders(actor)).header(AUTH, actor.bearer())
                .header("Idempotency-Key", "pos-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** Creates an unpaid POS order of one item and returns its id. */
    private String createOrder(MerchantFixture actor, String productId, String... optionIds) throws Exception {
        MvcResult created = tryOrder(actor, productId, optionIds);
        assertStatus(201, created);
        assertEquals("UNPAID", ApiTestSupport.read(created, "$.data.paymentStatus"));
        return ApiTestSupport.read(created, "$.data.orderId");
    }

    private MvcResult payCash(MerchantFixture actor, String orderId) throws Exception {
        return api.send(post(orders(actor) + "/" + orderId + "/payments").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CASH\",\"amountTendered\":1000000,\"idempotencyKey\":\"pay-" + UUID.randomUUID()
                        + "\"}"), 200, 201);
    }

    private static void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
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

    private List<String> libraryIds(MerchantFixture actor) throws Exception {
        return readList(api.send(get(libraryUrl(actor)).header(AUTH, actor.bearer()), 200), "$.data[*].id");
    }

    private List<String> productGroupIds(MerchantFixture actor, String productId) throws Exception {
        return readList(api.send(get(productGroupsUrl(actor, productId)).header(AUTH, actor.bearer()), 200),
                "$.data[*].id");
    }

    private static void assertDecimal(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + ": expected " + expected + " but was " + actual);
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

    private int links(String productId) {
        return count("SELECT count(*) FROM product_option_groups WHERE product_id = ?::uuid", productId);
    }

    private int linksOfGroup(String groupId) {
        return count("SELECT count(*) FROM product_option_groups WHERE option_group_id = ?::uuid", groupId);
    }

    private boolean archived(String groupId) {
        return Boolean.TRUE.equals(api.jdbc().queryForObject(
                "SELECT archived FROM option_groups WHERE id = ?::uuid", Boolean.class, groupId));
    }

    private int storedOrders(MerchantFixture merchant) {
        return count("SELECT count(*) FROM sales_orders WHERE tenant_id = ?::uuid", merchant.tenantId().toString());
    }

    /** The movement ledger must always add up to the cached balance. */
    private void assertLedgerMatchesBalance(String itemId) {
        BigDecimal sum = api.jdbc().queryForObject(
                "SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_movements WHERE inventory_item_id = ?::uuid",
                BigDecimal.class, itemId);
        assertEquals(0, sum.compareTo(balance(itemId)),
                "SUM(quantity_delta)=" + sum + " must equal quantity_on_hand=" + balance(itemId));
    }

    // ------------------------------------------------------------------ 1. reuse + stock deduction

    @Test
    void oneSharedOptionAttachedToTwoProductsDeductsItsRecipeOncePerOrderedUnit() throws Exception {
        MerchantFixture owner = api.onboardOwner("rog-stock");
        String category = api.createCategory(owner, "Minuman");
        String teh = api.createProduct(owner, category, "Es Teh", PRICE);
        String kopi = api.createProduct(owner, category, "Es Kopi", PRICE);
        String boba = createIngredient(owner, "Boba", "g", "920", "1000");
        String gula = createIngredient(owner, "Gula", "g", "10", "100");
        // Only the second product has its own recipe; the shared option recipe is independent of both.
        setRecipe(owner, admin(owner) + "/products/" + kopi + "/recipe", recipeJson(gula, "5"));

        String groupId = createGroup(owner, "Topping", false, 0, 3, 1);
        String bobaOption = createOption(owner, groupId, "Boba", "3000");
        setRecipe(owner, optionRecipeUrl(owner, groupId, bobaOption), recipeJson(boba, "50"));
        attachOk(owner, teh, groupId);
        attachOk(owner, kopi, groupId);
        assertEquals(List.of(groupId), productGroupIds(owner, teh));
        assertEquals(List.of(groupId), productGroupIds(owner, kopi));
        assertEquals(1, libraryIds(owner).size(), "one library group serves both products");

        String orderTeh = createOrder(owner, teh, bobaOption);
        MvcResult paidTeh = payCash(owner, orderTeh);
        assertTrue(readList(paidTeh, "$.data.stockWarnings").isEmpty(),
                "950 g is above the reorder level 920: " + readList(paidTeh, "$.data.stockWarnings"));
        assertDecimal("950", balance(boba), "boba after the first product");
        assertEquals(1, saleMovements(orderTeh));

        String orderKopi = createOrder(owner, kopi, bobaOption);
        MvcResult paidKopi = payCash(owner, orderKopi);
        assertEquals(List.of("Boba: sisa 900 g (menipis)"), readList(paidKopi, "$.data.stockWarnings"));
        assertDecimal("900", balance(boba), "boba after both products: 1000 - 2 x 50");
        assertDecimal("95", balance(gula), "the product recipe of the second product still deducts");
        assertEquals(2, saleMovements(orderKopi), "boba (shared option) + gula (product recipe)");

        assertEquals(2, count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid "
                + "AND movement_type = 'SALE'", boba), "exactly two SALE movements for the shared option");
        assertEquals(2, count("SELECT count(*) FROM inventory_movements WHERE inventory_item_id = ?::uuid "
                + "AND movement_type = 'SALE' AND quantity_delta = -50", boba));
        assertLedgerMatchesBalance(boba);
        assertLedgerMatchesBalance(gula);
        MvcResult list = api.send(get(stockItems(owner)).header(AUTH, owner.bearer()), 200);
        assertEquals(List.of("LOW"), readList(list, "$.data[?(@.id=='" + boba + "')].stockStatus"));
        assertEquals(List.of("OK"), readList(list, "$.data[?(@.id=='" + gula + "')].stockStatus"));
    }

    // ------------------------------------------------------------------ 2. cashier option listing

    @Test
    void cashierListsTheSharedGroupUnderBothProductsWithEachOptionExactlyOnce() throws Exception {
        MerchantFixture owner = api.onboardOwner("rog-menu");
        String category = api.createCategory(owner, "Minuman");
        String teh = api.createProduct(owner, category, "Es Teh", PRICE);
        String kopi = api.createProduct(owner, category, "Es Kopi", PRICE);
        String plain = api.createProduct(owner, category, "Air Mineral", PRICE);
        String groupId = createGroup(owner, "Topping", false, 0, 3, 1);
        String boba = createOption(owner, groupId, "Boba", "3000");
        String jelly = createOption(owner, groupId, "Jelly", "2000");
        attachOk(owner, teh, groupId);
        attachOk(owner, kopi, groupId);

        for (String productId : List.of(teh, kopi)) {
            assertEquals(List.of(groupId), productGroupIds(owner, productId),
                    "the shared group appears exactly once under each product");
        }
        assertTrue(productGroupIds(owner, plain).isEmpty(), "a product without attached groups exposes none");
        List<String> optionIds = readList(api.send(get(optionsReadUrl(owner, groupId)).header(AUTH, owner.bearer()), 200),
                "$.data[*].id");
        assertEquals(2, optionIds.size(), "no duplicated options in the shared group: " + optionIds);
        assertEquals(Set.of(boba, jelly), new HashSet<>(optionIds));
    }

    // ------------------------------------------------------------------ 3. group rules enforced at order time

    @Test
    void orderEnforcesRequiredGroupAndRejectsOptionsOfGroupsNotAttachedToTheProduct() throws Exception {
        MerchantFixture owner = api.onboardOwner("rog-rules");
        String category = api.createCategory(owner, "Minuman");
        String product = api.createProduct(owner, category, "Es Teh", PRICE);
        String sizeGroup = createGroup(owner, "Ukuran", true, 1, 1, 1);
        String large = createOption(owner, sizeGroup, "Large", "2000");
        String small = createOption(owner, sizeGroup, "Small", "0");
        String toppingGroup = createGroup(owner, "Topping", false, 0, 3, 2);
        String boba = createOption(owner, toppingGroup, "Boba", "3000");
        attachOk(owner, product, sizeGroup);

        assertStatus(400, tryOrder(owner, product));
        assertStatus(400, tryOrder(owner, product, boba));
        assertStatus(400, tryOrder(owner, product, large, small));
        assertStatus(400, tryOrder(owner, product, large, boba));
        assertEquals(0, storedOrders(owner), "no rejected request may leave an order behind");

        String orderId = createOrder(owner, product, large);
        assertEquals(1, storedOrders(owner));
        assertDecimal("27000", new BigDecimal(ApiTestSupport.read(api.send(
                get(orders(owner) + "/" + orderId).header(AUTH, owner.bearer()), 200), "$.data.subtotal")),
                "subtotal = product 25000 + Large 2000");

        // Attaching the second group makes its options valid for this product, and the rules still hold.
        attachOk(owner, product, sizeGroup, toppingGroup);
        createOrder(owner, product, large, boba);
        assertStatus(400, tryOrder(owner, product, boba));
        assertEquals(2, storedOrders(owner));

        // Detaching the required group removes its options (and its rule) from the product immediately.
        attachOk(owner, product, toppingGroup);
        assertStatus(400, tryOrder(owner, product, large));
        createOrder(owner, product, boba);
        assertEquals(3, storedOrders(owner));
    }

    // ------------------------------------------------------------------ 4. archive

    @Test
    void archivingAnAttachedGroupIsConflictUntilEveryProductDetachesItAndArchivedGroupsAreGone() throws Exception {
        MerchantFixture owner = api.onboardOwner("rog-archive");
        String category = api.createCategory(owner, "Minuman");
        String first = api.createProduct(owner, category, "Es Teh", PRICE);
        String second = api.createProduct(owner, category, "Es Kopi", PRICE);
        String third = api.createProduct(owner, category, "Es Susu", PRICE);
        String boba = createIngredient(owner, "Boba", "g", "10", "100");
        String groupId = createGroup(owner, "Topping", false, 0, 3, 1);
        String optionId = createOption(owner, groupId, "Boba", "3000");
        setRecipe(owner, optionRecipeUrl(owner, groupId, optionId), recipeJson(boba, "50"));
        attachOk(owner, first, groupId);
        attachOk(owner, second, groupId);
        String groupUrl = groupsUrl(owner) + "/" + groupId;

        assertStatus(409, api.call(delete(groupUrl).header(AUTH, owner.bearer())));
        assertFalse(archived(groupId), "a conflicting archive must not change the group");
        assertEquals(List.of(groupId), libraryIds(owner));

        attachOk(owner, first);
        assertEquals(0, links(first));
        assertEquals(1, linksOfGroup(groupId));
        assertStatus(409, api.call(delete(groupUrl).header(AUTH, owner.bearer())));
        assertFalse(archived(groupId), "still used by the second product");

        attachOk(owner, second);
        assertEquals(0, linksOfGroup(groupId));
        MvcResult archivedGroup = api.send(delete(groupUrl).header(AUTH, owner.bearer()), 200);
        assertEquals(groupId, ApiTestSupport.read(archivedGroup, "$.data.id"));
        assertTrue(archived(groupId), "the group is archived (soft), never deleted");
        assertEquals(1, count("SELECT count(*) FROM option_groups WHERE id = ?::uuid", groupId));
        assertTrue(libraryIds(owner).isEmpty(), "archived groups leave the library");
        assertTrue(productGroupIds(owner, first).isEmpty());

        // An archived group behaves like an unknown one: options, recipes, updates and attachments are 404.
        assertStatus(404, api.call(post(optionsUrl(owner, groupId)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(optionJson("Jelly", "2000"))));
        assertStatus(404, api.call(get(optionsReadUrl(owner, groupId)).header(AUTH, owner.bearer())));
        assertStatus(404, api.call(put(optionsUrl(owner, groupId) + "/" + optionId).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(optionJson("Boba Besar", "3000"))));
        assertStatus(404, api.call(put(optionRecipeUrl(owner, groupId, optionId)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(recipeJson(boba, "10"))));
        assertStatus(404, api.call(put(groupUrl).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(groupJson("Dibajak", false, 0, 1, 1))));
        assertStatus(404, api.call(delete(groupUrl).header(AUTH, owner.bearer())));
        assertStatus(404, attach(owner, first, attachJson(groupId)));
        assertEquals(0, links(first));
        assertEquals(1, count("SELECT count(*) FROM product_options WHERE option_group_id = ?::uuid", groupId),
                "the option row is kept");
        assertEquals("Topping", api.jdbc().queryForObject(
                "SELECT name FROM option_groups WHERE id = ?::uuid", String.class, groupId));

        // A group attached only to an archived product is not "in use".
        String otherGroup = createGroup(owner, "Saus", false, 0, 1, 2);
        attachOk(owner, third, otherGroup);
        String otherUrl = groupsUrl(owner) + "/" + otherGroup;
        assertStatus(409, api.call(delete(otherUrl).header(AUTH, owner.bearer())));
        api.send(delete(admin(owner) + "/products/" + third).header(AUTH, owner.bearer()), 200);
        api.send(delete(otherUrl).header(AUTH, owner.bearer()), 200);
        assertTrue(archived(otherGroup));
        assertTrue(libraryIds(owner).isEmpty());
    }

    // ------------------------------------------------------------------ 5. replace-all ordering

    @Test
    void attachingReplacesAllLinksInRequestOrderAndKeepsTheLibraryIntact() throws Exception {
        MerchantFixture owner = api.onboardOwner("rog-order");
        String category = api.createCategory(owner, "Minuman");
        String product = api.createProduct(owner, category, "Es Teh", PRICE);
        // Library order (displayOrder, name) is [A, B]; the attachment order below is deliberately [B, A].
        String groupA = createGroup(owner, "Topping", false, 0, 3, 1);
        String groupB = createGroup(owner, "Ukuran", true, 1, 1, 2);
        assertEquals(List.of(groupA, groupB), libraryIds(owner));

        MvcResult attached = attach(owner, product, attachJson(groupB, groupA));
        assertStatus(200, attached);
        assertEquals(List.of(groupB, groupA), readList(attached, "$.data[*].id"), "response keeps the request order");
        assertEquals(List.of(groupB, groupA), productGroupIds(owner, product));
        assertEquals(2, links(product));
        assertEquals(0, count("SELECT display_order FROM product_option_groups WHERE product_id = ?::uuid "
                + "AND option_group_id = ?::uuid", product, groupB));
        assertEquals(1, count("SELECT display_order FROM product_option_groups WHERE product_id = ?::uuid "
                + "AND option_group_id = ?::uuid", product, groupA));

        // Re-attaching only A removes B from the product, but B stays in the library.
        MvcResult reattached = attach(owner, product, attachJson(groupA));
        assertStatus(200, reattached);
        assertEquals(List.of(groupA), readList(reattached, "$.data[*].id"));
        assertEquals(List.of(groupA), productGroupIds(owner, product));
        assertEquals(1, links(product));
        assertEquals(0, linksOfGroup(groupB));
        assertEquals(List.of(groupA, groupB), libraryIds(owner), "detaching never removes a group from the library");
        assertFalse(archived(groupB));

        // The same request twice does not duplicate links.
        attachOk(owner, product, groupA);
        assertEquals(1, links(product));
        assertEquals(0, count("SELECT display_order FROM product_option_groups WHERE product_id = ?::uuid", product));

        // Rejected requests leave the previous attachment untouched.
        assertStatus(400, attach(owner, product, attachJson(groupA, groupA)));
        assertStatus(404, attach(owner, product, attachJson(groupA, UUID.randomUUID().toString())));
        assertStatus(400, attach(owner, product, "{}"));
        assertStatus(404, attach(owner, UUID.randomUUID().toString(), attachJson(groupA)));
        assertEquals(List.of(groupA), productGroupIds(owner, product));
        assertEquals(1, links(product));

        // An empty list only clears.
        MvcResult cleared = attach(owner, product, attachJson());
        assertStatus(200, cleared);
        assertTrue(readList(cleared, "$.data[*].id").isEmpty());
        assertTrue(productGroupIds(owner, product).isEmpty());
        assertEquals(0, links(product));
        assertEquals(List.of(groupA, groupB), libraryIds(owner));
    }

    // ------------------------------------------------------------------ 6. roles and tenant isolation

    @Test
    void cashierOnlyReadsAndAnotherTenantOrOutletCannotSeeOrAttachTheGroup() throws Exception {
        MerchantFixture owner = api.onboardOwner("rog-iso-a");
        MerchantFixture cashier = api.onboardCashier(owner, "rog-iso-cashier");
        MerchantFixture other = api.onboardOwner("rog-iso-b");
        String category = api.createCategory(owner, "Minuman");
        String product = api.createProduct(owner, category, "Es Teh", PRICE);
        String spare = api.createProduct(owner, category, "Es Kopi", PRICE);
        String groupId = createGroup(owner, "Topping", false, 0, 3, 1);
        String optionId = createOption(owner, groupId, "Boba", "3000");
        attachOk(owner, product, groupId);
        String otherProduct = api.createProduct(other, api.createCategory(other, "Minuman"), "Es Teh", PRICE);

        // CASHIER reads the library, the product groups and the options (the /cashier/** surface).
        assertEquals(List.of(groupId), libraryIds(cashier));
        assertEquals(List.of(groupId), productGroupIds(cashier, product));
        assertEquals(List.of(optionId), readList(api.send(get(optionsReadUrl(cashier, groupId))
                .header(AUTH, cashier.bearer()), 200), "$.data[*].id"));

        // ... but every mutation is refused by the /merchant/** rule (403), like the inventory admin endpoints.
        assertStatus(403, api.call(post(groupsUrl(cashier)).header(AUTH, cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(groupJson("Saus", false, 0, 1, 1))));
        assertStatus(403, api.call(put(groupsUrl(cashier) + "/" + groupId).header(AUTH, cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(groupJson("Dibajak", false, 0, 1, 1))));
        assertStatus(403, api.call(delete(groupsUrl(cashier) + "/" + groupId).header(AUTH, cashier.bearer())));
        assertStatus(403, attach(cashier, spare, attachJson(groupId)));
        assertStatus(403, attach(cashier, product, attachJson()));
        assertStatus(403, api.call(post(optionsUrl(cashier, groupId)).header(AUTH, cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(optionJson("Jelly", "2000"))));
        assertStatus(403, api.call(put(optionsUrl(cashier, groupId) + "/" + optionId).header(AUTH, cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(optionJson("Dibajak", "1"))));
        assertEquals("Topping", api.jdbc().queryForObject(
                "SELECT name FROM option_groups WHERE id = ?::uuid", String.class, groupId));
        assertFalse(archived(groupId));
        assertEquals(1, linksOfGroup(groupId));
        assertEquals(0, links(spare));
        assertEquals(1, count("SELECT count(*) FROM option_groups WHERE tenant_id = ?::uuid",
                owner.tenantId().toString()));
        assertEquals(1, count("SELECT count(*) FROM product_options WHERE option_group_id = ?::uuid", groupId));

        // Another tenant: B's own outlet but A's group id -> 404 everywhere, no sign that the group exists.
        assertTrue(libraryIds(other).isEmpty(), "B's library does not contain A's group");
        assertStatus(404, attach(other, otherProduct, attachJson(groupId)));
        assertEquals(0, links(otherProduct));
        assertStatus(404, api.call(put(groupsUrl(other) + "/" + groupId).header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(groupJson("Dibajak", false, 0, 1, 1))));
        assertStatus(404, api.call(delete(groupsUrl(other) + "/" + groupId).header(AUTH, other.bearer())));
        assertStatus(404, api.call(get(optionsReadUrl(other, groupId)).header(AUTH, other.bearer())));
        assertStatus(404, api.call(post(optionsUrl(other, groupId)).header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(optionJson("Susup", "1"))));
        assertStatus(404, api.call(put(optionRecipeUrl(other, groupId, optionId)).header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(recipeJson(UUID.randomUUID().toString(), "1"))));

        // B principal against A's outlet path: 404 on the cashier and merchant surface.
        assertStatus(404, api.call(get(libraryUrl(owner)).header(AUTH, other.bearer())));
        assertStatus(404, api.call(get(productGroupsUrl(owner, product)).header(AUTH, other.bearer())));
        assertStatus(404, api.call(get(optionsReadUrl(owner, groupId)).header(AUTH, other.bearer())));
        assertStatus(404, api.call(post(groupsUrl(owner)).header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(groupJson("Susup", false, 0, 1, 1))));
        assertStatus(404, api.call(put(attachUrl(owner, spare)).header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(attachJson(groupId))));
        assertStatus(404, api.call(delete(groupsUrl(owner) + "/" + groupId).header(AUTH, other.bearer())));
        assertEquals(0, links(spare));
        assertEquals("Topping", api.jdbc().queryForObject(
                "SELECT name FROM option_groups WHERE id = ?::uuid", String.class, groupId));
        assertFalse(archived(groupId));
        assertEquals(List.of(groupId), libraryIds(owner));
        assertEquals(List.of(groupId), productGroupIds(owner, product));

        // Another outlet of the SAME tenant: its group cannot be attached to a product of this outlet.
        UUID secondOutlet = UUID.randomUUID();
        UUID foreignGroup = UUID.randomUUID();
        api.jdbc().update("INSERT INTO outlets (id, tenant_id, name, time_zone, active, created_at, updated_at, version) "
                + "VALUES (?, ?, 'Outlet 2', 'Asia/Jakarta', true, now(), now(), 0)", secondOutlet, owner.tenantId());
        api.jdbc().update("INSERT INTO option_groups (id, tenant_id, outlet_id, name, required, minimum_selections, "
                + "maximum_selections, display_order, archived, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, 'Topping Outlet 2', false, 0, 1, 1, false, now(), now(), 0)",
                foreignGroup, owner.tenantId(), secondOutlet);
        assertStatus(404, attach(owner, spare, attachJson(foreignGroup.toString())));
        assertStatus(404, attach(owner, product, attachJson(groupId, foreignGroup.toString())));
        assertEquals(0, links(spare));
        assertEquals(List.of(groupId), productGroupIds(owner, product), "a rejected attach keeps the old links");
        assertEquals(List.of(groupId), libraryIds(owner), "the other outlet's group is not in this outlet's library");
        assertStatus(404, api.call(get(optionsReadUrl(owner, foreignGroup.toString())).header(AUTH, owner.bearer())));
        assertStatus(404, api.call(delete(groupsUrl(owner) + "/" + foreignGroup).header(AUTH, owner.bearer())));
        assertFalse(archived(foreignGroup.toString()));
    }
}
