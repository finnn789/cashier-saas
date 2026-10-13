package org.lite.project.cashiersaas.component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.lite.project.cashiersaas.dto.FnbRealtimeMapper;
import org.lite.project.cashiersaas.service.FnbRealtimeAccessService;
import org.lite.project.cashiersaas.service.FnbRealtimeTicketService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class FnbRealtimeWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {
    private static final int MAX_SESSIONS = 500;
    private static final int MAX_PER_PRINCIPAL = 4;

    private final Object sessionLock = new Object();
    private final Map<String, SessionTicket> sessions = new HashMap<>();
    private final ObjectMapper objectMapper;
    private final FnbRealtimeAccessService access;
    private final Executor deliveryExecutor;

    public FnbRealtimeWebSocketHandler(ObjectMapper objectMapper, FnbRealtimeAccessService access,
                                       @Qualifier("fnbRealtimeDeliveryExecutor") Executor deliveryExecutor) {
        this.objectMapper = objectMapper;
        this.access = access;
        this.deliveryExecutor = deliveryExecutor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Object state = session.getAttributes().get(FnbRealtimeHandshakeInterceptor.TICKET_ATTRIBUTE);
        if (!(state instanceof FnbRealtimeTicketService.Ticket ticket) || !isCurrent(ticket)) {
            close(session);
            return;
        }
        ConcurrentWebSocketSessionDecorator decorated = new ConcurrentWebSocketSessionDecorator(session, 5_000, 16 * 1024);
        boolean accepted;
        synchronized (sessionLock) {
            accepted = sessions.size() < MAX_SESSIONS && sessionCount(ticket) < MAX_PER_PRINCIPAL;
            if (accepted) sessions.put(session.getId(), new SessionTicket(decorated, ticket));
        }
        if (!accepted) close(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        close(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        remove(session.getId());
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(FnbRealtimeTicketService.PROTOCOL);
    }

    public void enqueue(FnbOrderChangedEvent event) {
        try {
            deliveryExecutor.execute(() -> deliver(event));
        } catch (RejectedExecutionException ignored) {
            // Bounded overload drops this invalidation; clients continue REST polling/resync.
        }
    }

    public void maintain() {
        for (SessionTicket value : snapshot()) {
            try {
                if (!value.session().isOpen() || !isCurrent(value.ticket())) close(value.session());
            } catch (RuntimeException exception) {
                close(value.session());
            }
        }
    }

    private void deliver(FnbOrderChangedEvent event) {
        String payload = serialize(event);
        if (payload == null) return;
        for (SessionTicket value : snapshot()) {
            try {
                if (!sameScope(value.ticket(), event)) continue;
                if (!isCurrent(value.ticket())) {
                    close(value.session());
                    continue;
                }
                send(value.session(), payload);
            } catch (RuntimeException exception) {
                close(value.session());
            }
        }
    }

    private String serialize(FnbOrderChangedEvent event) {
        try {
            return new String(objectMapper.writeValueAsBytes(FnbRealtimeMapper.event(event)), StandardCharsets.UTF_8);
        } catch (JacksonException exception) {
            return null;
        }
    }

    private boolean isCurrent(FnbRealtimeTicketService.Ticket ticket) {
        return access.isCurrent(ticket.principal(), ticket.accessExpiresAt());
    }

    private boolean sameScope(FnbRealtimeTicketService.Ticket ticket, FnbOrderChangedEvent event) {
        return ticket.principal().getTenantId().equals(event.getTenantId())
                && ticket.principal().getOutletId().equals(event.getOutletId());
    }

    private int sessionCount(FnbRealtimeTicketService.Ticket ticket) {
        return (int) sessions.values().stream()
                .filter(value -> value.ticket().principalKey().equals(ticket.principalKey()))
                .count();
    }

    private List<SessionTicket> snapshot() {
        synchronized (sessionLock) {
            return new ArrayList<>(sessions.values());
        }
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) session.sendMessage(new TextMessage(payload));
        } catch (IOException | RuntimeException exception) {
            close(session);
        }
    }

    private void close(WebSocketSession session) {
        remove(session.getId());
        try {
            if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException | RuntimeException ignored) {
            // Capacity has already been released; transport failure must not stop other clients.
        }
    }

    private void remove(String id) {
        synchronized (sessionLock) {
            sessions.remove(id);
        }
    }

    @PreDestroy
    public void closeAll() {
        for (SessionTicket value : snapshot()) close(value.session());
    }

    private static final class SessionTicket {
        private final WebSocketSession session;
        private final FnbRealtimeTicketService.Ticket ticket;

        private SessionTicket(WebSocketSession session, FnbRealtimeTicketService.Ticket ticket) {
            this.session = session;
            this.ticket = ticket;
        }

        private WebSocketSession session() { return session; }
        private FnbRealtimeTicketService.Ticket ticket() { return ticket; }
    }
}
