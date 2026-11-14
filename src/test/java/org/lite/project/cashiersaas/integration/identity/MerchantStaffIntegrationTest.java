package org.lite.project.cashiersaas.integration.identity;

import jakarta.servlet.http.Cookie;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Owner staff management (deactivate, reactivate, password reset), self password change, the cashier cancel rule
 * for discounted orders and the checkout audit row, against real PostgreSQL and the real security chain.
 *
 * <p>Status codes asserted (taken from the code, not guessed): an access token is a stateless JWT, so a deactivated
 * cashier's existing token still authenticates but every outlet call fails the membership guard with 404; the
 * revoked session makes refresh fail with 401; select-merchant for an inactive membership is 404.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantStaffIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final String PASSWORD = "Passw0rd!23";
    private static final String NEW_PASSWORD = "NewPassw0rd!45";
    private static final String REFRESH_COOKIE = "cashier_refresh";

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

    /** One login: the account bearer, the merchant bearer after select-merchant, and the refresh cookie. */
    private static final class Session {
        private final String accountBearer;
        private final String merchantBearer;
        private final Cookie refresh;

        private Session(String accountBearer, String merchantBearer, Cookie refresh) {
            this.accountBearer = accountBearer;
            this.merchantBearer = merchantBearer;
            this.refresh = refresh;
        }
    }

    // ------------------------------------------------------------------ helpers

    private String staff(MerchantFixture owner) {
        return "/api/v1/merchant/outlets/" + owner.outletId() + "/staff";
    }

    private String orders(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/orders";
    }

    private UUID userId(String fullName) {
        return api.jdbc().queryForObject("SELECT id FROM staff_users WHERE full_name = ?", UUID.class, fullName);
    }

    private String email(UUID userId) {
        return api.jdbc().queryForObject("SELECT email FROM staff_users WHERE id = ?", String.class, userId);
    }

    private int count(String sql, Object... args) {
        Integer value = api.jdbc().queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private int auditRows(MerchantFixture owner, String action, UUID entityId) {
        return count("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = ? AND entity_id = ?",
                owner.tenantId(), action, entityId);
    }

    private MvcResult login(String email, String password) throws Exception {
        return api.call(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private MvcResult selectMerchant(String accountBearer, MerchantFixture merchant) throws Exception {
        return api.call(post("/api/v1/auth/select-merchant").header(AUTH, accountBearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + merchant.tenantId()
                        + "\",\"outletId\":\"" + merchant.outletId() + "\"}"));
    }

    private Session openSession(String email, String password, MerchantFixture merchant) throws Exception {
        MvcResult login = login(email, password);
        assertEquals(200, login.getResponse().getStatus(), login.getResponse().getContentAsString());
        String account = "Bearer " + ApiTestSupport.read(login, "$.data.accessToken");
        MvcResult selected = selectMerchant(account, merchant);
        assertEquals(200, selected.getResponse().getStatus(), selected.getResponse().getContentAsString());
        return new Session(account, "Bearer " + ApiTestSupport.read(selected, "$.data.accessToken"),
                refreshCookie(login));
    }

    private static Cookie refreshCookie(MvcResult login) {
        String prefix = REFRESH_COOKIE + "=";
        for (String header : login.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith(prefix)) {
                int end = header.indexOf(';');
                return new Cookie(REFRESH_COOKIE, header.substring(prefix.length(), end < 0 ? header.length() : end));
            }
        }
        throw new AssertionError("login did not set the refresh cookie");
    }

    private int refreshStatus(Session session) throws Exception {
        return api.call(post("/api/v1/auth/refresh").with(csrf()).cookie(session.refresh)).getResponse().getStatus();
    }

    private int listOrdersStatus(MerchantFixture merchant, String bearer) throws Exception {
        return api.call(get(orders(merchant)).header(AUTH, bearer)).getResponse().getStatus();
    }

    private int staffCall(MerchantFixture ownerPath, String bearer, String suffix, boolean isPost) throws Exception {
        String url = staff(ownerPath) + suffix;
        return api.call(isPost ? post(url).header(AUTH, bearer) : get(url).header(AUTH, bearer))
                .getResponse().getStatus();
    }

    private MvcResult staffAction(MerchantFixture owner, UUID userId, String action, int... expected)
            throws Exception {
        return api.send(post(staff(owner) + "/" + userId + "/" + action).header(AUTH, owner.bearer()), expected);
    }

    private String createPosOrder(MerchantFixture merchant, String productId, String key) throws Exception {
        MvcResult created = api.send(post(orders(merchant)).header(AUTH, merchant.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceType\":\"DINE_IN\",\"customerLabel\":\"Budi\",\"items\":[{\"productId\":\""
                        + productId + "\",\"quantity\":1,\"note\":\"\",\"optionIds\":[]}]}"), 201);
        return ApiTestSupport.read(created, "$.data.orderId");
    }

    private MvcResult cancel(MerchantFixture actor, String orderId) throws Exception {
        return api.call(post(orders(actor) + "/" + orderId + "/cancel").header(AUTH, actor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Salah pesan\"}"));
    }

    private String orderStatus(String orderId) {
        return api.jdbc().queryForObject("SELECT status FROM sales_orders WHERE id = ?::uuid", String.class, orderId);
    }

    // ------------------------------------------------------------------ staff management

    @Test
    void ownerListsCashiersAndOtherTenantsSeeNothing() throws Exception {
        MerchantFixture owner = api.onboardOwner("list");
        api.onboardCashier(owner, "list");
        MerchantFixture otherOwner = api.onboardOwner("listother");
        UUID cashierId = userId("Cashier list");

        MvcResult list = api.send(get(staff(owner)).header(AUTH, owner.bearer()), 200);

        assertEquals("1", ApiTestSupport.read(list, "$.data.length()"));
        assertEquals(cashierId.toString(), ApiTestSupport.read(list, "$.data[0].userId"));
        assertEquals("Cashier list", ApiTestSupport.read(list, "$.data[0].fullName"));
        assertEquals(email(cashierId), ApiTestSupport.read(list, "$.data[0].email"));
        assertEquals("true", ApiTestSupport.read(list, "$.data[0].active"));
        assertFalse(ApiTestSupport.read(list, "$.data[0].joinedAt").isBlank());

        // Owner of tenant B: A's outlet path is 404, its own outlet has no cashiers.
        assertEquals(404, staffCall(owner, otherOwner.bearer(), "", false));
        assertEquals("0", ApiTestSupport.read(
                api.send(get(staff(otherOwner)).header(AUTH, otherOwner.bearer()), 200), "$.data.length()"));
    }

    @Test
    void deactivateEndsAccessAndSessionsThenReactivateRestoresLogin() throws Exception {
        MerchantFixture owner = api.onboardOwner("deact");
        MerchantFixture cashier = api.onboardCashier(owner, "deact");
        UUID cashierId = userId("Cashier deact");
        String email = email(cashierId);
        Session session = openSession(email, PASSWORD, owner);
        assertEquals(200, listOrdersStatus(owner, session.merchantBearer));

        MvcResult deactivated = staffAction(owner, cashierId, "deactivate", 200);

        assertEquals("false", ApiTestSupport.read(deactivated, "$.data.active"));
        // Stateless JWT: the old token still authenticates, but the membership guard rejects it (404).
        assertEquals(404, listOrdersStatus(owner, session.merchantBearer));
        assertEquals(404, listOrdersStatus(owner, cashier.bearer()));
        // The session is revoked, so refresh fails; every session of the user is revoked.
        assertEquals(401, refreshStatus(session));
        assertEquals(0, count("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", cashierId));
        // The account itself is untouched: login works, select-merchant does not (inactive membership -> 404).
        MvcResult relogin = login(email, PASSWORD);
        assertEquals(200, relogin.getResponse().getStatus());
        assertEquals(404, selectMerchant("Bearer " + ApiTestSupport.read(relogin, "$.data.accessToken"), owner)
                .getResponse().getStatus());
        assertEquals(1, auditRows(owner, "STAFF_DEACTIVATED", cashierId));
        assertEquals("false", ApiTestSupport.read(
                api.send(get(staff(owner)).header(AUTH, owner.bearer()), 200), "$.data[0].active"));

        // Idempotent: still 200, no second audit row.
        staffAction(owner, cashierId, "deactivate", 200);
        assertEquals(1, auditRows(owner, "STAFF_DEACTIVATED", cashierId));

        MvcResult reactivated = staffAction(owner, cashierId, "reactivate", 200);

        assertEquals("true", ApiTestSupport.read(reactivated, "$.data.active"));
        assertEquals(1, auditRows(owner, "STAFF_REACTIVATED", cashierId));
        staffAction(owner, cashierId, "reactivate", 200);
        assertEquals(1, auditRows(owner, "STAFF_REACTIVATED", cashierId));
        Session again = openSession(email, PASSWORD, owner);
        assertEquals(200, listOrdersStatus(owner, again.merchantBearer));
    }

    @Test
    void passwordResetReplacesThePasswordAndRevokesSessions() throws Exception {
        MerchantFixture owner = api.onboardOwner("reset");
        api.onboardCashier(owner, "reset");
        UUID cashierId = userId("Cashier reset");
        String email = email(cashierId);
        Session session = openSession(email, PASSWORD, owner);

        MvcResult reset = staffAction(owner, cashierId, "password-reset", 200);

        String temporary = ApiTestSupport.read(reset, "$.data.temporaryPassword");
        assertEquals(cashierId.toString(), ApiTestSupport.read(reset, "$.data.userId"));
        assertEquals(12, temporary.length());
        assertEquals(401, login(email, PASSWORD).getResponse().getStatus(), "old password must stop working");
        assertEquals(200, login(email, temporary).getResponse().getStatus(), "temporary password works");
        assertEquals(401, refreshStatus(session), "existing sessions are revoked");
        assertEquals(1, auditRows(owner, "STAFF_PASSWORD_RESET", cashierId));
        assertEquals(0, count("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND (details LIKE '%' || ? || '%' "
                + "OR reason LIKE '%' || ? || '%')", owner.tenantId(), temporary, temporary),
                "the temporary password must never be written to the audit log");
    }

    @Test
    void passwordResetIsRejectedWhenTheCashierBelongsToAnotherBusiness() throws Exception {
        MerchantFixture owner = api.onboardOwner("resetconflict");
        api.onboardCashier(owner, "resetconflict");
        MerchantFixture otherOwner = api.onboardOwner("resetconflictb");
        UUID cashierId = userId("Cashier resetconflict");
        api.jdbc().update("INSERT INTO tenant_memberships (id, tenant_id, user_id, role, active, created_at, "
                + "updated_at, version) VALUES (gen_random_uuid(), ?, ?, 'CASHIER', true, now(), now(), 0)",
                otherOwner.tenantId(), cashierId);

        MvcResult rejected = api.call(post(staff(owner) + "/" + cashierId + "/password-reset")
                .header(AUTH, owner.bearer()));

        assertEquals(409, rejected.getResponse().getStatus());
        assertTrue(rejected.getResponse().getContentAsString().contains("terhubung ke bisnis lain"));
        assertEquals(200, login(email(cashierId), PASSWORD).getResponse().getStatus(), "password unchanged");
        assertEquals(0, auditRows(owner, "STAFF_PASSWORD_RESET", cashierId));
    }

    @Test
    void onlyTheOwnerOfTheOutletCanManageItsStaff() throws Exception {
        MerchantFixture owner = api.onboardOwner("authz");
        MerchantFixture cashier = api.onboardCashier(owner, "authz");
        MerchantFixture otherOwner = api.onboardOwner("authzother");
        UUID cashierId = userId("Cashier authz");
        UUID ownerId = userId("Owner authz");

        // Cashier role: forbidden by the security config. No token: unauthorized.
        assertEquals(403, staffCall(owner, cashier.bearer(), "", false));
        for (String action : new String[] {"deactivate", "reactivate", "password-reset"}) {
            assertEquals(403, staffCall(owner, cashier.bearer(), "/" + cashierId + "/" + action, true), action);
            assertEquals(401, api.call(post(staff(owner) + "/" + cashierId + "/" + action)).getResponse().getStatus());
            // Owner of another tenant: the outlet is not theirs (404), and the cashier stays untouched.
            assertEquals(404, staffCall(owner, otherOwner.bearer(), "/" + cashierId + "/" + action, true), action);
            // Their own outlet path with a foreign user id: also 404, no evidence the user exists.
            assertEquals(404, staffCall(otherOwner, otherOwner.bearer(), "/" + cashierId + "/" + action, true));
            // Owners and unknown users are not valid targets.
            assertEquals(404, staffCall(owner, owner.bearer(), "/" + ownerId + "/" + action, true), action);
            assertEquals(404, staffCall(owner, owner.bearer(), "/" + UUID.randomUUID() + "/" + action, true), action);
        }
        assertEquals(1, count("SELECT count(*) FROM tenant_memberships WHERE tenant_id = ? AND user_id = ? "
                + "AND active = true", owner.tenantId(), cashierId));
        assertEquals(0, count("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action LIKE 'STAFF_%'",
                owner.tenantId()));
    }

    // ------------------------------------------------------------------ self password change

    @Test
    void changingOwnPasswordReplacesItAndKeepsOnlyTheCallersSession() throws Exception {
        MerchantFixture owner = api.onboardOwner("self");
        api.onboardCashier(owner, "self");
        String email = email(userId("Cashier self"));
        Session caller = openSession(email, PASSWORD, owner);
        Session other = openSession(email, PASSWORD, owner);
        String body = "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}";

        assertEquals(401, api.call(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .content(body)).getResponse().getStatus(), "anonymous");
        assertEquals(401, api.call(post("/api/v1/auth/password").header(AUTH, caller.merchantBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong-password-1\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .getResponse().getStatus(), "wrong current password");
        assertEquals(400, api.call(post("/api/v1/auth/password").header(AUTH, caller.merchantBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .getResponse().getStatus(), "same password");
        assertEquals(400, api.call(post("/api/v1/auth/password").header(AUTH, caller.merchantBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}"))
                .getResponse().getStatus(), "weak new password");
        assertEquals(200, login(email, PASSWORD).getResponse().getStatus(), "failed attempts changed nothing");

        api.send(post("/api/v1/auth/password").header(AUTH, caller.merchantBearer)
                .contentType(MediaType.APPLICATION_JSON).content(body), 200);

        assertEquals(401, login(email, PASSWORD).getResponse().getStatus(), "old password fails");
        assertEquals(200, login(email, NEW_PASSWORD).getResponse().getStatus(), "new password works");
        assertEquals(200, refreshStatus(caller), "the caller's own session survives");
        assertEquals(401, refreshStatus(other), "other sessions are revoked");
    }

    @Test
    void accountScopedTokenCanChangeThePasswordToo() throws Exception {
        api.onboardOwner("selfacct");
        String email = email(userId("Owner selfacct"));
        MvcResult login = login(email, PASSWORD);
        assertEquals(200, login.getResponse().getStatus());
        String accountBearer = "Bearer " + ApiTestSupport.read(login, "$.data.accessToken");

        api.send(post("/api/v1/auth/password").header(AUTH, accountBearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"), 200);

        assertEquals(401, login(email, PASSWORD).getResponse().getStatus());
        assertEquals(200, login(email, NEW_PASSWORD).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ cancel rule and checkout audit

    @Test
    void cashierCannotCancelADiscountedOrderButTheOwnerCan() throws Exception {
        MerchantFixture owner = api.onboardOwner("cancel");
        MerchantFixture cashier = api.onboardCashier(owner, "cancel");
        String productId = api.createProduct(owner, api.createCategory(owner, "Minuman"), "Es Teh", "25000");
        String discounted = createPosOrder(owner, productId, "pos-" + UUID.randomUUID());
        String plain = createPosOrder(owner, productId, "pos-" + UUID.randomUUID());
        api.send(post("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/orders/" + discounted + "/discount")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NOMINAL\",\"value\":1000,\"reason\":\"Promo\"}"), 200, 201);

        MvcResult refused = cancel(cashier, discounted);

        assertEquals(403, refused.getResponse().getStatus());
        assertTrue(refused.getResponse().getContentAsString().contains("diskon"));
        assertEquals("OPEN", orderStatus(discounted));
        assertEquals(0, auditRows(owner, "FNB_ORDER_CANCELLED", UUID.fromString(discounted)));

        assertEquals(200, cancel(cashier, plain).getResponse().getStatus(), "no discount: the cashier may cancel");
        assertEquals("CANCELLED", orderStatus(plain));

        assertEquals(200, cancel(owner, discounted).getResponse().getStatus(), "owner may cancel a discounted order");
        assertEquals("CANCELLED", orderStatus(discounted));
        assertEquals(1, auditRows(owner, "FNB_ORDER_CANCELLED", UUID.fromString(discounted)));
    }

    @Test
    void checkoutWritesOneCreatedAuditRowAndAnIdempotentReplayWritesNone() throws Exception {
        MerchantFixture owner = api.onboardOwner("audit");
        MerchantFixture cashier = api.onboardCashier(owner, "audit");
        String productId = api.createProduct(owner, api.createCategory(owner, "Makanan"), "Nasi Goreng", "25000");
        String key = "pos-" + UUID.randomUUID();
        String body = "{\"serviceType\":\"DINE_IN\",\"customerLabel\":\"Budi\",\"items\":[{\"productId\":\""
                + productId + "\",\"quantity\":2,\"note\":\"\",\"optionIds\":[]}]}";

        MvcResult first = api.send(post(orders(cashier)).header(AUTH, cashier.bearer()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body), 201);
        UUID orderId = UUID.fromString(ApiTestSupport.read(first, "$.data.orderId"));
        String orderNumber = ApiTestSupport.read(first, "$.data.orderNumber");

        assertEquals(1, auditRows(owner, "FNB_ORDER_CREATED", orderId));
        String details = api.jdbc().queryForObject("SELECT details FROM audit_logs WHERE tenant_id = ? "
                + "AND action = 'FNB_ORDER_CREATED' AND entity_id = ?", String.class, owner.tenantId(), orderId);
        assertTrue(details.startsWith("orderNumber=" + orderNumber + " total="), details);
        assertEquals("SALES_ORDER", api.jdbc().queryForObject("SELECT entity_type FROM audit_logs WHERE tenant_id = ? "
                + "AND action = 'FNB_ORDER_CREATED' AND entity_id = ?", String.class, owner.tenantId(), orderId));

        api.send(post(orders(cashier)).header(AUTH, cashier.bearer()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body), 200, 201);

        assertEquals(1, auditRows(owner, "FNB_ORDER_CREATED", orderId), "replay must not write a second row");
        assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'FNB_ORDER_CREATED'",
                owner.tenantId()));
    }
}
