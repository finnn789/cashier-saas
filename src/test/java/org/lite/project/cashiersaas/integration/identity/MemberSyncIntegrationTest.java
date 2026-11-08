package org.lite.project.cashiersaas.integration.identity;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.lite.project.cashiersaas.integration.support.MerchantFixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Staff member sync (docs/contracts/member-sync.md) end to end: real onboarding API and PostgreSQL, with a JDK
 * HttpServer standing in for Ai-services. The push runs after commit on a background pool, so every assertion
 * waits on the stub's queue instead of sleeping.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberSyncIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String API_KEY = "test-only-ai-internal-key-0123456789";
    private static final Path FIXTURE = Path.of("docs", "contracts", "member-sync.example.json");
    private static final long WAIT_SECONDS = 15;
    private static final long QUIET_MILLIS = 600;

    private static final BlockingQueue<ReceivedCall> CALLS = new LinkedBlockingQueue<>();
    private static final AtomicInteger STUB_STATUS = new AtomicInteger(200);
    private static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void aiService(DynamicPropertyRegistry registry) {
        registry.add("app.ai-service.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("app.ai-service.api-key", () -> API_KEY);
        registry.add("app.ai-service.retry-backoff", () -> "20ms,40ms");
    }

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;

    private ApiTestSupport api;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUpAll() {
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        api.ensureFnbBusinessType();
        jdbc = api.jdbc();
    }

    @AfterAll
    void cleanUp() {
        STUB.stop(0);
        api.truncateTenantData();
    }

    @BeforeEach
    void resetStub() {
        CALLS.clear();
        STUB_STATUS.set(200);
    }

    @Test
    void completingTheOwnerProfilePushesOneContractShapedPut() throws Exception {
        MerchantFixture merchant = api.onboardOwner("sync");

        ReceivedCall call = await();
        UUID ownerId = ownerUserId(merchant.tenantId());
        assertEquals("PUT", call.method);
        assertEquals("/api/v1/internal/tenants/" + merchant.tenantId() + "/members/" + ownerId, call.path);
        assertEquals(API_KEY, call.serviceKey);
        assertTrue(MediaType.parseMediaType(call.contentType).isCompatibleWith(MediaType.APPLICATION_JSON));
        Map<String, Object> body = JsonPath.read(call.body, "$");
        Map<String, Object> fixture = fixtureBody("owner");
        assertEquals(fixture.keySet(), body.keySet(), "payload must have exactly the contract fields");
        assertEquals("Warung sync", body.get("tenant_name"));
        assertEquals("Owner sync", body.get("full_name"));
        assertEquals(fixture.get("phone_number"), body.get("phone_number"),
                "register phone 081234567890 is stored and pushed normalized");
        assertEquals(fixture.get("role"), body.get("role"));
        assertEquals(fixture.get("status"), body.get("status"));
        assertNoFurtherCall();
    }

    @Test
    void claimingACashierInvitationPushesTheCashierAndReclaimHealsWithAnIdenticalPut() throws Exception {
        MerchantFixture merchant = api.onboardOwner("cash");
        await();
        String cashierEmail = "siti-" + UUID.randomUUID() + "@example.com";
        String cashierToken = registerAccount("Siti Rahma", cashierEmail, "0811-1222-333");
        String cashierBearer = "Bearer " + cashierToken;
        api.send(put("/api/v1/onboarding/intent").header(HttpHeaders.AUTHORIZATION, cashierBearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"intent\":\"CASHIER_ONLY\"}"), 200);
        String code = ApiTestSupport.read(api.send(post("/api/v1/merchant/outlets/" + merchant.outletId()
                + "/cashier-invitations").header(HttpHeaders.AUTHORIZATION, merchant.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + cashierEmail + "\"}"), 200, 201),
                "$.data.code");
        assertNoFurtherCall();

        api.send(claim(cashierBearer, code), 200);

        ReceivedCall first = await();
        UUID cashierId = userIdByEmail(cashierEmail);
        assertEquals("PUT", first.method);
        assertEquals("/api/v1/internal/tenants/" + merchant.tenantId() + "/members/" + cashierId, first.path);
        assertEquals(API_KEY, first.serviceKey);
        Map<String, Object> body = JsonPath.read(first.body, "$");
        Map<String, Object> fixture = fixtureBody("cashier");
        assertEquals(fixture.keySet(), body.keySet());
        assertEquals("Warung cash", body.get("tenant_name"));
        assertEquals("Siti Rahma", body.get("full_name"));
        assertEquals(fixture.get("phone_number"), body.get("phone_number"));
        assertEquals("CASHIER", body.get("role"));
        assertEquals("ACTIVE", body.get("status"));
        assertNoFurtherCall();

        // The same user claiming again is the idempotent "already claimed" branch and must push again.
        api.send(claim(cashierBearer, code), 200);

        ReceivedCall second = await();
        assertEquals(first.path, second.path);
        assertEquals(first.body, second.body);
        assertNoFurtherCall();
    }

    @Test
    void serverErrorsDoNotBreakOnboardingAndAreRetriedThreeTimes() throws Exception {
        STUB_STATUS.set(500);

        MerchantFixture merchant = api.onboardOwner("down");

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals("PUT", await().method, "attempt " + attempt);
        }
        assertNoFurtherCall();
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class,
                merchant.tenantId()));
    }

    @Test
    void clientErrorsAreNotRetriedAndDoNotBreakOnboarding() throws Exception {
        STUB_STATUS.set(422);

        MerchantFixture merchant = api.onboardOwner("rejected");

        assertEquals("PUT", await().method);
        assertNoFurtherCall();
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class,
                merchant.tenantId()));
    }

    @Test
    void failedSyncIsHealedByTheIdempotentRecompleteOfTheSameProfile() throws Exception {
        STUB_STATUS.set(500);
        String email = "heal-" + UUID.randomUUID() + "@example.com";
        String bearer = "Bearer " + registerAccount("Owner heal", email, "081234567890");
        api.send(put("/api/v1/onboarding/intent").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"intent\":\"OWNER\"}"), 200);
        String tenantId = ApiTestSupport.read(api.send(post("/api/v1/onboarding/freetier")
                .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", "tier-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"businessTypeCode\":\"FNB\"}"), 200, 201),
                "$.data.tenantId");
        String profile = "{\"businessName\":\"Warung heal\",\"outletName\":\"Outlet heal\",\"address\":\"Jl. Test 1\","
                + "\"phoneNumber\":\"08123456789\",\"timeZone\":\"Asia/Jakarta\"}";
        api.send(post("/api/v1/onboarding/" + tenantId + "/business-profile").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(profile), 200, 201);
        for (int attempt = 1; attempt <= 3; attempt++) {
            await();
        }
        assertNoFurtherCall();

        STUB_STATUS.set(200);
        api.send(post("/api/v1/onboarding/" + tenantId + "/business-profile").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(profile), 200, 201);

        ReceivedCall healed = await();
        assertEquals("PUT", healed.method);
        Map<String, Object> body = JsonPath.read(healed.body, "$");
        assertEquals("Warung heal", body.get("tenant_name"));
        assertEquals("OWNERMERCHANT", body.get("role"));
        assertNoFurtherCall();
    }

    // ----- helpers -----

    private MockHttpServletRequestBuilder claim(String bearer, String code) {
        return post("/api/v1/onboarding/cashier-invitations/claim").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}");
    }

    private String registerAccount(String fullName, String email, String phone) throws Exception {
        return ApiTestSupport.read(api.send(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + fullName + "\",\"email\":\"" + email + "\",\"phoneNumber\":\"" + phone
                        + "\",\"password\":\"Passw0rd!23\"}"), 201), "$.data.accessToken");
    }

    private UUID ownerUserId(UUID tenantId) {
        return jdbc.queryForObject("SELECT user_id FROM tenant_memberships WHERE tenant_id = ? "
                + "AND role = 'OWNERMERCHANT'", (rs, row) -> rs.getObject(1, UUID.class), tenantId);
    }

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM staff_users WHERE email = ?",
                (rs, row) -> rs.getObject(1, UUID.class), email);
    }

    private static Map<String, Object> fixtureBody(String name) throws IOException {
        return JsonPath.read(Files.readString(FIXTURE), "$." + name + ".body");
    }

    private static ReceivedCall await() throws InterruptedException {
        ReceivedCall call = CALLS.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(call, "Ai-services stub did not receive the expected call");
        return call;
    }

    private static void assertNoFurtherCall() throws InterruptedException {
        assertNull(CALLS.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS), "unexpected extra call to Ai-services");
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", MemberSyncIntegrationTest::record);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start the Ai-services stub", exception);
        }
    }

    private static void record(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            CALLS.add(new ReceivedCall(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Internal-Service-Key"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            int status = STUB_STATUS.get();
            if (status == 200) {
                byte[] response = "{\"status\":\"ACTIVE\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.sendResponseHeaders(status, -1);
            }
        }
    }

    private static final class ReceivedCall {
        private final String method;
        private final String path;
        private final String serviceKey;
        private final String contentType;
        private final String body;

        private ReceivedCall(String method, String path, String serviceKey, String contentType, String body) {
            this.method = method;
            this.path = path;
            this.serviceKey = serviceKey;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
