package org.lite.project.cashiersaas.unit.component;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.FnbOrderChangedEvent;
import org.lite.project.cashiersaas.component.FnbRealtimeHandshakeInterceptor;
import org.lite.project.cashiersaas.component.FnbRealtimeWebSocketHandler;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.entity.shared.identity.AuthSessionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.repository.AuthSessionRepository;
import org.lite.project.cashiersaas.service.FnbRealtimeAccessService;
import org.lite.project.cashiersaas.service.FnbRealtimeTicketService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class FnbRealtimeWebSocketHandlerTest {
    private final MerchantAccessGuard guard = mock(MerchantAccessGuard.class);
    private final AuthSessionRepository authSessions = mock(AuthSessionRepository.class);
    private final Map<UUID, AuthSessionEntity> auth = new HashMap<>();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Instant future = Instant.parse("2100-01-01T00:00:00Z");
    private final UUID tenantId = UUID.randomUUID(), outletId = UUID.randomUUID();
    private FnbRealtimeAccessService access;
    private FnbRealtimeWebSocketHandler handler;
    private TenantPrincipal cashier;

    @BeforeEach
    void setUp() {
        access = new FnbRealtimeAccessService(guard, authSessions);
        handler = new FnbRealtimeWebSocketHandler(mapper, access, Runnable::run);
        cashier = principal(tenantId, outletId, Role.CASHIER);
    }

    @AfterEach
    void closeSockets() {
        handler.closeAll();
    }

    @Test
    void onlyExactTenantAndOutletReceiveMinimalJsonSnapshot() throws Exception {
        TenantPrincipal owner = principal(tenantId, outletId, Role.OWNERMERCHANT);
        WebSocketSession localCashier = connect("cashier", cashier);
        WebSocketSession localOwner = connect("owner", owner);
        WebSocketSession otherTenant = connect("otherTenant", principal(UUID.randomUUID(), outletId, Role.CASHIER));
        WebSocketSession otherOutlet = connect("otherOutlet", principal(tenantId, UUID.randomUUID(), Role.CASHIER));
        FnbOrderChangedEvent event = event();
        handler.enqueue(event);
        assertPayload(localCashier, event);
        assertPayload(localOwner, event);
        verify(otherTenant, never()).sendMessage(any());
        verify(otherOutlet, never()).sendMessage(any());
        verify(authSessions, atLeastOnce()).findById(cashier.getSessionId(), cashier.getUserId());
    }

    @Test
    void revokedSessionClosesOnDeliveryAndCapacityCanBeReused() throws Exception {
        List<WebSocketSession> sockets = fourSessions(cashier);
        auth.get(cashier.getSessionId()).revoke(Instant.now());
        handler.enqueue(event());
        for (WebSocketSession socket : sockets) {
            verify(socket).close(CloseStatus.POLICY_VIOLATION);
            verify(socket, never()).sendMessage(any());
        }
        // Same user/tenant/outlet with a newly authenticated session must recover all four slots.
        TenantPrincipal fresh = new TenantPrincipal(cashier.getUserId(), tenantId, outletId,
                cashier.getMembershipId(), UUID.randomUUID(), cashier.getRoles());
        register(fresh, Role.CASHIER);
        for (int i = 0; i < 4; i++) {
            WebSocketSession replacement = connect("replacement-" + i, fresh);
            verify(replacement, never()).close(any(CloseStatus.class));
        }
    }

    @Test
    void jwtExpiryClosesAtMaintenanceAndReleasesSlotEvenWithLiveAuthSession() throws Exception {
        FnbRealtimeTicketService.Ticket state = ticket(cashier, Instant.EPOCH, future);
        WebSocketSession socket = socket("expiring", state);
        handler.afterConnectionEstablished(socket);
        for (int i = 0; i < 3; i++) connect("unexpired-" + i, cashier);
        // A consumed ticket's TTL no longer controls connection lifetime.
        handler.enqueue(event());
        verify(socket).sendMessage(any(TextMessage.class));
        ReflectionTestUtils.setField(state, "accessExpiresAt", Instant.EPOCH);
        handler.maintain();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        WebSocketSession replacement = connect("fresh", cashier);
        verify(replacement, never()).close(any(CloseStatus.class));
        handler.enqueue(event());
        verify(replacement).sendMessage(any(TextMessage.class));
    }

    @Test
    void fourSessionLimitRejectsFifthAndConnectionCloseReleasesCapacity() throws Exception {
        List<WebSocketSession> sockets = fourSessions(cashier);
        WebSocketSession fifth = connect("fifth", cashier);
        verify(fifth).close(CloseStatus.POLICY_VIOLATION);
        handler.afterConnectionClosed(sockets.get(0), CloseStatus.NORMAL);
        WebSocketSession replacement = connect("replacement", cashier);
        verify(replacement, never()).close(any(CloseStatus.class));
        handler.enqueue(event());
        verify(sockets.get(0), never()).sendMessage(any());
        verify(fifth, never()).sendMessage(any());
        verify(replacement).sendMessage(any(TextMessage.class));
    }

    @Test
    void globalSessionLimitRejectsFiveHundredAndFirstUntilSlotReleased() throws Exception {
        WebSocketSession first = null;
        for (int user = 0; user < 125; user++) {
            TenantPrincipal principal = principal(tenantId, outletId, Role.CASHIER);
            for (int slot = 0; slot < 4; slot++) {
                WebSocketSession socket = connect("global-" + user + "-" + slot, principal);
                if (first == null) first = socket;
                verify(socket, never()).close(any(CloseStatus.class));
            }
        }
        TenantPrincipal extra = principal(tenantId, outletId, Role.CASHIER);
        WebSocketSession overflow = connect("overflow", extra);
        verify(overflow).close(CloseStatus.POLICY_VIOLATION);
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        WebSocketSession replacement = connect("global-replacement", extra);
        verify(replacement, never()).close(any(CloseStatus.class));
    }

    @Test
    void clientTextCannotMutateStateAndClosesThroughPublicHandleMessage() throws Exception {
        WebSocketSession socket = connect("client", cashier);
        handler.handleMessage(socket, new TextMessage("{\"type\":\"MARK_PAID\",\"orderId\":\"123\"}"));
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        handler.enqueue(event());
        verify(socket, never()).sendMessage(any());
        WebSocketSession replacement = connect("after-client-close", cashier);
        handler.enqueue(event());
        verify(replacement).sendMessage(any(TextMessage.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime"})
    void brokenSocketDoesNotPreventHealthyPeerDeliveryAndItsSlotIsReleased(String failure) throws Exception {
        WebSocketSession first = connect("peer-one", cashier);
        WebSocketSession second = connect("peer-two", cashier);
        // Identify the first actual delivery peer, so failure containment is tested before another send.
        Map<String, Object> registered = registeredSessions();
        String firstId = registered.keySet().iterator().next();
        WebSocketSession broken = firstId.equals("peer-one") ? first : second;
        WebSocketSession healthy = broken == first ? second : first;
        connect("peer-three", cashier);
        connect("peer-four", cashier);
        if (failure.equals("io")) doThrow(new IOException("broken")).when(broken).sendMessage(any());
        else doThrow(new IllegalStateException("broken")).when(broken).sendMessage(any());
        assertDoesNotThrow(() -> handler.enqueue(event()));
        verify(broken).close(CloseStatus.POLICY_VIOLATION);
        verify(healthy).sendMessage(any(TextMessage.class));
        WebSocketSession replacement = connect("after-broken", cashier);
        verify(replacement, never()).close(any(CloseStatus.class));
        handler.enqueue(event());
        verify(broken, times(1)).sendMessage(any());
        verify(replacement).sendMessage(any(TextMessage.class));
    }

    @Test
    void establishmentRequiresTicketAndRechecksCurrentAccess() throws Exception {
        WebSocketSession missing = socket("no-ticket", null);
        handler.afterConnectionEstablished(missing);
        verify(missing).close(CloseStatus.POLICY_VIOLATION);
        auth.get(cashier.getSessionId()).revoke(Instant.now());
        WebSocketSession revoked = connect("revoked", cashier);
        verify(revoked).close(CloseStatus.POLICY_VIOLATION);
        handler.enqueue(event());
        verify(missing, never()).sendMessage(any());
        verify(revoked, never()).sendMessage(any());
    }

    @Test
    void maintenanceRemovesClosedRevokedAndSwitchedMerchantSessionsWithoutAffectingHealthyPeer() throws Exception {
        WebSocketSession closed = connect("closed", cashier);
        TenantPrincipal revokedPrincipal = principal(tenantId, outletId, Role.CASHIER);
        WebSocketSession revoked = connect("revoked", revokedPrincipal);
        TenantPrincipal switchedPrincipal = principal(tenantId, outletId, Role.CASHIER);
        WebSocketSession switched = connect("switched", switchedPrincipal);
        WebSocketSession healthy = connect("healthy", principal(tenantId, outletId, Role.CASHIER));
        when(closed.isOpen()).thenReturn(false);
        auth.get(revokedPrincipal.getSessionId()).revoke(Instant.now());
        ReflectionTestUtils.setField(auth.get(switchedPrincipal.getSessionId()), "role", Role.OWNERMERCHANT);
        handler.maintain();
        verify(revoked).close(CloseStatus.POLICY_VIOLATION);
        verify(switched).close(CloseStatus.POLICY_VIOLATION);
        handler.enqueue(event());
        verify(closed, never()).sendMessage(any());
        verify(switched, never()).sendMessage(any());
        verify(revoked, never()).sendMessage(any());
        verify(healthy).sendMessage(any(TextMessage.class));
    }

    @Test
    void closeFailureIsIsolatedAndCloseAllReleasesEverySlot() throws Exception {
        List<WebSocketSession> sockets = fourSessions(cashier);
        doThrow(new IllegalStateException("transport close")).when(sockets.get(0)).close(any(CloseStatus.class));
        assertDoesNotThrow(handler::closeAll);
        for (WebSocketSession socket : sockets) verify(socket).close(CloseStatus.POLICY_VIOLATION);
        for (int i = 0; i < 4; i++) {
            WebSocketSession replacement = connect("after-shutdown-" + i, cashier);
            verify(replacement, never()).close(any(CloseStatus.class));
        }
    }

    @Test
    void advertisesOnlyApplicationProtocolAndContainsBoundedExecutorRejection() {
        FnbRealtimeWebSocketHandler rejected = new FnbRealtimeWebSocketHandler(mapper, access,
                command -> { throw new RejectedExecutionException("full"); });
        assertEquals(List.of("cashier-fnb-v1"), rejected.getSubProtocols());
        assertDoesNotThrow(() -> rejected.enqueue(event()));
        verifyNoInteractions(authSessions, guard);
    }

    private List<WebSocketSession> fourSessions(TenantPrincipal principal) throws Exception {
        List<WebSocketSession> sockets = new ArrayList<>();
        for (int i = 0; i < 4; i++) sockets.add(connect("slot-" + i, principal));
        return sockets;
    }

    private WebSocketSession connect(String id, TenantPrincipal principal) throws Exception {
        WebSocketSession socket = socket(id, ticket(principal, future, future));
        handler.afterConnectionEstablished(socket);
        return socket;
    }

    private WebSocketSession socket(String id, FnbRealtimeTicketService.Ticket ticket) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        if (ticket != null) attributes.put(FnbRealtimeHandshakeInterceptor.TICKET_ATTRIBUTE, ticket);
        when(socket.getId()).thenReturn(id);
        when(socket.getAttributes()).thenReturn(attributes);
        AtomicBoolean open = new AtomicBoolean(true);
        when(socket.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> { open.set(false); return null; }).when(socket).close(any(CloseStatus.class));
        return socket;
    }

    private TenantPrincipal principal(UUID tenant, UUID outlet, Role role) {
        TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenant, outlet,
                UUID.randomUUID(), UUID.randomUUID(), Set.of(role));
        register(principal, role);
        return principal;
    }

    private void register(TenantPrincipal principal, Role role) {
        TenantEntity tenant = new TenantEntity();
        ReflectionTestUtils.setField(tenant, "id", principal.getTenantId());
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        ReflectionTestUtils.setField(outlet, "id", principal.getOutletId());
        TenantMembershipEntity membership = role == Role.CASHIER ? TenantMembershipEntity.grantCashier(null, tenant)
                : TenantMembershipEntity.grantOwner(null, tenant);
        ReflectionTestUtils.setField(membership, "id", principal.getMembershipId());
        AuthSessionEntity current = AuthSessionEntity.start(null, future);
        current.selectMerchant(membership, outlet);
        auth.put(principal.getSessionId(), current);
        when(authSessions.findById(principal.getSessionId(), principal.getUserId())).thenReturn(Optional.of(current));
        when(guard.requireOutlet(principal, principal.getOutletId())).thenReturn(new MerchantAccessContext(tenant, outlet, membership));
    }

    private FnbRealtimeTicketService.Ticket ticket(TenantPrincipal principal, Instant ticketExpiry, Instant accessExpiry) throws Exception {
        Constructor<FnbRealtimeTicketService.Ticket> constructor = FnbRealtimeTicketService.Ticket.class
                .getDeclaredConstructor(TenantPrincipal.class, Instant.class, Instant.class);
        constructor.setAccessible(true);
        return constructor.newInstance(principal, ticketExpiry, accessExpiry);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> registeredSessions() {
        return (Map<String, Object>) ReflectionTestUtils.getField(handler, "sessions");
    }

    private FnbOrderChangedEvent event() {
        return new FnbOrderChangedEvent("ORDER_CHANGED", tenantId, outletId, UUID.randomUUID(),
                OrderStatus.CONFIRMED, PaymentStatus.PAID, Instant.parse("2030-01-01T00:00:00Z"));
    }

    private void assertPayload(WebSocketSession socket, FnbOrderChangedEvent event) throws Exception {
        ArgumentCaptor<TextMessage> message = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(message.capture());
        JsonNode json = mapper.readTree(message.getValue().getPayload());
        assertEquals(Set.of("eventId", "type", "orderId", "outletId", "orderStatus", "paymentStatus", "occurredAt", "resyncRequired"),
                Set.copyOf(json.propertyNames()));
        assertEquals(event.getEventId().toString(), json.get("eventId").asString());
        assertEquals("ORDER_CHANGED", json.get("type").asString());
        assertEquals(event.getOrderId().toString(), json.get("orderId").asString());
        assertEquals(outletId.toString(), json.get("outletId").asString());
        assertEquals("CONFIRMED", json.get("orderStatus").asString());
        assertEquals("PAID", json.get("paymentStatus").asString());
        assertTrue(json.get("resyncRequired").asBoolean());
    }
}
