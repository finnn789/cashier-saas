package org.lite.project.cashiersaas.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbRealtimeTicketResponse;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FnbRealtimeTicketService {
    public static final String PROTOCOL = "cashier-fnb-v1";
    public static final String PATH = "/api/v1/fnb/realtime";
    private static final Duration MAX_TICKET_TTL = Duration.ofSeconds(30);
    private static final int MAX_TICKETS = 2_000;
    private static final int MAX_TICKETS_PER_PRINCIPAL = 4;
    private final JwtDecoder jwtDecoder;
    private final Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;
    private final FnbRealtimeAccessService access;
    private final SecureRandom random = new SecureRandom();
    private final Object ticketLock = new Object();
    private final Map<String, Ticket> tickets = new HashMap<>();

    public FnbRealtimeTicketResponse issue(TenantPrincipal requestPrincipal, String authorization) {
        Jwt jwt = jwtDecoder.decode(bearer(authorization));
        AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(jwt);
        Object converted = authentication == null ? null : authentication.getPrincipal();
        if (!(converted instanceof TenantPrincipal decoded) || !same(decoded, requestPrincipal)) throw new InvalidOperationException("JWT realtime tidak cocok dengan principal saat ini.");
        Instant accessExpiresAt = jwt.getExpiresAt();
        if (accessExpiresAt == null || !accessExpiresAt.isAfter(Instant.now()) || !access.isCurrent(decoded, accessExpiresAt)) throw new InvalidOperationException("Akses realtime tidak tersedia.");
        Instant ticketExpiresAt = earlier(accessExpiresAt, Instant.now().plus(MAX_TICKET_TTL));
        String ticket = newTicket();
        Ticket state = new Ticket(decoded, ticketExpiresAt, accessExpiresAt);
        synchronized (ticketLock) {
            cleanupLocked(Instant.now());
            if (tickets.size() >= MAX_TICKETS || outstandingLocked(state.principalKey()) >= MAX_TICKETS_PER_PRINCIPAL) throw new InvalidOperationException("Kapasitas tiket realtime sedang penuh.");
            tickets.put(digest(ticket), state);
        }
        FnbRealtimeTicketResponse response = new FnbRealtimeTicketResponse();
        response.setTicket(ticket); response.setExpiresAt(ticketExpiresAt); response.setWebsocketPath(PATH); response.setProtocol(PROTOCOL);
        return response;
    }

    public Ticket consume(String rawTicket) {
        if (rawTicket == null || !rawTicket.matches("[A-Za-z0-9_-]{43}")) return null;
        Ticket ticket;
        synchronized (ticketLock) { cleanupLocked(Instant.now()); ticket = tickets.remove(digest(rawTicket)); }
        if (ticket == null || !ticket.ticketExpiresAt().isAfter(Instant.now()) || !access.isCurrent(ticket.principal(), ticket.accessExpiresAt())) return null;
        return ticket;
    }
    public void cleanup() { synchronized (ticketLock) { cleanupLocked(Instant.now()); } }
    private void cleanupLocked(Instant now) { tickets.entrySet().removeIf(entry -> !entry.getValue().ticketExpiresAt().isAfter(now)); }
    private int outstandingLocked(String key) { return (int) tickets.values().stream().filter(ticket -> ticket.principalKey().equals(key)).count(); }
    private String newTicket() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
    private String bearer(String value) { if (value == null || !value.startsWith("Bearer ") || value.length() <= 7) throw new InvalidOperationException("Authorization Bearer wajib diisi."); return value.substring(7); }
    private boolean same(TenantPrincipal left, TenantPrincipal right) { return left.getUserId().equals(right.getUserId()) && left.getTenantId().equals(right.getTenantId()) && left.getOutletId().equals(right.getOutletId()) && left.getMembershipId().equals(right.getMembershipId()) && left.getSessionId().equals(right.getSessionId()) && left.getRoles().equals(right.getRoles()); }
    private String digest(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }

    public static final class Ticket {
        private final TenantPrincipal principal; private final Instant ticketExpiresAt; private final Instant accessExpiresAt;
        private Ticket(TenantPrincipal principal, Instant ticketExpiresAt, Instant accessExpiresAt) { this.principal = principal; this.ticketExpiresAt = ticketExpiresAt; this.accessExpiresAt = accessExpiresAt; }
        public TenantPrincipal principal() { return principal; }
        public Instant ticketExpiresAt() { return ticketExpiresAt; }
        public Instant accessExpiresAt() { return accessExpiresAt; }
        public String principalKey() { return principal.getUserId() + ":" + principal.getTenantId() + ":" + principal.getOutletId(); }
    }
}
