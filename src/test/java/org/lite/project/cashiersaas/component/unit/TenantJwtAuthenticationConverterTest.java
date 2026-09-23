package org.lite.project.cashiersaas.component.unit;

import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.component.TenantJwtAuthenticationConverter;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.enums.Role;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantJwtAuthenticationConverterTest {

    private final TenantJwtAuthenticationConverter converter = new TenantJwtAuthenticationConverter();

    @Test
    void convertsValidJwtIntoTenantPrincipalAndRoleAuthorities() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Jwt jwt = jwt(Map.of("sub", userId.toString(), "tenant_id", tenantId.toString(),
                "roles", List.of("OWNERMERCHANT", "CASHIER", "CASHIER")));

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        TenantPrincipal principal = assertInstanceOf(TenantPrincipal.class, authentication.getPrincipal());
        assertEquals(userId, principal.getUserId());
        assertEquals(tenantId, principal.getTenantId());
        assertEquals(Set.of(Role.OWNERMERCHANT, Role.CASHIER), principal.getRoles());
        assertEquals(Set.of(new SimpleGrantedAuthority("ROLE_OWNERMERCHANT"), new SimpleGrantedAuthority("ROLE_CASHIER")),
                Set.copyOf(authentication.getAuthorities()));
        assertEquals("token-value", authentication.getCredentials());
    }

    @Test
    void rejectsMissingTenantIdClaim() {
        Jwt jwt = jwt(Map.of("sub", UUID.randomUUID().toString(), "roles", List.of("CASHIER")));

        BadCredentialsException exception = assertThrows(BadCredentialsException.class, () -> converter.convert(jwt));

        assertEquals("JWT claim 'tenant_id' must be a UUID.", exception.getMessage());
    }

    @Test
    void rejectsInvalidTenantIdClaim() {
        Jwt jwt = jwt(Map.of("sub", UUID.randomUUID().toString(), "tenant_id", "not-a-uuid", "roles", List.of("CASHIER")));

        BadCredentialsException exception = assertThrows(BadCredentialsException.class, () -> converter.convert(jwt));

        assertEquals("JWT claim 'tenant_id' must be a UUID.", exception.getMessage());
    }

    @Test
    void rejectsMissingRolesClaim() {
        Jwt jwt = jwt(Map.of("sub", UUID.randomUUID().toString(), "tenant_id", UUID.randomUUID().toString()));

        BadCredentialsException exception = assertThrows(BadCredentialsException.class, () -> converter.convert(jwt));

        assertEquals("JWT claim 'roles' is required.", exception.getMessage());
    }

    @Test
    void rejectsUnsupportedRoleClaim() {
        Jwt jwt = jwt(Map.of("sub", UUID.randomUUID().toString(), "tenant_id", UUID.randomUUID().toString(),
                "roles", List.of("MANAGER")));

        BadCredentialsException exception = assertThrows(BadCredentialsException.class, () -> converter.convert(jwt));

        assertEquals("JWT contains an unsupported role.", exception.getMessage());
    }

    private Jwt jwt(Map<String, Object> claims) {
        Instant issuedAt = Instant.now();
        return new Jwt("token-value", issuedAt, issuedAt.plusSeconds(60), Map.of("alg", "HS256"), claims);
    }
}
