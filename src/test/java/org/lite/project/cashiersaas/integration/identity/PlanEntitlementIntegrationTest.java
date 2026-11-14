package org.lite.project.cashiersaas.integration.identity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.lite.project.cashiersaas.integration.support.MerchantFixture;
import org.lite.project.cashiersaas.service.PlanEntitlementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Plan entitlements (TASK-082) against real PostgreSQL and the real security chain: the FREETIER base row plus paid
 * rows, cashier access computed from the effective plan, 402 on PRO features, and the 50th-paid-order Premium trial.
 * The tenant fixtures give every other scenario an open-ended PREMIUM row; these tests build FREETIER and PRO tenants.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanEntitlementIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlanEntitlementService plans;

    private ApiTestSupport api;
    /** FREETIER only. Shared by tests that never change its subscription rows. */
    private MerchantFixture free;

    @BeforeAll
    void setUpAll() throws Exception {
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        api.ensureFnbBusinessType();
        free = api.onboardOwner("plan-free", PlanCode.FREETIER);
    }

    @AfterAll
    void cleanUp() {
        api.truncateTenantData();
    }

    // ------------------------------------------------------------------ helpers

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private int staffListStatus(MerchantFixture shop) throws Exception {
        return status(api.call(get("/api/v1/merchant/outlets/" + shop.outletId() + "/staff").header(AUTH, shop.bearer())));
    }

    private int paymentLabelsStatus(MerchantFixture actor) throws Exception {
        return status(api.call(get("/api/v1/cashier/outlets/" + actor.outletId() + "/fnb/payment-labels")
                .header(AUTH, actor.bearer())));
    }

    private void expirePaidRows(MerchantFixture shop) {
        api.jdbc().update("UPDATE subscriptions SET ends_at = now() - interval '1 minute' "
                + "WHERE tenant_id = ? AND plan_code <> 'FREETIER'", shop.tenantId());
    }

    /** Logs the account in and returns the account bearer. */
    private String login(String email) throws Exception {
        MvcResult login = api.send(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"), 200);
        return "Bearer " + ApiTestSupport.read(login, "$.data.accessToken");
    }

    private int selectMerchantStatus(String accountBearer, MerchantFixture shop) throws Exception {
        return status(api.call(post("/api/v1/auth/select-merchant").header(AUTH, accountBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + shop.tenantId() + "\",\"outletId\":\"" + shop.outletId() + "\"}")));
    }

    private String ownerEmail(MerchantFixture shop) {
        return api.jdbc().queryForObject("SELECT u.email FROM tenant_memberships m JOIN staff_users u ON u.id = m.user_id "
                + "WHERE m.tenant_id = ? AND m.role = 'OWNERMERCHANT'", String.class, shop.tenantId());
    }

    private Timestamp trialStartedAt(MerchantFixture shop) {
        return api.jdbc().queryForObject("SELECT premium_trial_started_at FROM tenants WHERE id = ?", Timestamp.class,
                shop.tenantId());
    }

    // ------------------------------------------------------------------ effective plan

    @Test
    void effectivePlanIgnoresExpiredRowsAndFollowsTheRowEndTime() throws Exception {
        MerchantFixture shop = api.onboardOwner("plan-rows", PlanCode.FREETIER);
        Instant now = Instant.now();
        assertEquals(PlanCode.FREETIER, plans.effectivePlan(shop.tenantId(), now));

        api.grantPlan(shop.tenantId(), PlanCode.PRO, now.minus(1, ChronoUnit.HOURS)); // an expired paid row
        assertEquals(PlanCode.FREETIER, plans.effectivePlan(shop.tenantId(), now));

        api.grantPlan(shop.tenantId(), PlanCode.PREMIUM, now.plus(1, ChronoUnit.HOURS));
        assertEquals(PlanCode.PREMIUM, plans.effectivePlan(shop.tenantId(), now));
        assertEquals(PlanCode.FREETIER, plans.effectivePlan(shop.tenantId(), now.plus(2, ChronoUnit.HOURS)));
    }

    // ------------------------------------------------------------------ cashier access follows the paid plan

    @Test
    void cashierAccessIsLostOnDowngradeAndRestoredOnUpgradeWhileTheOwnerKeepsHers() throws Exception {
        MerchantFixture owner = api.onboardOwner("plan-dg", PlanCode.PRO);
        MerchantFixture cashier = api.onboardCashier(owner, "plan-dg-c");
        assertEquals(200, paymentLabelsStatus(cashier));

        expirePaidRows(owner); // downgrade to the FREETIER base: nothing is deleted
        assertEquals(404, paymentLabelsStatus(cashier), "the guard refuses a cashier without PRO, like an unknown outlet");
        assertEquals(200, paymentLabelsStatus(owner), "owners keep access on every plan");
        // Login regression: the expired paid row stays ACTIVE in the table and must not make the owner's login ambiguous.
        assertEquals(200, selectMerchantStatus(login(ownerEmail(owner)), owner));

        api.grantPlan(owner.tenantId(), PlanCode.PRO, null); // upgrade again
        assertEquals(200, paymentLabelsStatus(cashier), "the same token works again; the membership was never touched");
    }

    @Test
    void aDowngradedCashierCannotSelectTheMerchantAndSeesNoOutletChoices() throws Exception {
        MerchantFixture owner = api.onboardOwner("plan-cl", PlanCode.PRO);
        api.onboardCashier(owner, "plan-cl-c");
        String cashierEmail = api.jdbc().queryForObject("SELECT email FROM staff_users WHERE full_name = ?", String.class,
                "Cashier plan-cl-c");
        String account = login(cashierEmail);
        assertEquals("1", ApiTestSupport.read(api.send(get("/api/v1/auth/outlets").header(AUTH, account), 200), "$.data.length()"));

        expirePaidRows(owner);

        assertEquals(409, selectMerchantStatus(account, owner));
        assertEquals("0", ApiTestSupport.read(api.send(get("/api/v1/auth/outlets").header(AUTH, account), 200), "$.data.length()"));
    }

    // ------------------------------------------------------------------ 402 on PRO features

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "POST|/api/v1/merchant/outlets/%s/cashier-invitations|{\"email\":\"kasir@example.com\"}",
            "POST|/api/v1/merchant/outlets/%s/fnb/orders/00000000-0000-4000-8000-000000000001/refund|{\"reason\":\"Salah pesan\"}",
            "GET|/api/v1/merchant/outlets/%s/staff|",
            "GET|/api/v1/merchant/outlets/%s/audit-logs|",
            "GET|/api/v1/merchant/outlets/%s/fnb/reports/sales/csv|"})
    void proFeaturesAnswer402WithTheUpgradeMessageOnFreetier(String method, String path, String body) throws Exception {
        MvcResult result = api.call(request(HttpMethod.valueOf(method), path.formatted(free.outletId()))
                .header(AUTH, free.bearer()).header("Idempotency-Key", "plan-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body));

        assertEquals(402, status(result), result.getResponse().getContentAsString());
        assertEquals("false", ApiTestSupport.read(result, "$.success"));
        assertEquals("402", ApiTestSupport.read(result, "$.code"));
        assertEquals("Fitur ini tersedia di paket Pro.", ApiTestSupport.read(result, "$.message"));
    }

    @ParameterizedTest(name = "trial started {0} days ago")
    @CsvSource({"1, 200", "13, 200", "15, 402"})
    void aRunningPremiumTrialUnlocksProFeaturesAndAnEndedOneDoesNot(int daysAgo, int expectedStatus) throws Exception {
        try {
            api.jdbc().update("UPDATE tenants SET premium_trial_started_at = now() - (? * interval '1 day') WHERE id = ?",
                    daysAgo, free.tenantId());
            assertEquals(expectedStatus, staffListStatus(free));
        } finally {
            api.jdbc().update("UPDATE tenants SET premium_trial_started_at = NULL WHERE id = ?", free.tenantId());
        }
    }

    // ------------------------------------------------------------------ Premium trial at the 50th paid order

    @Test
    void thePremiumTrialStartsAtTheFiftiethPaidOrderAndNotOnReplay() throws Exception {
        MerchantFixture shop = api.onboardOwner("plan-trial", PlanCode.FREETIER);
        String product = api.createProduct(shop, api.createCategory(shop, "Menu"), "Kopi", "10000");
        api.ensureShift(shop);
        // 48 earlier paid orders are inserted directly: 48 create+pay API round trips would dominate the runtime and the
        // trial rule only counts rows (paid, not cancelled); the 49th and 50th go through the real payment flow.
        api.jdbc().update("INSERT INTO sales_orders (id, tenant_id, outlet_id, order_number, idempotency_key, status, "
                + "payment_status, service_type, source, subtotal, discount_amount, tax_amount, service_charge_amount, "
                + "tax_rate_snapshot, service_charge_rate_snapshot, total, created_at, updated_at, version) "
                + "SELECT gen_random_uuid(), ?, ?, 'SEED-' || g, 'seed-key-' || g, 'COMPLETED', 'PAID', 'TAKEAWAY', 'POS', "
                + "1000, 0, 0, 0, 0, 0, 1000, now(), now(), 0 FROM generate_series(1, 48) g", shop.tenantId(), shop.outletId());

        api.payCashExact(shop, api.createPosOrder(shop, product, 1)); // the 49th paid order
        assertNull(trialStartedAt(shop));

        String fiftieth = api.createPosOrder(shop, product, 1);
        String total = api.orderTotal(shop, fiftieth);
        String key = "pay-" + UUID.randomUUID();
        MvcResult paid = api.pay(shop, fiftieth, "CASH", total, null, key);
        assertTrue(status(paid) == 200 || status(paid) == 201, paid.getResponse().getContentAsString());
        assertNotNull(trialStartedAt(shop));

        api.jdbc().update("UPDATE tenants SET premium_trial_started_at = NULL WHERE id = ?", shop.tenantId());
        MvcResult replay = api.pay(shop, fiftieth, "CASH", total, null, key);
        assertTrue(status(replay) == 200 || status(replay) == 201, replay.getResponse().getContentAsString());
        assertNull(trialStartedAt(shop), "an idempotent replay does not run the trial hook");
    }
}
