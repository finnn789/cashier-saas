package org.lite.project.cashiersaas.integration.support;

import com.jayway.jsonpath.JsonPath;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Drives the real HTTP API (security filter chain included) and prepares reference data. */
public final class ApiTestSupport {
    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;

    public ApiTestSupport(MockMvc mockMvc, DataSource dataSource) {
        this.mockMvc = mockMvc;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Reference data that migrations do not seed. */
    public void ensureFnbBusinessType() {
        jdbc.update("INSERT INTO business_types (id, code, name, is_active, created_at) "
                + "VALUES (gen_random_uuid(), 'FNB', 'Food and Beverage', true, now()) ON CONFLICT (code) DO NOTHING");
    }

    /** Removes all tenant data created by a test class; keeps schema history and reference data. */
    public void truncateTenantData() {
        List<String> tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                + "AND tablename NOT IN ('flyway_schema_history', 'business_types')", String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
        }
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /**
     * Register, OWNER intent, free tier FNB, business profile: all through the public API. The tenant also gets an
     * open-ended PREMIUM row next to the FREETIER base, so scenarios that need cashiers, staff, refunds or the AI
     * assistant work unchanged. Plan scenarios use {@link #onboardOwner(String, PlanCode)}.
     */
    public MerchantFixture onboardOwner(String label) throws Exception {
        return onboardOwner(label, PlanCode.PREMIUM);
    }

    /** As {@link #onboardOwner(String)}; {@code FREETIER} leaves the tenant on the FREETIER base row only. */
    public MerchantFixture onboardOwner(String label, PlanCode plan) throws Exception {
        MerchantFixture owner = onboardFreeOwner(label);
        if (plan != PlanCode.FREETIER) {
            grantPlan(owner.tenantId(), plan, null);
        }
        return owner;
    }

    /** Adds an ACTIVE paid subscription row beside the FREETIER base; {@code endsAt} null means open-ended. */
    public void grantPlan(UUID tenantId, PlanCode plan, Instant endsAt) {
        jdbc.update("INSERT INTO subscriptions (id, tenant_id, plan_code, status, starts_at, ends_at, created_at, "
                + "updated_at, version) VALUES (gen_random_uuid(), ?, ?, 'ACTIVE', now() - interval '1 minute', ?, now(), "
                + "now(), 0)", tenantId, plan.name(), endsAt == null ? null : Timestamp.from(endsAt));
    }

    private MerchantFixture onboardFreeOwner(String label) throws Exception {
        String email = label + "-" + UUID.randomUUID() + "@example.com";
        String accountToken = read(send(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Owner " + label + "\",\"email\":\"" + email
                        + "\",\"phoneNumber\":\"081234567890\",\"password\":\"Passw0rd!23\"}"), 201), "$.data.accessToken");
        String bearer = "Bearer " + accountToken;
        send(put("/api/v1/onboarding/intent").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"intent\":\"OWNER\"}"), 200);
        String tenantId = read(send(post("/api/v1/onboarding/freetier").header(HttpHeaders.AUTHORIZATION, bearer)
                .header("Idempotency-Key", "tier-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"businessTypeCode\":\"FNB\"}"), 200, 201),
                "$.data.tenantId");
        MvcResult profile = send(post("/api/v1/onboarding/" + tenantId + "/business-profile")
                .header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"Warung " + label + "\",\"outletName\":\"Outlet " + label
                        + "\",\"address\":\"Jl. Test 1\",\"phoneNumber\":\"08123456789\","
                        + "\"timeZone\":\"Asia/Jakarta\"}"), 200, 201);
        return new MerchantFixture(UUID.fromString(tenantId),
                UUID.fromString(read(profile, "$.data.outletId")), read(profile, "$.data.accessToken"));
    }

    /**
     * Registers a CASHIER_ONLY account, has the owner invite and the cashier claim it, then selects the merchant
     * and outlet; all through the real API. The fixture carries the same tenant/outlet as the owner and a CASHIER bearer.
     */
    public MerchantFixture onboardCashier(MerchantFixture owner, String label) throws Exception {
        String email = label + "-" + UUID.randomUUID() + "@example.com";
        String accountToken = read(send(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Cashier " + label + "\",\"email\":\"" + email
                        + "\",\"phoneNumber\":\"081234567890\",\"password\":\"Passw0rd!23\"}"), 201), "$.data.accessToken");
        String accountBearer = "Bearer " + accountToken;
        send(put("/api/v1/onboarding/intent").header(HttpHeaders.AUTHORIZATION, accountBearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"intent\":\"CASHIER_ONLY\"}"), 200);
        String code = read(send(post("/api/v1/merchant/outlets/" + owner.outletId() + "/cashier-invitations")
                .header(HttpHeaders.AUTHORIZATION, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"), 200, 201), "$.data.code");
        send(post("/api/v1/onboarding/cashier-invitations/claim").header(HttpHeaders.AUTHORIZATION, accountBearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"), 200);
        String accessToken = read(send(post("/api/v1/auth/select-merchant")
                .header(HttpHeaders.AUTHORIZATION, accountBearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + owner.tenantId() + "\",\"outletId\":\"" + owner.outletId() + "\"}"), 200),
                "$.data.accessToken");
        return new MerchantFixture(owner.tenantId(), owner.outletId(), accessToken);
    }

    public String createCategory(MerchantFixture merchant, String name) throws Exception {
        return read(send(post("/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/categories")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"displayOrder\":1}"), 200, 201), "$.data.id");
    }

    public String createProduct(MerchantFixture merchant, String categoryId, String name, String price)
            throws Exception {
        return read(send(post("/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/products")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":\"" + categoryId + "\",\"name\":\"" + name + "\",\"description\":\"d\","
                        + "\"price\":" + price + ",\"available\":true,\"displayOrder\":1}"), 200, 201), "$.data.id");
    }

    /** Opens the caller's cash shift (payments require one); fails unless it is opened. */
    public MvcResult openShift(MerchantFixture actor, String openingCash) throws Exception {
        return send(post("/api/v1/cashier/outlets/" + actor.outletId() + "/fnb/shifts")
                .header(HttpHeaders.AUTHORIZATION, actor.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"openingCash\":" + openingCash + "}"), 201);
    }

    /** Opens a zero-cash shift for the caller unless one is already open. */
    public void ensureShift(MerchantFixture actor) throws Exception {
        MvcResult current = call(get("/api/v1/cashier/outlets/" + actor.outletId() + "/fnb/shifts/current")
                .header(HttpHeaders.AUTHORIZATION, actor.bearer()));
        if (current.getResponse().getStatus() == 404) {
            openShift(actor, "0.00");
        }
    }

    /** First active payment label of the actor's outlet (onboarding seeds QRIS, Transfer, Debit). */
    public String firstActiveLabelId(MerchantFixture actor) throws Exception {
        return read(send(get("/api/v1/cashier/outlets/" + actor.outletId() + "/fnb/payment-labels")
                .header(HttpHeaders.AUTHORIZATION, actor.bearer()), 200), "$.data[0].id");
    }

    /** Creates an OPEN POS order (DINE_IN, customer "Budi") for {@code quantity} of the product; returns its id. */
    public String createPosOrder(MerchantFixture actor, String productId, int quantity) throws Exception {
        return read(send(post("/api/v1/cashier/outlets/" + actor.outletId() + "/fnb/orders")
                .header(HttpHeaders.AUTHORIZATION, actor.bearer()).header("Idempotency-Key", "pos-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceType\":\"DINE_IN\",\"customerLabel\":\"Budi\",\"items\":[{\"productId\":\""
                        + productId + "\",\"quantity\":" + quantity + ",\"optionIds\":[]}]}"), 201), "$.data.orderId");
    }

    public String orderTotal(MerchantFixture actor, String orderId) throws Exception {
        return read(send(get("/api/v1/cashier/outlets/" + actor.outletId() + "/fnb/orders/" + orderId)
                .header(HttpHeaders.AUTHORIZATION, actor.bearer()), 200), "$.data.total");
    }

    /** Payment request without asserting the status; {@code labelId} and {@code idempotencyKey} may be null (random key). */
    public MvcResult pay(MerchantFixture actor, String orderId, String method, String amount, String labelId,
                         String idempotencyKey) throws Exception {
        return call(post("/api/v1/cashier/outlets/" + actor.outletId() + "/fnb/orders/" + orderId + "/payments")
                .header(HttpHeaders.AUTHORIZATION, actor.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"" + method + "\",\"amountTendered\":" + amount + ",\"idempotencyKey\":\""
                        + (idempotencyKey == null ? "pay-" + UUID.randomUUID() : idempotencyKey) + "\""
                        + (labelId == null ? "" : ",\"labelId\":\"" + labelId + "\"") + "}"));
    }

    /** Pays the order in cash with the exact total (the caller must have an open shift); must succeed. */
    public MvcResult payCashExact(MerchantFixture actor, String orderId) throws Exception {
        MvcResult paid = pay(actor, orderId, "CASH", orderTotal(actor, orderId), null, null);
        assertSuccessStatus(paid);
        return paid;
    }

    /** Pays the order non-cash with the given label and the exact total; must succeed. */
    public MvcResult payNonCash(MerchantFixture actor, String orderId, String labelId) throws Exception {
        MvcResult paid = pay(actor, orderId, "MANUAL_NON_CASH", orderTotal(actor, orderId), labelId, null);
        assertSuccessStatus(paid);
        return paid;
    }

    private static void assertSuccessStatus(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        if (status != 200 && status != 201) {
            throw new AssertionError("Expected a successful payment but was " + status + ": "
                    + result.getResponse().getContentAsString());
        }
    }

    /** Performs the request and fails with the response body unless the status is one of the expected ones. */
    public MvcResult send(MockHttpServletRequestBuilder request, int... expectedStatuses) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        int actual = result.getResponse().getStatus();
        for (int expected : expectedStatuses) {
            // Some endpoints report "created" only in the body code while the HTTP status stays 200.
            boolean success = (actual == 200 || actual == 201) && (expected == 200 || expected == 201);
            if (actual == expected || success) {
                return result;
            }
        }
        throw new AssertionError("Expected status " + Arrays.toString(expectedStatuses) + " but was " + actual
                + " for " + result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
                + ": " + result.getResponse().getContentAsString());
    }

    public MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    public static String read(MvcResult result, String jsonPath) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return String.valueOf(value);
    }
}
