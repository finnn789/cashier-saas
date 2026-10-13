package org.lite.project.cashiersaas.component;

import org.lite.project.cashiersaas.enums.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class TenantJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = requiredUuidClaim(jwt, "sub");
        UUID sessionId = requiredUuidClaim(jwt, "session_id");
        if ("ACCOUNT".equals(jwt.getClaimAsString("scope"))) {
            AccountPrincipal principal = new AccountPrincipal(userId, requiredEmail(jwt), sessionId);
            Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority("SCOPE_ACCOUNT"));
            return new UsernamePasswordAuthenticationToken(principal, null, authorities);
        }

        if (!"MERCHANT".equals(jwt.getClaimAsString("scope"))) {
            throw new BadCredentialsException("JWT scope is invalid.");
        }

        UUID tenantId = requiredUuidClaim(jwt, "tenant_id");
        UUID outletId = requiredUuidClaim(jwt, "outlet_id");
        UUID membershipId = requiredUuidClaim(jwt, "membership_id");
        Set<Role> roles = requiredRoles(jwt);

        TenantPrincipal principal = new TenantPrincipal(userId, tenantId, outletId, membershipId, sessionId, roles);
        Set<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private String requiredEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new BadCredentialsException("JWT claim 'email' is required.");
        }
        return email;
    }

    private UUID requiredUuidClaim(Jwt jwt, String claimName) {
        String claimValue = jwt.getClaimAsString(claimName);
        if (claimValue == null || claimValue.isBlank()) {
            throw new BadCredentialsException("JWT claim '" + claimName + "' must be a UUID.");
        }

        try {
            return UUID.fromString(claimValue);
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException("JWT claim '" + claimName + "' must be a UUID.", exception);
        }
    }

    private Set<Role> requiredRoles(Jwt jwt) {
        List<String> roleClaims = jwt.getClaimAsStringList("roles");
        if (roleClaims == null || roleClaims.isEmpty()) {
            throw new BadCredentialsException("JWT claim 'roles' is required.");
        }

        try {
            return roleClaims.stream()
                    .map(Role::valueOf)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException("JWT contains an unsupported role.", exception);
        }
    }
}
