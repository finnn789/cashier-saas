package org.lite.project.cashiersaas.unit.service;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbRealtimeTicketResponse;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.service.FnbRealtimeAccessService;
import org.lite.project.cashiersaas.service.FnbRealtimeTicketService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class FnbRealtimeTicketServiceTest {
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final Converter<Jwt, AbstractAuthenticationToken> converter = mockConverter();
    private final FnbRealtimeAccessService access = mock(FnbRealtimeAccessService.class);
    private final TenantPrincipal principal = principal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final Instant accessExpiry = Instant.parse("2100-01-01T00:00:00Z");
    private FnbRealtimeTicketService service;

    @BeforeEach
    void setUp() {
        service = new FnbRealtimeTicketService(decoder, converter, access);
    }

    @Test
    void opaqueRandomTicketHasContractAndIsConsumedExactlyOnce() {
        authorize(principal, accessExpiry);
        Instant before = Instant.now();
        FnbRealtimeTicketResponse first = issue();
        Instant after = Instant.now();
        FnbRealtimeTicketResponse second = issue();
        assertTrue(first.getTicket().matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, Base64.getUrlDecoder().decode(first.getTicket()).length);
        assertFalse(storage().containsKey(first.getTicket()));
        assertNotEquals(first.getTicket(), second.getTicket());
        assertEquals("/api/v1/fnb/realtime", first.getWebsocketPath());
        assertEquals("cashier-fnb-v1", first.getProtocol());
        assertFalse(first.getExpiresAt().isBefore(before.plusSeconds(30)));
        assertFalse(first.getExpiresAt().isAfter(after.plusSeconds(30)));
        FnbRealtimeTicketService.Ticket consumed = service.consume(first.getTicket());
        assertNotNull(consumed);
        assertSame(principal, consumed.principal());
        assertEquals(first.getExpiresAt(), consumed.ticketExpiresAt());
        assertEquals(accessExpiry, consumed.accessExpiresAt());
        assertTrue(consumed.accessExpiresAt().isAfter(consumed.ticketExpiresAt()));
        assertNull(service.consume(first.getTicket()));
    }

    @Test
    void ticketExpiryIsCappedExactlyAtJwtExpiryWithControlledTestClock() {
        // Freeze only Instant.now; no production clock seam and no wall-clock short-TTL race.
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        Instant jwtExpiry = now.plusSeconds(20);
        try (org.mockito.MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(now);
            authorize(principal, jwtExpiry);
            FnbRealtimeTicketResponse response = issue();
            assertEquals(jwtExpiry, response.getExpiresAt());
            assertEquals(jwtExpiry, service.consume(response.getTicket()).accessExpiresAt());
        }
    }

    @Test
    void expiredTicketIsDeniedAndCleanupReleasesCapacityWithoutSleeping() throws Exception {
        authorize(principal, accessExpiry);
        FnbRealtimeTicketResponse expired = issue();
        issue();
        issue();
        issue();
        replaceState(expired.getTicket(), ticket(principal, Instant.EPOCH, accessExpiry));
        assertNull(service.consume(expired.getTicket()));
        assertNotNull(issue());
        verify(access, times(5)).isCurrent(principal, accessExpiry);
    }

    @Test
    void expiredOrMissingJwtExpiryIsDeniedBeforeCurrentAccessLookup() {
        authorize(principal, Instant.EPOCH);
        assertThrows(InvalidOperationException.class, this::issue);
        authorize(principal, null);
        assertThrows(InvalidOperationException.class, this::issue);
        verifyNoInteractions(access);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"token", "bearer token", "Bearer "})
    void malformedAuthorizationIsRejectedBeforeDecode(String header) {
        assertThrows(InvalidOperationException.class, () -> service.issue(principal, header));
        verifyNoInteractions(decoder, converter, access);
    }

    @Test
    void everyPrincipalIdentityOrRoleMismatchIsRejected() {
        TenantPrincipal[] mismatches = {
            new TenantPrincipal(UUID.randomUUID(), principal.getTenantId(), principal.getOutletId(), principal.getMembershipId(), principal.getSessionId(), principal.getRoles()),
            new TenantPrincipal(principal.getUserId(), UUID.randomUUID(), principal.getOutletId(), principal.getMembershipId(), principal.getSessionId(), principal.getRoles()),
            new TenantPrincipal(principal.getUserId(), principal.getTenantId(), UUID.randomUUID(), principal.getMembershipId(), principal.getSessionId(), principal.getRoles()),
            new TenantPrincipal(principal.getUserId(), principal.getTenantId(), principal.getOutletId(), UUID.randomUUID(), principal.getSessionId(), principal.getRoles()),
            new TenantPrincipal(principal.getUserId(), principal.getTenantId(), principal.getOutletId(), principal.getMembershipId(), UUID.randomUUID(), principal.getRoles()),
            new TenantPrincipal(principal.getUserId(), principal.getTenantId(), principal.getOutletId(), principal.getMembershipId(), principal.getSessionId(), Set.of(Role.OWNERMERCHANT))
        };
        for (TenantPrincipal mismatch : mismatches) {
            authorize(mismatch, accessExpiry);
            assertThrows(InvalidOperationException.class, this::issue);
        }
        verifyNoInteractions(access);
    }

    @Test
    void nullOrNonTenantConvertedPrincipalIsDenied() {
        Jwt jwt = jwt(accessExpiry);
        when(decoder.decode("token")).thenReturn(jwt);
        when(converter.convert(jwt)).thenReturn(null);
        assertThrows(InvalidOperationException.class, this::issue);
        AbstractAuthenticationToken auth = mock(AbstractAuthenticationToken.class);
        when(auth.getPrincipal()).thenReturn("untrusted");
        when(converter.convert(jwt)).thenReturn(auth);
        assertThrows(InvalidOperationException.class, this::issue);
        verifyNoInteractions(access);
    }

    @Test
    void decoderRejectingInvalidJwtCannotCreateTicketOrCheckAccess() {
        org.springframework.security.oauth2.jwt.JwtException failure =
                new org.springframework.security.oauth2.jwt.JwtException("invalid JWT");
        when(decoder.decode("token")).thenThrow(failure);
        assertSame(failure, assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, this::issue));
        verifyNoInteractions(converter, access);
    }

    @Test
    void revokedAccessDeniesIssueAndBurnsConsumedTicket() {
        authorize(principal, accessExpiry);
        when(access.isCurrent(principal, accessExpiry)).thenReturn(false);
        assertThrows(InvalidOperationException.class, this::issue);
        when(access.isCurrent(principal, accessExpiry)).thenReturn(true);
        String raw = issue().getTicket();
        when(access.isCurrent(principal, accessExpiry)).thenReturn(false);
        assertNull(service.consume(raw));
        when(access.isCurrent(principal, accessExpiry)).thenReturn(true);
        assertNull(service.consume(raw));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "...........................................", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa+"})
    void malformedTicketNeverReachesAccessCheck(String raw) {
        assertNull(service.consume(raw));
        verifyNoInteractions(access);
    }

    @Test
    void fourOutstandingTicketsAreScopedAndConsumptionReleasesCapacity() {
        authorize(principal, accessExpiry);
        String first = issue().getTicket();
        Set<String> raw = new HashSet<>();
        raw.add(first);
        for (int i = 0; i < 3; i++) raw.add(issue().getTicket());
        assertEquals(4, raw.size());
        assertThrows(InvalidOperationException.class, this::issue);
        assertNotNull(service.consume(first));
        assertNotNull(issue());
        TenantPrincipal other = principal(UUID.randomUUID(), principal.getTenantId(), principal.getOutletId());
        authorize(other, accessExpiry);
        assertNotNull(service.issue(other, "Bearer token"));
        TenantPrincipal otherOutlet = principal(principal.getUserId(), principal.getTenantId(), UUID.randomUUID());
        authorize(otherOutlet, accessExpiry);
        assertNotNull(service.issue(otherOutlet, "Bearer token"));
        TenantPrincipal otherTenant = principal(principal.getUserId(), UUID.randomUUID(), principal.getOutletId());
        authorize(otherTenant, accessExpiry);
        assertNotNull(service.issue(otherTenant, "Bearer token"));
        TenantPrincipal otherSession = new TenantPrincipal(principal.getUserId(), principal.getTenantId(),
                principal.getOutletId(), UUID.randomUUID(), UUID.randomUUID(), principal.getRoles());
        authorize(otherSession, accessExpiry);
        InvalidOperationException full = assertThrows(InvalidOperationException.class,
                () -> service.issue(otherSession, "Bearer token"));
        assertTrue(full.getMessage().contains("Kapasitas"), "A new JWT session must not bypass the scoped ticket limit");
    }

    @Test
    void globalTicketCapacityIsEnforcedAndCleanupReleasesIt() throws Exception {
        authorize(principal, accessExpiry);
        Map<String, FnbRealtimeTicketService.Ticket> storage = storage();
        for (int i = 0; i < 2_000; i++) {
            storage.put("fixture-" + i, ticket(principal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                    accessExpiry, accessExpiry));
        }
        assertThrows(InvalidOperationException.class, this::issue);
        storage.put("fixture-0", ticket(principal, Instant.EPOCH, accessExpiry));
        service.cleanup();
        assertNotNull(issue());
    }

    private FnbRealtimeTicketResponse issue() {
        return service.issue(principal, "Bearer token");
    }

    private void authorize(TenantPrincipal decoded, Instant expiresAt) {
        Jwt jwt = jwt(expiresAt);
        AbstractAuthenticationToken authentication = mock(AbstractAuthenticationToken.class);
        when(decoder.decode("token")).thenReturn(jwt);
        when(converter.convert(jwt)).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(decoded);
        when(access.isCurrent(decoded, expiresAt)).thenReturn(true);
    }

    private Jwt jwt(Instant expiresAt) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getExpiresAt()).thenReturn(expiresAt);
        return jwt;
    }

    @SuppressWarnings("unchecked")
    private static Converter<Jwt, AbstractAuthenticationToken> mockConverter() {
        return mock(Converter.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, FnbRealtimeTicketService.Ticket> storage() {
        return (Map<String, FnbRealtimeTicketService.Ticket>) ReflectionTestUtils.getField(service, "tickets");
    }

    private void replaceState(String raw, FnbRealtimeTicketService.Ticket state) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        storage().put(digest, state);
        assertFalse(storage().containsKey(raw), "Raw credentials must never be retained as storage keys");
    }

    private static FnbRealtimeTicketService.Ticket ticket(TenantPrincipal principal, Instant expiresAt, Instant accessExpiresAt) throws Exception {
        Constructor<FnbRealtimeTicketService.Ticket> constructor = FnbRealtimeTicketService.Ticket.class
                .getDeclaredConstructor(TenantPrincipal.class, Instant.class, Instant.class);
        constructor.setAccessible(true);
        return constructor.newInstance(principal, expiresAt, accessExpiresAt);
    }

    private static TenantPrincipal principal(UUID user, UUID tenant, UUID outlet) {
        return new TenantPrincipal(user, tenant, outlet, UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.CASHIER));
    }
}
