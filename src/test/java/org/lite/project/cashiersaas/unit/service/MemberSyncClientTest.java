package org.lite.project.cashiersaas.unit.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.component.MemberSyncRequestedEvent;
import org.lite.project.cashiersaas.configuration.AiServiceProperties;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.service.MemberSyncClient;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Sender behavior of docs/contracts/member-sync.md section 2, checked against the shared contract fixtures. */
@Tag("unit")
class MemberSyncClientTest {
    private static final String BASE_URL = "http://ai.test";
    private static final String API_KEY = "unit-test-internal-key-0123456789";
    private static final Path FIXTURE = Path.of("docs", "contracts", "member-sync.example.json");

    private MockRestServiceServer server;
    private MemberSyncClient client;
    private ListAppender<ILoggingEvent> logs;
    private Logger clientLogger;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MemberSyncClient(properties(BASE_URL, API_KEY), builder.build());
        clientLogger = (Logger) LoggerFactory.getLogger(MemberSyncClient.class);
        originalLevel = clientLogger.getLevel();
        clientLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        clientLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logs);
        clientLogger.setLevel(originalLevel);
    }

    private static AiServiceProperties properties(String baseUrl, String apiKey) {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey(apiKey);
        properties.setRetryBackoff(List.of(Duration.ofMillis(1), Duration.ofMillis(1)));
        return properties;
    }

    private static String fixtureJson() {
        try {
            return Files.readString(FIXTURE);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read contract fixture " + FIXTURE, exception);
        }
    }

    private static Map<String, Object> fixtureBody(String name) {
        return JsonPath.read(fixtureJson(), "$." + name + ".body");
    }

    private static String fixturePath(String name) {
        return JsonPath.read(fixtureJson(), "$." + name + ".path");
    }

    private static MemberSyncRequestedEvent eventFromFixture(String name) {
        // Path shape: /api/v1/internal/tenants/{tenant}/members/{user}
        String[] segments = fixturePath(name).split("/");
        UUID tenantId = UUID.fromString(segments[5]);
        UUID userId = UUID.fromString(segments[7]);
        Map<String, Object> body = fixtureBody(name);
        return new MemberSyncRequestedEvent(tenantId, (String) body.get("tenant_name"), userId,
                (String) body.get("full_name"), (String) body.get("phone_number"),
                Role.valueOf((String) body.get("role")), (String) body.get("status"));
    }

    private static URI fixtureUri(String name) {
        return URI.create(BASE_URL + fixturePath(name));
    }

    private static void assertFixtureRequest(String name, ClientHttpRequest request) {
        assertTrue(request.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON));
        Map<String, Object> actual = JsonPath.read(((MockClientHttpRequest) request).getBodyAsString(), "$");
        assertEquals(fixtureBody(name), actual);
    }

    @Test
    void ownerRequestMatchesTheContractFixture() {
        server.expect(requestTo(fixtureUri("owner")))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Internal-Service-Key", API_KEY))
                .andExpect(request -> assertFixtureRequest("owner", request))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.sync(eventFromFixture("owner"));

        server.verify();
    }

    @Test
    void cashierRequestMatchesTheContractFixture() {
        server.expect(requestTo(fixtureUri("cashier")))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Internal-Service-Key", API_KEY))
                .andExpect(request -> assertFixtureRequest("cashier", request))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.sync(eventFromFixture("cashier"));

        server.verify();
    }

    @Test
    void deactivatedMemberIsSentAsDisabled() {
        MemberSyncRequestedEvent active = eventFromFixture("cashier");
        MemberSyncRequestedEvent disabled = new MemberSyncRequestedEvent(active.getTenantId(), active.getTenantName(),
                active.getUserId(), active.getFullName(), active.getPhoneNumber(), active.getRole(), "DISABLED");
        server.expect(requestTo(fixtureUri("cashier")))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(request -> {
                    Map<String, Object> actual = JsonPath.read(((MockClientHttpRequest) request).getBodyAsString(), "$");
                    assertEquals("DISABLED", actual.get("status"));
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.sync(disabled);

        server.verify();
    }

    @Test
    void trailingSlashOnTheBaseUrlDoesNotChangeThePath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer slashServer = MockRestServiceServer.bindTo(builder).build();
        MemberSyncClient slashClient = new MemberSyncClient(properties(BASE_URL + "/", API_KEY), builder.build());
        slashServer.expect(requestTo(fixtureUri("owner"))).andRespond(withSuccess());

        slashClient.sync(eventFromFixture("owner"));

        slashServer.verify();
    }

    @Test
    void retriesServerErrorsThenSucceeds() {
        URI uri = fixtureUri("owner");
        server.expect(requestTo(uri)).andRespond(withServerError());
        server.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(uri)).andRespond(withSuccess());

        client.sync(eventFromFixture("owner"));

        server.verify();
    }

    @Test
    void retriesIoErrorsThenSucceeds() {
        URI uri = fixtureUri("owner");
        server.expect(requestTo(uri)).andRespond(request -> {
            throw new IOException("connection reset");
        });
        server.expect(requestTo(uri)).andRespond(withSuccess());

        client.sync(eventFromFixture("owner"));

        server.verify();
    }

    @Test
    void givesUpAfterThreeAttemptsWithoutThrowing() {
        server.expect(ExpectedCount.times(3), requestTo(fixtureUri("owner"))).andRespond(withServerError());

        assertDoesNotThrow(() -> client.sync(eventFromFixture("owner")));

        server.verify();
    }

    @Test
    void waitsTheConfiguredBackoffBetweenAttempts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer timedServer = MockRestServiceServer.bindTo(builder).build();
        AiServiceProperties timed = properties(BASE_URL, API_KEY);
        timed.setRetryBackoff(List.of(Duration.ofMillis(50), Duration.ofMillis(100)));
        MemberSyncClient timedClient = new MemberSyncClient(timed, builder.build());
        timedServer.expect(ExpectedCount.times(3), requestTo(fixtureUri("owner"))).andRespond(withServerError());

        long start = System.nanoTime();
        timedClient.sync(eventFromFixture("owner"));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        timedServer.verify();
        assertTrue(elapsedMillis >= 140, "expected about 50 ms + 100 ms of backoff but waited " + elapsedMillis);
    }

    @Test
    void doesNotRetryClientErrors() {
        server.expect(requestTo(fixtureUri("owner"))).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertDoesNotThrow(() -> client.sync(eventFromFixture("owner")));

        server.verify();
    }

    @Test
    void doesNotRetryAuthenticationErrors() {
        server.expect(requestTo(fixtureUri("owner"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        client.sync(eventFromFixture("owner"));

        server.verify();
    }

    @Test
    void doesNotRetryRedirects() {
        server.expect(requestTo(fixtureUri("owner"))).andRespond(withStatus(HttpStatus.FOUND));

        client.sync(eventFromFixture("owner"));

        server.verify();
    }

    @Test
    void unexpectedRuntimeFailureNeverEscapes() {
        server.expect(requestTo(fixtureUri("owner"))).andRespond(request -> {
            throw new IllegalStateException("unexpected transport bug");
        });

        assertDoesNotThrow(() -> client.sync(eventFromFixture("owner")));

        server.verify();
    }

    @Test
    void blankBaseUrlDisablesTheSyncAndMakesNoCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer idle = MockRestServiceServer.bindTo(builder).build();
        MemberSyncClient disabled = new MemberSyncClient(properties("  ", API_KEY), builder.build());

        assertFalse(disabled.isEnabled());
        assertDoesNotThrow(() -> disabled.sync(eventFromFixture("owner")));

        idle.verify();
    }

    @Test
    void blankBaseUrlNeedsNoHttpClientAtAll() {
        MemberSyncClient disabled = new MemberSyncClient(properties("", ""));

        assertFalse(disabled.isEnabled());
        assertDoesNotThrow(() -> disabled.sync(eventFromFixture("owner")));
    }

    @Test
    void blankApiKeyDisablesTheSyncInsteadOfSendingUnauthenticatedCalls() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer idle = MockRestServiceServer.bindTo(builder).build();
        MemberSyncClient noKey = new MemberSyncClient(properties(BASE_URL, " "), builder.build());

        assertFalse(noKey.isEnabled());
        noKey.sync(eventFromFixture("owner"));

        idle.verify();
    }

    @Test
    void configuredClientIsEnabled() {
        assertTrue(client.isEnabled());
        assertTrue(new MemberSyncClient(properties(BASE_URL, API_KEY)).isEnabled());
    }

    @Test
    void logsNeverContainThePhoneNumberTheKeyTheNameOrTheBody() {
        MemberSyncRequestedEvent event = eventFromFixture("owner");
        URI uri = fixtureUri("owner");
        // The IOException message and the error response bodies carry sensitive text on purpose.
        server.expect(requestTo(uri)).andRespond(request -> {
            throw new IOException("reset while sending " + event.getPhoneNumber() + " key " + API_KEY);
        });
        server.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("echo " + event.getPhoneNumber() + " " + API_KEY).contentType(MediaType.TEXT_PLAIN));
        server.expect(requestTo(uri)).andRespond(withServerError());
        server.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("bad " + event.getPhoneNumber()).contentType(MediaType.TEXT_PLAIN));

        client.sync(event);
        client.sync(event);

        server.verify();
        assertFalse(logs.list.isEmpty(), "failures must be logged");
        for (ILoggingEvent logged : logs.list) {
            String text = logged.getFormattedMessage();
            assertNull(logged.getThrowableProxy(), "no stack trace (it would carry the exception message)");
            assertFalse(text.contains(event.getPhoneNumber()), text);
            assertFalse(text.contains(API_KEY), text);
            assertFalse(text.contains(event.getFullName()), text);
            assertFalse(text.contains(event.getTenantName()), text);
            assertFalse(text.contains("phone_number"), text);
        }
        assertTrue(logs.list.stream().anyMatch(logged -> logged.getLevel() == Level.WARN
                && logged.getFormattedMessage().contains(event.getTenantId().toString())
                && logged.getFormattedMessage().contains(event.getUserId().toString())));
    }
}
