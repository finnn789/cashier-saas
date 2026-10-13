package org.lite.project.cashiersaas.unit.component;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.FnbRealtimeHandshakeInterceptor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.service.FnbRealtimeAccessService;
import org.lite.project.cashiersaas.service.FnbRealtimeTicketService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.WebSocketHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class FnbRealtimeHandshakeInterceptorTest {
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final FnbRealtimeAccessService access = mock(FnbRealtimeAccessService.class);
    private final Converter<Jwt, AbstractAuthenticationToken> converter = converter();
    private final ServerHttpResponse response = mock(ServerHttpResponse.class);
    private final WebSocketHandler handler = mock(WebSocketHandler.class);
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.CASHIER));
    private final Map<String, Object> attributes = new HashMap<>();
    private FnbRealtimeTicketService tickets;
    private FnbRealtimeHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        tickets = new FnbRealtimeTicketService(decoder, converter, access);
        interceptor = new FnbRealtimeHandshakeInterceptor(tickets);
        Jwt jwt = mock(Jwt.class);
        AbstractAuthenticationToken authentication = mock(AbstractAuthenticationToken.class);
        Instant expiry = Instant.parse("2100-01-01T00:00:00Z");
        when(decoder.decode("token")).thenReturn(jwt);
        when(jwt.getExpiresAt()).thenReturn(expiry);
        when(converter.convert(jwt)).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(access.isCurrent(principal, expiry)).thenReturn(true);
    }

    @Test
    void successfulHandshakeStoresRealConsumedTicketAndReplayIsRejected() {
        String raw = issue();
        ServerHttpRequest request = request("cashier-fnb-v1, ticket." + raw, null);
        assertTrue(handshake(request));
        FnbRealtimeTicketService.Ticket state = assertInstanceOf(FnbRealtimeTicketService.Ticket.class,
                attributes.get(FnbRealtimeHandshakeInterceptor.TICKET_ATTRIBUTE));
        assertSame(principal, state.principal());
        Map<String, Object> replayAttributes = new HashMap<>();
        assertFalse(interceptor.beforeHandshake(request, response, handler, replayAttributes));
        assertTrue(replayAttributes.isEmpty());
    }

    @Test
    void separateProtocolHeaderValuesAndWhitespaceAreAccepted() {
        String raw = issue();
        ServerHttpRequest request = request(null, null);
        request.getHeaders().add("Sec-WebSocket-Protocol", " cashier-fnb-v1 ");
        request.getHeaders().add("Sec-WebSocket-Protocol", " ticket." + raw + " ");
        assertTrue(handshake(request));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"cashier-fnb-v1", "ticket.raw", "cashier-fnb-v1, cashier-fnb-v1, ticket.raw",
            "cashier-fnb-v1, ticket.raw, ticket.other", "cashier-fnb-v2, ticket.raw"})
    void missingOrDuplicateApplicationAndTicketProtocolsAreRejected(String protocols) {
        String raw = issue();
        String supplied = protocols == null ? null : protocols.replace("raw", raw).replace("other", raw);
        clearInvocations(access);
        assertFalse(handshake(request(supplied, null)));
        assertTrue(attributes.isEmpty());
        verifyNoInteractions(access);
        assertTrue(handshake(request("cashier-fnb-v1, ticket." + raw, null)),
                "Protocol rejection must precede consumption of the otherwise-valid ticket");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket=", "token=secret", "unrelated=value"})
    void everyUriQueryIsRejectedAndDoesNotBurnValidTicket(String query) {
        String raw = issue();
        assertFalse(handshake(request("cashier-fnb-v1, ticket." + raw, query)));
        assertTrue(attributes.isEmpty());
        assertTrue(handshake(request("cashier-fnb-v1, ticket." + raw, null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "...........................................", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void malformedOrUnknownTicketCannotPopulateAttributes(String raw) {
        assertFalse(handshake(request("cashier-fnb-v1, ticket." + raw, null)));
        assertTrue(attributes.isEmpty());
        verifyNoInteractions(access);
    }

    @Test
    void revokedAccessBetweenIssuanceAndHandshakeIsDenied() {
        String raw = issue();
        when(access.isCurrent(eq(principal), any(Instant.class))).thenReturn(false);
        assertFalse(handshake(request("cashier-fnb-v1, ticket." + raw, null)));
        assertTrue(attributes.isEmpty());
    }

    private String issue() {
        return tickets.issue(principal, "Bearer token").getTicket();
    }

    private boolean handshake(ServerHttpRequest request) {
        return interceptor.beforeHandshake(request, response, handler, attributes);
    }

    private ServerHttpRequest request(String protocols, String query) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (protocols != null) headers.add("Sec-WebSocket-Protocol", protocols);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("http://localhost/api/v1/fnb/realtime" + (query == null ? "" : "?" + query)));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Converter<Jwt, AbstractAuthenticationToken> converter() {
        return mock(Converter.class);
    }
}
