package org.lite.project.cashiersaas.integration.identity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Multi-outlet (TASK-083) against real PostgreSQL and the real security chain: owner outlet management under the plan
 * allowance, per-outlet transactions, the tenant billing zone rule and the consolidated sales report. The paid-row expiry
 * (downgrade) scenarios live in PlanEntitlementIntegrationTest next to the other plan rules.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantOutletIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final String PASSWORD = "Passw0rd!23";
    private static final String OUTLETS = "/api/v1/merchant/outlets";
    private static final String ADD_ON_MESSAGE = "Outlet tambahan memerlukan add-on outlet (Rp49.000/outlet/bulan).";

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

    /** A PRO tenant whose paid row carries {@code extraOutlets} add-on outlets (the operator sets it by hand in the pilot). */
    private MerchantFixture pro(String label, int extraOutlets) throws Exception {
        MerchantFixture owner = api.onboardOwner(label, PlanCode.PRO);
        setExtraOutlets(owner, extraOutlets);
        return owner;
    }

    private void setExtraOutlets(MerchantFixture owner, int extraOutlets) {
        api.jdbc().update("UPDATE subscriptions SET extra_outlets = ? WHERE tenant_id = ? AND plan_code <> 'FREETIER'",
                extraOutlets, owner.tenantId());
    }

    private static String outletJson(String name, String timeZone) {
        return "{\"name\":\"" + name + "\",\"timeZone\":\"" + timeZone + "\"}";
    }

    private MvcResult createOutlet(MerchantFixture owner, String name, String timeZone) throws Exception {
        return api.call(post(OUTLETS).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(outletJson(name, timeZone)));
    }

    private MvcResult outletAction(MerchantFixture actor, UUID outletId, String action) throws Exception {
        return api.call(post(OUTLETS + "/" + outletId + "/" + action).header(AUTH, actor.bearer()));
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String login(String email) throws Exception {
        MvcResult login = api.send(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"), 200);
        return "Bearer " + ApiTestSupport.read(login, "$.data.accessToken");
    }

    private String ownerEmail(MerchantFixture owner) {
        return api.jdbc().queryForObject("SELECT u.email FROM tenant_memberships m JOIN staff_users u ON u.id = m.user_id "
                + "WHERE m.tenant_id = ? AND m.role = 'OWNERMERCHANT'", String.class, owner.tenantId());
    }

    /** Creates the outlet through the API (must succeed) and returns the owner's token scoped to it via select-merchant. */
    private MerchantFixture addOutlet(MerchantFixture owner, String name, String timeZone) throws Exception {
        MvcResult created = api.send(post(OUTLETS).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(outletJson(name, timeZone)), 201);
        UUID outletId = UUID.fromString(ApiTestSupport.read(created, "$.data.id"));
        MvcResult selected = api.send(post("/api/v1/auth/select-merchant").header(AUTH, login(ownerEmail(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + owner.tenantId() + "\",\"outletId\":\"" + outletId + "\"}"), 200);
        return new MerchantFixture(owner.tenantId(), outletId, ApiTestSupport.read(selected, "$.data.accessToken"));
    }

    private int auditRows(MerchantFixture owner, String action, UUID outletId) {
        Integer count = api.jdbc().queryForObject("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = ? "
                + "AND entity_id = ?", Integer.class, owner.tenantId(), action, outletId);
        return count == null ? -1 : count;
    }

    private MvcResult consolidated(MerchantFixture actor, String startDate, String endDate, int expectedStatus)
            throws Exception {
        var request = get("/api/v1/merchant/reports/sales").header(AUTH, actor.bearer());
        if (startDate != null) {
            request.param("startDate", startDate).param("endDate", endDate);
        }
        return api.send(request, expectedStatus);
    }

    private static BigDecimal money(MvcResult result, String path) throws Exception {
        return new BigDecimal(ApiTestSupport.read(result, path));
    }

    // ------------------------------------------------------------------ create and list

    @Test
    void anOutletIsCreatedOnlyWithinThePlanAllowanceAndTheListReportsIt() throws Exception {
        MerchantFixture free = api.onboardOwner("out-free", PlanCode.FREETIER);
        MvcResult onFree = createOutlet(free, "Cabang", "Asia/Jakarta");
        assertEquals(402, status(onFree));
        assertEquals("Fitur ini tersedia di paket Pro.", ApiTestSupport.read(onFree, "$.message"));

        MerchantFixture owner = pro("out-limit", 0);
        MvcResult withoutAddOn = createOutlet(owner, "Cabang", "Asia/Jakarta");
        assertEquals(402, status(withoutAddOn));
        assertEquals(ADD_ON_MESSAGE, ApiTestSupport.read(withoutAddOn, "$.message"));

        setExtraOutlets(owner, 1);
        MvcResult created = createOutlet(owner, "Cabang", "Asia/Makassar");
        assertEquals(201, status(created), created.getResponse().getContentAsString());
        assertEquals("Cabang", ApiTestSupport.read(created, "$.data.name"));
        assertEquals("true", ApiTestSupport.read(created, "$.data.active"));
        assertEquals("true", ApiTestSupport.read(created, "$.data.usable"));
        assertEquals(402, status(createOutlet(owner, "Cabang 2", "Asia/Jakarta")), "the add-on outlet is used up");

        MvcResult list = api.send(get(OUTLETS).header(AUTH, owner.bearer()), 200);
        assertEquals("2", ApiTestSupport.read(list, "$.data.outlets.length()"));
        assertEquals("2", ApiTestSupport.read(list, "$.data.allowedOutlets"));
        assertEquals("2", ApiTestSupport.read(list, "$.data.activeOutlets"));
        assertEquals(0, new BigDecimal("49000").compareTo(money(list, "$.data.extraOutletPrice")));
    }

    @Test
    void aNewOutletReachesTheOwnerWithDefaultPaymentLabelsAndIsAudited() throws Exception {
        MerchantFixture owner = pro("out-new", 1);
        MerchantFixture second = addOutlet(owner, "Cabang Baru", "Asia/Makassar");

        MvcResult choices = api.send(get("/api/v1/auth/outlets").header(AUTH, login(ownerEmail(owner))), 200);
        assertEquals("2", ApiTestSupport.read(choices, "$.data.length()"));
        String firstLabels = ApiTestSupport.read(api.send(get("/api/v1/cashier/outlets/" + owner.outletId()
                + "/fnb/payment-labels").header(AUTH, owner.bearer()), 200), "$.data.length()");
        String secondLabels = ApiTestSupport.read(api.send(get("/api/v1/cashier/outlets/" + second.outletId()
                + "/fnb/payment-labels").header(AUTH, second.bearer()), 200), "$.data.length()");
        assertEquals(firstLabels, secondLabels);
        assertTrue(Integer.parseInt(secondLabels) > 0);
        assertEquals(1, auditRows(owner, "OUTLET_CREATED", second.outletId()));
    }

    // ------------------------------------------------------------------ update

    @Test
    void updateRejectsABadTimeZoneAndADuplicateNameAndAuditsOnlyRealChanges() throws Exception {
        MerchantFixture owner = pro("out-upd", 1);
        MerchantFixture second = addOutlet(owner, "Cabang Lama", "Asia/Jakarta");
        String url = OUTLETS + "/" + second.outletId();

        assertEquals(400, status(api.call(put(url).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(outletJson("Cabang Lama", "Mars/Olympus")))));
        assertEquals(409, status(api.call(put(url).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(outletJson("Outlet out-upd", "Asia/Jakarta")))), "the name of the first outlet is taken");

        MvcResult renamed = api.send(put(url).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(outletJson("Cabang Baru", "Asia/Jayapura")), 200);
        assertEquals("Asia/Jayapura", ApiTestSupport.read(renamed, "$.data.timeZone"));
        api.send(put(url).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(outletJson("Cabang Baru", "Asia/Jayapura")), 200);
        assertEquals(1, auditRows(owner, "OUTLET_UPDATED", second.outletId()), "an unchanged update writes no audit row");
    }

    // ------------------------------------------------------------------ deactivate and reactivate

    @Test
    void anOutletWithAnOpenShiftAndTheLastActiveOutletCannotBeDeactivated() throws Exception {
        MerchantFixture owner = pro("out-dea", 1);
        MerchantFixture second = addOutlet(owner, "Cabang", "Asia/Jakarta");
        api.openShift(second, "0.00");

        MvcResult withShift = outletAction(owner, second.outletId(), "deactivate");
        assertEquals(409, status(withShift));
        assertTrue(ApiTestSupport.read(withShift, "$.message").contains("shift"));

        MvcResult first = outletAction(second, owner.outletId(), "deactivate");
        assertEquals(200, status(first));
        assertEquals("false", ApiTestSupport.read(first, "$.data.active"));
        MvcResult last = outletAction(second, second.outletId(), "deactivate");
        assertEquals(409, status(last));
        assertTrue(ApiTestSupport.read(last, "$.message").contains("terakhir"));
        assertEquals(1, auditRows(owner, "OUTLET_DEACTIVATED", owner.outletId()));
    }

    @Test
    void anOutletWithAnOpenUnpaidOrderCannotBeDeactivatedUntilTheOrderIsCancelled() throws Exception {
        MerchantFixture owner = pro("out-open", 1);
        MerchantFixture second = addOutlet(owner, "Cabang", "Asia/Jakarta");
        String order = api.createPosOrder(second, api.createProduct(second, api.createCategory(second, "Menu"),
                "Kopi", "10000"), 1);

        MvcResult refused = outletAction(owner, second.outletId(), "deactivate");
        assertEquals(409, status(refused));
        assertTrue(ApiTestSupport.read(refused, "$.message").contains("pesanan terbuka"));

        api.send(post("/api/v1/cashier/outlets/" + second.outletId() + "/fnb/orders/" + order + "/cancel")
                .header(AUTH, second.bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Salah pesan\"}"), 200);
        assertEquals(200, status(outletAction(owner, second.outletId(), "deactivate")));
    }

    @Test
    void withThreeActiveOutletsAndOneAddOnOnlyTheTwoOldestStayUsable() throws Exception {
        MerchantFixture owner = pro("out-rank", 2);
        MerchantFixture second = addOutlet(owner, "Cabang 2", "Asia/Jakarta");
        MerchantFixture third = addOutlet(owner, "Cabang 3", "Asia/Jakarta");
        String labels = "/fnb/payment-labels";
        for (MerchantFixture shop : new MerchantFixture[]{owner, second, third}) {
            assertEquals(200, status(api.call(get("/api/v1/cashier/outlets/" + shop.outletId() + labels)
                    .header(AUTH, shop.bearer()))), "three outlets fit the allowance of three");
        }

        setExtraOutlets(owner, 1); // allowance 2: the third outlet is over it, the first two are not
        assertEquals(200, status(api.call(get("/api/v1/cashier/outlets/" + owner.outletId() + labels).header(AUTH, owner.bearer()))));
        assertEquals(200, status(api.call(get("/api/v1/cashier/outlets/" + second.outletId() + labels).header(AUTH, second.bearer()))));
        MvcResult refused = api.call(get("/api/v1/cashier/outlets/" + third.outletId() + labels).header(AUTH, third.bearer()));
        assertEquals(402, status(refused));
        assertEquals("OUTLET_OVER_ALLOWANCE", ApiTestSupport.read(refused, "$.data.reason"));
        assertEquals("2", ApiTestSupport.read(refused, "$.data.usableOutletIds.length()"));
        MvcResult list = api.send(get(OUTLETS).header(AUTH, owner.bearer()), 200);
        assertEquals("true", ApiTestSupport.read(list, "$.data.outlets[1].usable"));
        assertEquals("false", ApiTestSupport.read(list, "$.data.outlets[2].usable"));
    }

    @Test
    void reactivationIsBoundByTheAllowanceAndAuditedWhenItSucceeds() throws Exception {
        MerchantFixture owner = pro("out-rea", 1);
        MerchantFixture second = addOutlet(owner, "Cabang", "Asia/Jakarta");
        assertEquals(200, status(outletAction(owner, second.outletId(), "deactivate")));

        setExtraOutlets(owner, 0);
        MvcResult refused = outletAction(owner, second.outletId(), "reactivate");
        assertEquals(402, status(refused));
        assertEquals(ADD_ON_MESSAGE, ApiTestSupport.read(refused, "$.message"));

        setExtraOutlets(owner, 1);
        MvcResult reactivated = outletAction(owner, second.outletId(), "reactivate");
        assertEquals(200, status(reactivated));
        assertEquals("true", ApiTestSupport.read(reactivated, "$.data.active"));
        assertEquals(1, auditRows(owner, "OUTLET_REACTIVATED", second.outletId()));
    }

    // ------------------------------------------------------------------ who may manage outlets

    @Test
    void cashiersCannotManageOutletsAndAnotherTenantsOutletLooksUnknown() throws Exception {
        MerchantFixture owner = pro("out-acl", 1);
        MerchantFixture cashier = api.onboardCashier(owner, "out-acl-c");
        MerchantFixture other = api.onboardOwner("out-acl-o");

        assertEquals(403, status(api.call(get(OUTLETS).header(AUTH, cashier.bearer()))));
        assertEquals(403, status(createOutlet(cashier, "Cabang", "Asia/Jakarta")));
        assertEquals(404, status(api.call(put(OUTLETS + "/" + owner.outletId()).header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(outletJson("Diambil alih", "Asia/Jakarta")))));
        assertEquals(404, status(outletAction(other, owner.outletId(), "deactivate")));
        assertEquals(404, status(outletAction(other, owner.outletId(), "reactivate")));
        MvcResult mine = api.send(get(OUTLETS).header(AUTH, other.bearer()), 200);
        assertEquals("1", ApiTestSupport.read(mine, "$.data.outlets.length()"));
        assertEquals(other.outletId().toString(), ApiTestSupport.read(mine, "$.data.outlets[0].id"));
    }

    // ------------------------------------------------------------------ per-outlet transactions

    @Test
    void aSecondOutletTransactsWhileAnotherOutletRowIsLocked() throws Exception {
        MerchantFixture owner = pro("out-lock", 1);
        MerchantFixture second = addOutlet(owner, "Cabang", "Asia/Jakarta");
        String product = api.createProduct(second, api.createCategory(second, "Menu"), "Kopi", "10000");
        api.ensureShift(second);
        String order = api.createPosOrder(second, product, 1);
        String total = api.orderTotal(second, order);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            try (Statement lock = held.createStatement()) {
                // NO KEY UPDATE conflicts with the PESSIMISTIC_WRITE (FOR UPDATE) of lockOutlet but not with FK inserts.
                lock.execute("SELECT 1 FROM outlets WHERE id = '" + owner.outletId() + "' FOR NO KEY UPDATE");
            }
            Future<MvcResult> paid = executor.submit(() -> api.pay(second, order, "CASH", total, null, null));
            MvcResult result = paid.get(30, TimeUnit.SECONDS); // a tenant-wide lock would wait here until the timeout
            assertTrue(status(result) == 200 || status(result) == 201, result.getResponse().getContentAsString());
            held.rollback();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aPaymentKeyAlreadyUsedInAnotherOutletOfTheTenantIsAConflictNotAServerError() throws Exception {
        MerchantFixture owner = pro("out-key", 1);
        MerchantFixture second = addOutlet(owner, "Cabang", "Asia/Jakarta");
        String firstOrder = api.createPosOrder(owner, api.createProduct(owner, api.createCategory(owner, "Menu"),
                "Kopi", "10000"), 1);
        String secondOrder = api.createPosOrder(second, api.createProduct(second, api.createCategory(second, "Menu"),
                "Teh", "10000"), 1);
        api.ensureShift(owner);
        api.ensureShift(second);
        String key = "pay-" + UUID.randomUUID();

        MvcResult first = api.pay(owner, firstOrder, "CASH", api.orderTotal(owner, firstOrder), null, key);
        assertTrue(status(first) == 200 || status(first) == 201, first.getResponse().getContentAsString());
        MvcResult clash = api.pay(second, secondOrder, "CASH", api.orderTotal(second, secondOrder), null, key);

        assertEquals(409, status(clash), clash.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ billing time zone

    @Test
    void theTenantTimeZoneFollowsTheOutletOnlyWhileItIsTheSingleActiveOutlet() throws Exception {
        MerchantFixture owner = pro("out-zone", 1);
        String settings = OUTLETS + "/" + owner.outletId() + "/business-settings";
        String bodyTemplate = "{\"businessName\":\"Warung out-zone\",\"currency\":\"IDR\",\"taxRate\":10,"
                + "\"serviceChargeRate\":5,\"outletName\":\"Outlet out-zone\",\"timeZone\":\"%s\"}";

        api.send(put(settings).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(bodyTemplate.formatted("Asia/Makassar")), 200);
        assertEquals("Asia/Makassar", api.jdbc().queryForObject("SELECT time_zone FROM tenants WHERE id = ?", String.class,
                owner.tenantId()));

        addOutlet(owner, "Cabang", "Asia/Jakarta");
        api.send(put(settings).header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(bodyTemplate.formatted("Asia/Jayapura")), 200);
        assertEquals("Asia/Makassar", api.jdbc().queryForObject("SELECT time_zone FROM tenants WHERE id = ?", String.class,
                owner.tenantId()), "the billing zone is kept once the tenant has several outlets");
        assertEquals("Asia/Jayapura", api.jdbc().queryForObject("SELECT time_zone FROM outlets WHERE id = ?", String.class,
                owner.outletId()));
    }

    // ------------------------------------------------------------------ consolidated report

    @Test
    void theConsolidatedReportSumsEachOutletInItsOwnTimeZoneAndSkipsUnpaidAndCancelledOrders() throws Exception {
        MerchantFixture owner = pro("rep", 1);
        MerchantFixture second = addOutlet(owner, "Cabang Makassar", "Asia/Makassar");
        String kopi = api.createProduct(owner, api.createCategory(owner, "Menu"), "Kopi", "25000");
        String teh = api.createProduct(second, api.createCategory(second, "Menu"), "Teh", "10000");
        api.ensureShift(owner);
        api.ensureShift(second);
        api.payCashExact(owner, api.createPosOrder(owner, kopi, 1));
        api.payCashExact(second, api.createPosOrder(second, teh, 1));
        api.payCashExact(second, api.createPosOrder(second, teh, 2));
        api.createPosOrder(second, teh, 1); // stays unpaid
        String cancelled = api.createPosOrder(second, teh, 1);
        api.send(post("/api/v1/cashier/outlets/" + second.outletId() + "/fnb/orders/" + cancelled + "/cancel")
                .header(AUTH, second.bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Salah pesan\"}"), 200);
        // 16:30 UTC on 10 March is 23:30 in Jakarta but already 00:30 on the 11th in Makassar.
        api.jdbc().update("UPDATE payments SET paid_at = ? WHERE tenant_id = ?",
                Timestamp.from(Instant.parse("2026-03-10T16:30:00Z")), owner.tenantId());

        MvcResult tenth = consolidated(owner, "2026-03-10", "2026-03-10", 200);
        assertEquals("Asia/Jakarta", ApiTestSupport.read(tenth, "$.data.outlets[0].timeZone"));
        assertEquals("Asia/Makassar", ApiTestSupport.read(tenth, "$.data.outlets[1].timeZone"));
        assertEquals("1", ApiTestSupport.read(tenth, "$.data.outlets[0].summary.transactionCount"));
        assertEquals("0", ApiTestSupport.read(tenth, "$.data.outlets[1].summary.transactionCount"));
        assertEquals("1", ApiTestSupport.read(tenth, "$.data.totals.transactionCount"));

        MvcResult both = consolidated(owner, "2026-03-10", "2026-03-11", 200);
        assertEquals("3", ApiTestSupport.read(both, "$.data.totals.transactionCount"));
        BigDecimal firstTotal = money(both, "$.data.outlets[0].summary.total");
        BigDecimal secondTotal = money(both, "$.data.outlets[1].summary.total");
        assertEquals(0, firstTotal.add(secondTotal).compareTo(money(both, "$.data.totals.total")));
        for (MerchantFixture shop : new MerchantFixture[]{owner, second}) {
            MvcResult own = api.send(get(OUTLETS + "/" + shop.outletId() + "/fnb/reports/sales")
                    .param("startDate", "2026-03-10").param("endDate", "2026-03-11").header(AUTH, shop.bearer()), 200);
            int row = shop == owner ? 0 : 1;
            assertEquals(0, money(own, "$.data.summary.total").compareTo(money(both, "$.data.outlets[" + row + "].summary.total")));
        }
    }

    @Test
    void theConsolidatedReportDropsOutletsOverTheAllowanceAndIsOwnerOnlyAndTenantScoped() throws Exception {
        MerchantFixture owner = pro("rep-acl", 1);
        addOutlet(owner, "Cabang", "Asia/Makassar");
        MerchantFixture cashier = api.onboardCashier(owner, "rep-acl-c");
        MerchantFixture other = api.onboardOwner("rep-acl-o");
        assertEquals("2", ApiTestSupport.read(consolidated(owner, null, null, 200), "$.data.outlets.length()"));

        setExtraOutlets(owner, 0); // the add-on lapses: only the oldest outlet stays usable
        MvcResult shrunk = consolidated(owner, null, null, 200);
        assertEquals("1", ApiTestSupport.read(shrunk, "$.data.outlets.length()"));
        assertEquals(owner.outletId().toString(), ApiTestSupport.read(shrunk, "$.data.outlets[0].outletId"));

        consolidated(cashier, null, null, 403);
        MvcResult foreign = consolidated(other, null, null, 200);
        assertEquals("1", ApiTestSupport.read(foreign, "$.data.outlets.length()"));
        assertEquals(other.outletId().toString(), ApiTestSupport.read(foreign, "$.data.outlets[0].outletId"));
    }
}
