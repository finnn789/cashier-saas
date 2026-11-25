package org.lite.project.cashiersaas.integration.fnb;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.lite.project.cashiersaas.integration.support.MerchantFixture;
import org.lite.project.cashiersaas.service.DailySummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Daily summary 23:00 fallback and retry (TASK-080 D1) with a fake clock: the package-private
 * {@code DailySummaryService.runFallback(Instant)} is called by reflection with fixed instants in 2031, so the
 * outcome never depends on the real time of day. A JDK HttpServer stands in for Ai-services. The scheduler is
 * pushed to a 24 h interval; every assertion filters stub calls by its own outlet and business date.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DailySummaryFallbackIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String API_KEY = "test-only-ai-internal-key-0123456789";
    private static final String SUMMARY_PATH = "/api/v1/internal/daily-summaries";

    private static final List<ReceivedCall> CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger SUMMARY_STATUS = new AtomicInteger(200);
    private static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void dailySummary(DynamicPropertyRegistry registry) {
        registry.add("app.daily-summary.enabled", () -> "true");
        registry.add("app.daily-summary.fallback-interval", () -> "PT24H");
        registry.add("app.ai-service.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("app.ai-service.api-key", () -> API_KEY);
        registry.add("app.ai-service.retry-backoff", () -> "20ms,40ms");
    }

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private DailySummaryService dailySummary;

    private ApiTestSupport api;
    private JdbcTemplate jdbc;
    private MerchantFixture jakarta;
    private MerchantFixture newYork;
    private Method runFallback;

    @BeforeAll
    void setUpAll() throws Exception {
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        api.ensureFnbBusinessType();
        jdbc = api.jdbc();
        jakarta = api.onboardOwner("fb-jakarta");
        newYork = api.onboardOwner("fb-newyork");
        jdbc.update("UPDATE outlets SET time_zone = 'America/New_York' WHERE id = ?", newYork.outletId());
        runFallback = DailySummaryService.class.getDeclaredMethod("runFallback", Instant.class);
        runFallback.setAccessible(true);
    }

    @AfterAll
    void cleanUp() {
        STUB.stop(0);
        api.truncateTenantData();
    }

    @BeforeEach
    void resetStub() {
        SUMMARY_STATUS.set(200);
        CALLS.clear();
    }

    @Test
    void nothingIsCreatedBefore2300InTheOutletZone() throws Exception {
        runAt("2031-03-03T15:59:00Z"); // 22:59 in Jakarta
        assertEquals(0, rows(jakarta, "2031-03-03"));
        assertEquals(0, callsFor(jakarta, "2031-03-03").size());
    }

    @Test
    void at2300CreatesOneFallbackRowPerOutletZoneSendsItOnceAndNeverRepeats() throws Exception {
        runAt("2031-03-13T16:00:00Z"); // 23:00 in Jakarta, 12:00 in New York

        Map<String, Object> row = jdbc.queryForMap("SELECT \"trigger\", status, attempts FROM daily_summary_dispatches "
                + "WHERE outlet_id = ? AND business_date = DATE '2031-03-13'", jakarta.outletId());
        assertEquals("FALLBACK", row.get("trigger"));
        assertEquals("SENT", row.get("status"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());
        assertEquals(0, rows(newYork, "2031-03-13"), "midday in New York: not yet");

        List<ReceivedCall> sent = callsFor(jakarta, "2031-03-13");
        assertEquals(1, sent.size());
        assertEquals(API_KEY, sent.get(0).serviceKey);
        assertEquals(jakarta.tenantId().toString(), JsonPath.<String>read(sent.get(0).body, "$.tenant_id"));
        assertEquals(jakarta.outletId().toString(), JsonPath.<String>read(sent.get(0).body, "$.outlet_id"));
        assertEquals("2031-03-13", JsonPath.<String>read(sent.get(0).body, "$.business_date"));

        runAt("2031-03-13T16:30:00Z"); // later the same evening: once per outlet per business day
        assertEquals(1, rows(jakarta, "2031-03-13"));
        assertEquals(1, callsFor(jakarta, "2031-03-13").size());
    }

    @Test
    void aSummaryAlreadyCreatedByTheShiftCloseBlocksTheFallbackForThatDay() throws Exception {
        insertDispatch(jakarta, "2031-03-23", "SHIFT_CLOSED", "SENT", 1);

        runAt("2031-03-23T16:30:00Z");

        assertEquals(1, rows(jakarta, "2031-03-23"));
        assertEquals(0, callsFor(jakarta, "2031-03-23").size());
    }

    @Test
    void aFailedSendIsRetriedAfterTenMinutesButStaleRowsAreNot() throws Exception {
        insertDispatch(newYork, "2031-03-28", "FALLBACK", "PENDING", 0); // more than 2 days older than the run below
        SUMMARY_STATUS.set(500);

        runAt("2031-04-02T16:30:00Z");
        Map<String, Object> failed = jdbc.queryForMap("SELECT status, attempts FROM daily_summary_dispatches "
                + "WHERE outlet_id = ? AND business_date = DATE '2031-04-02'", jakarta.outletId());
        assertEquals("FAILED", failed.get("status"));
        assertEquals(1, ((Number) failed.get("attempts")).intValue());
        assertEquals(1, callsFor(jakarta, "2031-04-02").size());

        SUMMARY_STATUS.set(200);
        runAt("2031-04-02T16:41:00Z");
        Map<String, Object> retried = jdbc.queryForMap("SELECT status, attempts FROM daily_summary_dispatches "
                + "WHERE outlet_id = ? AND business_date = DATE '2031-04-02'", jakarta.outletId());
        assertEquals("SENT", retried.get("status"));
        assertEquals(2, ((Number) retried.get("attempts")).intValue());
        assertEquals(2, callsFor(jakarta, "2031-04-02").size());

        Map<String, Object> stale = jdbc.queryForMap("SELECT status, attempts FROM daily_summary_dispatches "
                + "WHERE outlet_id = ? AND business_date = DATE '2031-03-28'", newYork.outletId());
        assertEquals("PENDING", stale.get("status"), "rows older than today minus 2 days are not retried");
        assertEquals(0, ((Number) stale.get("attempts")).intValue());
        assertEquals(0, callsFor(newYork, "2031-03-28").size());
    }

    @Test
    void aTenantBelowPremiumGetsNoFallbackRowAndItsPendingRowIsNotRetried() throws Exception {
        MerchantFixture free = api.onboardOwner("fb-free", PlanCode.FREETIER);
        insertDispatch(free, "2031-05-01", "SHIFT_CLOSED", "PENDING", 0);

        runAt("2031-05-02T16:30:00Z"); // 23:30 in Jakarta; the row of the 1st is inside the 2-day retry window

        assertEquals(0, rows(free, "2031-05-02"), "the sweep skips tenants without an effective PREMIUM plan");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM daily_summary_dispatches WHERE outlet_id = ?",
                String.class, free.outletId()));
        assertEquals(0, callsFor(free, "2031-05-01").size() + callsFor(free, "2031-05-02").size());
    }

    @Test
    void aSentRowIsNeverSentAgain() throws Exception {
        insertDispatch(jakarta, "2031-04-12", "FALLBACK", "SENT", 1);
        jdbc.update("UPDATE daily_summary_dispatches SET last_attempt_at = now() - interval '1 day' "
                + "WHERE outlet_id = ? AND business_date = DATE '2031-04-12'", jakarta.outletId());

        runAt("2031-04-12T17:00:00Z");

        assertEquals(0, callsFor(jakarta, "2031-04-12").size());
        assertTrue(rows(jakarta, "2031-04-12") == 1);
    }

    // ----- helpers -----

    private void runAt(String instant) throws Exception {
        runFallback.invoke(dailySummary, Instant.parse(instant));
    }

    private int rows(MerchantFixture shop, String date) {
        Integer value = jdbc.queryForObject("SELECT count(*) FROM daily_summary_dispatches WHERE outlet_id = ? "
                + "AND business_date = ?", Integer.class, shop.outletId(), LocalDate.parse(date));
        return value == null ? -1 : value;
    }

    private void insertDispatch(MerchantFixture shop, String date, String trigger, String status, int attempts) {
        jdbc.update("INSERT INTO daily_summary_dispatches (id, created_at, updated_at, version, tenant_id, outlet_id, "
                + "business_date, \"trigger\", status, attempts) VALUES (gen_random_uuid(), now(), now(), 0, ?, ?, ?, ?, ?, ?)",
                shop.tenantId(), shop.outletId(), LocalDate.parse(date), trigger, status, attempts);
    }

    private static List<ReceivedCall> callsFor(MerchantFixture shop, String date) {
        return CALLS.stream().filter(call -> call.path.equals(SUMMARY_PATH)
                && shop.outletId().toString().equals(JsonPath.<String>read(call.body, "$.outlet_id"))
                && date.equals(JsonPath.<String>read(call.body, "$.business_date"))).toList();
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", DailySummaryFallbackIntegrationTest::record);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start the Ai-services stub", exception);
        }
    }

    /** Member-sync pushes (made by onboarding) always get 200; only the summary path follows SUMMARY_STATUS. */
    private static void record(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean summary = SUMMARY_PATH.equals(path);
            if (summary) {
                CALLS.add(new ReceivedCall(path, exchange.getRequestHeaders().getFirst("X-Internal-Service-Key"), body));
            }
            int status = summary ? SUMMARY_STATUS.get() : 200;
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
        private final String path;
        private final String serviceKey;
        private final String body;

        private ReceivedCall(String path, String serviceKey, String body) {
            this.path = path;
            this.serviceKey = serviceKey;
            this.body = body;
        }
    }
}
