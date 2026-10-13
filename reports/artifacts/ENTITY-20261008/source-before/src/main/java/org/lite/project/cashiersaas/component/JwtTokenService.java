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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public String generateAccessToken(MerchantTokenContext context) {
        requireTokenSubject(context);

        Instant issuedAt = Instant.now();
        List<String> roleNames = context.getRoles().stream().map(Role::name).distinct().toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .audience(List.of(jwtProperties.getAudience()))
                .subject(context.getUserId().toString())
                .issuedAt(issuedAt)
                .expiresAt(context.getExpiresAt())
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", context.getTenantId().toString())
                .claim("outlet_id", context.getOutletId().toString())
                .claim("membership_id", context.getMembershipId().toString())
                .claim("session_id", context.getSessionId().toString())
                .claim("scope", "MERCHANT")
                .claim("email", context.getEmail())
                .claim("roles", roleNames)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public String generateAccountAccessToken(UUID userId, String email, UUID sessionId) {
        return generateAccountAccessToken(userId, email, sessionId,
                Instant.now().plus(jwtProperties.getAccessTokenTtl()));
    }

    public String generateAccountAccessToken(UUID userId, String email, UUID sessionId, Instant expiresAt) {
        Objects.requireNonNull(userId, "userId is required.");
        Objects.requireNonNull(email, "email is required.");
        Objects.requireNonNull(sessionId, "sessionId is required.");
        Objects.requireNonNull(expiresAt, "expiresAt is required.");
        if (email.isBlank()) {
            throw new IllegalArgumentException("email is required.");
        }

        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .audience(List.of(jwtProperties.getAudience()))
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("scope", "ACCOUNT")
                .claim("session_id", sessionId.toString())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private void requireTokenSubject(MerchantTokenContext context) {
        Objects.requireNonNull(context, "context is required.");
        Objects.requireNonNull(context.getUserId(), "userId is required.");
        Objects.requireNonNull(context.getTenantId(), "tenantId is required.");
        Objects.requireNonNull(context.getOutletId(), "outletId is required.");
        Objects.requireNonNull(context.getMembershipId(), "membershipId is required.");
        Objects.requireNonNull(context.getSessionId(), "sessionId is required.");
        Objects.requireNonNull(context.getEmail(), "email is required.");
        Objects.requireNonNull(context.getExpiresAt(), "expiresAt is required.");

        if (context.getEmail().isBlank()) {
            throw new IllegalArgumentException("email is required.");
        }
        if (context.getRoles() == null || context.getRoles().isEmpty()) {
            throw new IllegalArgumentException("At least one role is required.");
        }
    }
}
