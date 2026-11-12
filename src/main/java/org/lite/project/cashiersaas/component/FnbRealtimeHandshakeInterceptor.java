package org.lite.project.cashiersaas.component;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.service.FnbRealtimeTicketService;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
@RequiredArgsConstructor
public class FnbRealtimeHandshakeInterceptor implements HandshakeInterceptor {
    public static final String TICKET_ATTRIBUTE = "fnbRealtimeTicket";
    private final FnbRealtimeTicketService tickets;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        // Map<String,Object> is mandated by Spring's HandshakeInterceptor SPI and is confined to this adapter.
        if (request.getURI().getQuery() != null) return false;
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null) return false;
        List<String> values = protocols.stream().flatMap(value -> java.util.Arrays.stream(value.split(","))).map(String::trim).toList();
        if (values.stream().filter(FnbRealtimeTicketService.PROTOCOL::equals).count() != 1
                || values.stream().filter(value -> value.startsWith("ticket.")).count() != 1) return false;
        String raw = values.stream().filter(value -> value.startsWith("ticket."))
                .map(value -> value.substring("ticket.".length())).findFirst().orElse(null);
        FnbRealtimeTicketService.Ticket ticket = tickets.consume(raw);
        if (ticket == null) return false;
        attributes.put(TICKET_ATTRIBUTE, ticket);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
    }
}
