package org.lite.project.cashiersaas.unit.ratelimit;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.component.HttpRateLimiter;
import org.lite.project.cashiersaas.component.RateLimitFilter;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.configuration.RateLimitProperties;
import org.lite.project.cashiersaas.enums.Role;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RateLimitFilterTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String MENU = "/api/v1/public/fnb/menu/";
    private static final String TICKET = "/api/v1/cashier/outlets/o1/fnb/realtime-ticket";

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final RateLimitProperties properties = RateLimitTestProperties.defaults();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private RateLimitFilter filter() {
        return new RateLimitFilter(new HttpRateLimiter(properties, clock::get), objectMapper);
    }

    private static RateLimitProperties.Limit limit(int burst) {
        return new RateLimitProperties.Limit(60, burst);
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr(ip);
        return request;
    }

    /** Runs one request through the filter and returns the response; the chain is exposed via the holder. */
    private static final class Result {
        private final MockHttpServletResponse response = new MockHttpServletResponse();
        private final MockFilterChain chain = new MockFilterChain();

        boolean passed() {
            return chain.getRequest() != null;
        }
    }

    private Result run(RateLimitFilter filter, MockHttpServletRequest request) throws ServletException, IOException {
        Result result = new Result();
        filter.doFilter(request, result.response, result.chain);
        return result;
    }

    private int statusOf(RateLimitFilter filter, String method, String path, String ip)
            throws ServletException, IOException {
        return run(filter, request(method, path, ip)).response.getStatus();
    }

    private static void authenticateAs(Object principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void loginIsLimitedPerIpAndOnlyForPost() throws Exception {
        properties.setAuth(limit(2));
        properties.setAnonymousOther(limit(100));
        RateLimitFilter filter = filter();

        assertEquals(200, statusOf(filter, "POST", LOGIN, "1.1.1.1"));
        assertEquals(200, statusOf(filter, "POST", LOGIN, "1.1.1.1"));
        assertEquals(429, statusOf(filter, "POST", LOGIN, "1.1.1.1"));
        assertEquals(200, statusOf(filter, "POST", LOGIN, "2.2.2.2"), "another IP has its own bucket");
        assertEquals(200, statusOf(filter, "GET", LOGIN, "1.1.1.1"), "GET is not an AUTH request");
        assertEquals(429, statusOf(filter, "POST", "/api/v1/auth/register", "1.1.1.1"), "shared AUTH bucket");
        assertEquals(429, statusOf(filter, "POST", "/api/v1/auth/refresh", "1.1.1.1"), "shared AUTH bucket");
    }

    @Test
    void publicMenuConsumesBothThePublicGroupAndTheQrTokenGroup() throws Exception {
        properties.setPublicRead(limit(2));
        properties.setQrToken(limit(100));
        RateLimitFilter filter = filter();

        assertEquals(200, statusOf(filter, "GET", MENU + "tokA", "1.1.1.1"));
        assertEquals(200, statusOf(filter, "GET", MENU + "tokB", "1.1.1.1"));
        assertEquals(429, statusOf(filter, "GET", MENU + "tokC", "1.1.1.1"), "PUBLIC_READ per IP was consumed");

        properties.setPublicRead(limit(100));
        properties.setQrToken(limit(2));
        RateLimitFilter second = filter();

        assertEquals(200, statusOf(second, "GET", MENU + "tokX", "1.1.1.1"));
        assertEquals(200, statusOf(second, "GET", MENU + "tokX", "2.2.2.2"));
        assertEquals(429, statusOf(second, "GET", MENU + "tokX", "3.3.3.3"), "QR_TOKEN is shared across IPs");
        assertEquals(200, statusOf(second, "GET", MENU + "tokY", "3.3.3.3"), "another token is independent");
    }

    @Test
    void publicOrderWritesUseTheirOwnGroupAndTheQrTokenOfTheOrdersPath() throws Exception {
        properties.setPublicOrderWrite(limit(100));
        properties.setPublicRead(limit(100));
        properties.setQrToken(limit(2));
        RateLimitFilter filter = filter();

        assertEquals(200, statusOf(filter, "POST", MENU + "tok/orders", "1.1.1.1"));
        assertEquals(200, statusOf(filter, "POST", MENU + "tok/orders", "2.2.2.2"));
        assertEquals(429, statusOf(filter, "POST", MENU + "tok/orders", "3.3.3.3"));
        assertEquals(429, statusOf(filter, "GET", MENU + "tok", "4.4.4.4"), "menu and orders share the token bucket");

        properties.setPublicOrderWrite(limit(1));
        RateLimitFilter strict = filter();
        assertEquals(200, statusOf(strict, "POST", MENU + "t1/orders", "5.5.5.5"));
        assertEquals(429, statusOf(strict, "POST", MENU + "t2/orders", "5.5.5.5"), "PUBLIC_ORDER_WRITE per IP");
        assertEquals(200, statusOf(strict, "GET", MENU + "t3", "5.5.5.5"), "reads use PUBLIC_READ");
    }

    @Test
    void otherPublicPathsAreNotQrTokenLimited() throws Exception {
        properties.setQrToken(limit(1));
        RateLimitFilter filter = filter();

        for (int index = 0; index < 5; index++) {
            assertEquals(200, statusOf(filter, "GET", "/api/v1/public/fnb/orders/abc/status", "1.1.1.1"));
        }
    }

    @Test
    void realtimeTicketIsLimitedPerAuthenticatedUser() throws Exception {
        properties.setRealtimeTicket(limit(1));
        properties.setAuthenticated(limit(100));
        RateLimitFilter filter = filter();

        authenticateAs("user-1");
        assertEquals(200, statusOf(filter, "POST", TICKET, "1.1.1.1"));
        assertEquals(429, statusOf(filter, "POST", TICKET, "2.2.2.2"), "the key is the user, not the IP");
        assertEquals(200, statusOf(filter, "GET", "/api/v1/cashier/outlets/o1/fnb/orders", "1.1.1.1"));

        authenticateAs("user-2");
        assertEquals(200, statusOf(filter, "POST", TICKET, "1.1.1.1"));
    }

    @Test
    void authenticatedRequestsShareOneBucketPerUserAcrossIps() throws Exception {
        properties.setAuthenticated(limit(2));
        properties.setAnonymousOther(limit(100));
        RateLimitFilter filter = filter();

        authenticateAs("user-1");
        assertEquals(200, statusOf(filter, "GET", "/api/v1/cashier/products", "1.1.1.1"));
        assertEquals(200, statusOf(filter, "GET", "/api/v1/cashier/products", "2.2.2.2"));
        assertEquals(429, statusOf(filter, "GET", "/api/v1/cashier/products", "3.3.3.3"));

        authenticateAs("user-2");
        assertEquals(200, statusOf(filter, "GET", "/api/v1/cashier/products", "1.1.1.1"));
    }

    @Test
    void tenantPrincipalIsKeyedByItsStableUserId() throws Exception {
        properties.setAuthenticated(limit(2));
        RateLimitFilter filter = filter();
        UUID userId = UUID.randomUUID();

        // Two separate tokens (principal instances) of the same user must share one bucket.
        authenticateAs(new TenantPrincipal(userId, UUID.randomUUID(), Set.of(Role.CASHIER)));
        assertEquals(200, statusOf(filter, "GET", "/api/v1/cashier/products", "1.1.1.1"));
        authenticateAs(new TenantPrincipal(userId, UUID.randomUUID(), Set.of(Role.CASHIER)));
        assertEquals(200, statusOf(filter, "GET", "/api/v1/cashier/products", "1.1.1.1"));
        assertEquals(429, statusOf(filter, "GET", "/api/v1/cashier/products", "1.1.1.1"));
    }

    @Test
    void anonymousRequestsAreLimitedPerIp() throws Exception {
        properties.setAnonymousOther(limit(2));
        properties.setAuthenticated(limit(100));
        RateLimitFilter filter = filter();

        assertEquals(200, statusOf(filter, "POST", "/api/v1/auth/logout", "1.1.1.1"));
        assertEquals(200, statusOf(filter, "GET", "/api/v1/auth/csrf", "1.1.1.1"));
        assertEquals(429, statusOf(filter, "GET", "/api/v1/fnb/realtime", "1.1.1.1"));
        assertEquals(200, statusOf(filter, "GET", "/api/v1/fnb/realtime", "2.2.2.2"));

        authenticateAs("user-1");
        assertEquals(200, statusOf(filter, "GET", "/api/v1/cashier/products", "1.1.1.1"),
                "an authenticated user is not keyed by IP");
    }

    @Test
    void rejectedRequestGetsTooManyRequestsEnvelopeAndRetryAfterWithoutContinuingTheChain() throws Exception {
        properties.setAuth(new RateLimitProperties.Limit(10, 1));
        RateLimitFilter filter = filter();
        assertTrue(run(filter, request("POST", LOGIN, "1.1.1.1")).passed());

        Result rejected = run(filter, request("POST", LOGIN, "1.1.1.1"));

        assertFalse(rejected.passed(), "the chain must not run");
        assertEquals(429, rejected.response.getStatus());
        String retryAfter = rejected.response.getHeader("Retry-After");
        assertNotNull(retryAfter);
        long seconds = Long.parseLong(retryAfter);
        assertTrue(seconds >= 1);
        assertTrue(rejected.response.getContentType().startsWith("application/json"));
        JsonNode body = objectMapper.readTree(rejected.response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(429, body.path("code").asInt());
        assertFalse(body.path("success").asBoolean(true));
        assertEquals("Terlalu banyak permintaan. Coba lagi dalam " + seconds + " detik.",
                body.path("message").asString());
    }

    @Test
    void allowedRequestContinuesTheChainUntouched() throws Exception {
        Result result = run(filter(), request("POST", LOGIN, "1.1.1.1"));

        assertTrue(result.passed());
        assertEquals(200, result.response.getStatus());
        assertNull(result.response.getHeader("Retry-After"));
    }

    @Test
    void preflightDocsAndNonApiPathsAreNeverThrottled() throws Exception {
        properties.setAnonymousOther(limit(1));
        properties.setAuth(limit(1));
        RateLimitFilter filter = filter();

        for (int index = 0; index < 5; index++) {
            assertEquals(200, statusOf(filter, "OPTIONS", LOGIN, "1.1.1.1"));
            assertEquals(200, statusOf(filter, "GET", "/v3/api-docs/swagger-config", "1.1.1.1"));
            assertEquals(200, statusOf(filter, "GET", "/swagger-ui/index.html", "1.1.1.1"));
            assertEquals(200, statusOf(filter, "GET", "/swagger-ui.html", "1.1.1.1"));
            assertEquals(200, statusOf(filter, "GET", "/error", "1.1.1.1"));
            assertEquals(200, statusOf(filter, "GET", "/actuator/health", "1.1.1.1"));
        }
    }

    @Test
    void disabledFilterPassesEverythingThrough() throws Exception {
        properties.setEnabled(false);
        properties.setAuth(limit(1));
        RateLimitFilter filter = filter();

        for (int index = 0; index < 5; index++) {
            assertTrue(run(filter, request("POST", LOGIN, "1.1.1.1")).passed());
        }
    }

    @Test
    void forwardedForHeaderIsIgnored() throws Exception {
        properties.setAuth(limit(1));
        RateLimitFilter filter = filter();

        MockHttpServletRequest first = request("POST", LOGIN, "9.9.9.9");
        first.addHeader("X-Forwarded-For", "1.1.1.1");
        MockHttpServletRequest second = request("POST", LOGIN, "9.9.9.9");
        second.addHeader("X-Forwarded-For", "2.2.2.2");

        assertTrue(run(filter, first).passed());
        assertEquals(429, run(filter, second).response.getStatus(), "a spoofed header must not give a fresh bucket");
    }
}
