package org.lite.project.cashiersaas.unit.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.configuration.AiServiceProperties;
import org.lite.project.cashiersaas.service.DailySummaryClient;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** TASK-080 D1: single-attempt daily summary request; disabled without URL/key; never throws; never logs the key. */
@Tag("unit")
class DailySummaryClientTest {
    private static final String BASE_URL = "http://ai.test";
    private static final String API_KEY = "unit-test-internal-key-0123456789";
    private static final String URL = BASE_URL + "/api/v1/internal/daily-summaries";

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 11, 14);

    private MockRestServiceServer server;
    private RestClient restClient;
    private ListAppender<ILoggingEvent> logs;
    private Logger clientLogger;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        clientLogger = (Logger) LoggerFactory.getLogger(DailySummaryClient.class);
        logs = new ListAppender<>();
        logs.start();
        clientLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logs);
    }

    private static AiServiceProperties properties(String baseUrl, String apiKey) {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey(apiKey);
        return properties;
    }

    private DailySummaryClient client(String baseUrl, String apiKey) {
        return new DailySummaryClient(properties(baseUrl, apiKey), true, restClient);
    }

    @Test
    void aSuccessfulResponseIsReportedAsSentWithTheContractRequest() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Key", API_KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.tenant_id").value(tenantId.toString()))
                .andExpect(jsonPath("$.outlet_id").value(outletId.toString()))
                .andExpect(jsonPath("$.business_date").value("2026-11-14"))
                .andRespond(withSuccess("{\"delivered\":1,\"skipped\":0}", MediaType.APPLICATION_JSON));

        assertTrue(client(BASE_URL, API_KEY).send(tenantId, outletId, date));

        server.verify();
    }

    @Test
    void anyTwoHundredSeriesStatusCountsAsSent() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.ACCEPTED));

        assertTrue(client(BASE_URL, API_KEY).send(tenantId, outletId, date));
    }

    @Test
    void aTrailingSlashOnTheBaseUrlDoesNotDoubleTheSlash() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess());

        assertTrue(client(BASE_URL + "/", API_KEY).send(tenantId, outletId, date));

        server.verify();
    }

    @Test
    void aServerErrorIsReportedAsFailedWithoutThrowing() {
        server.expect(once(), requestTo(URL)).andRespond(withServerError());

        assertFalse(assertDoesNotThrow(() -> client(BASE_URL, API_KEY).send(tenantId, outletId, date)));

        server.verify(); // a single attempt: retries belong to the scheduler
    }

    @Test
    void aClientErrorIsReportedAsFailed() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertFalse(client(BASE_URL, API_KEY).send(tenantId, outletId, date));
    }

    @Test
    void anIoErrorIsReportedAsFailedWithoutThrowing() {
        server.expect(once(), requestTo(URL)).andRespond(withException(new IOException("connection reset")));

        assertFalse(assertDoesNotThrow(() -> client(BASE_URL, API_KEY).send(tenantId, outletId, date)));

        server.verify();
    }

    @Test
    void aBlankBaseUrlDisablesTheClientAndSendsNothing() {
        DailySummaryClient client = client("", API_KEY);

        assertFalse(client.isEnabled());
        assertFalse(client.send(tenantId, outletId, date));
        server.verify(); // no expectation was registered, so any request would have failed the call above
    }

    @Test
    void aBlankApiKeyDisablesTheClientAndSendsNothing() {
        DailySummaryClient client = client(BASE_URL, "  ");

        assertFalse(client.isEnabled());
        assertFalse(client.send(tenantId, outletId, date));
        server.verify();
    }

    @Test
    void theFeatureFlagOffDisablesTheClientEvenWithUrlAndKey() {
        DailySummaryClient client = new DailySummaryClient(properties(BASE_URL, API_KEY), false, restClient);

        assertFalse(client.isEnabled());
        assertFalse(client.send(tenantId, outletId, date));
        server.verify();
    }

    @Test
    void theSpringConstructorBuildsNoHttpClientWhenTheBaseUrlIsBlank() {
        DailySummaryClient client = new DailySummaryClient(properties("", API_KEY), true);

        assertFalse(client.isEnabled());
        assertFalse(client.send(tenantId, outletId, date));
    }

    @Test
    void theSpringConstructorIsEnabledWithUrlAndKeyAndDisabledWhenTheFlagIsOff() {
        assertTrue(new DailySummaryClient(properties(BASE_URL, API_KEY), true).isEnabled());
        assertFalse(new DailySummaryClient(properties(BASE_URL, API_KEY), false).isEnabled());
    }

    @Test
    void theApiKeyIsNeverLoggedOnSuccessFailureOrDisabledStartup() {
        server.expect(once(), requestTo(URL)).andRespond(withServerError());
        client(BASE_URL, API_KEY).send(tenantId, outletId, date);
        client("", API_KEY);

        for (ILoggingEvent event : logs.list) {
            assertFalse(event.getFormattedMessage().contains(API_KEY));
        }
    }
}
