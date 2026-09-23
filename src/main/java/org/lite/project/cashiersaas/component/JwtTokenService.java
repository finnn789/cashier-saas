package org.lite.project.cashiersaas.component;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.configuration.JwtProperties;
import org.lite.project.cashiersaas.enums.Role;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public String generateAccessToken(
            UUID userId,
            UUID tenantId,
            String email,
            Collection<Role> roles
    ) {
        requireTokenSubject(userId, tenantId, email, roles);

        Instant issuedAt = Instant.now();
        List<String> roleNames = roles.stream().map(Role::name).distinct().toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .audience(List.of(jwtProperties.getAudience()))
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(jwtProperties.getAccessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("email", email)
                .claim("roles", roleNames)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public String generateAccountAccessToken(UUID userId, String email) {
        Objects.requireNonNull(userId, "userId is required.");
        Objects.requireNonNull(email, "email is required.");
        if (email.isBlank()) {
            throw new IllegalArgumentException("email is required.");
        }

        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .audience(List.of(jwtProperties.getAudience()))
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(jwtProperties.getAccessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("scope", "ACCOUNT")
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private void requireTokenSubject(UUID userId, UUID tenantId, String email, Collection<Role> roles) {
        Objects.requireNonNull(userId, "userId is required.");
        Objects.requireNonNull(tenantId, "tenantId is required.");
        Objects.requireNonNull(email, "email is required.");

        if (email.isBlank()) {
            throw new IllegalArgumentException("email is required.");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required.");
        }
    }
}
