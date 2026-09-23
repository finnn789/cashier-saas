package org.lite.project.cashiersaas.component.unit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.configuration.JwtProperties;
import org.lite.project.cashiersaas.component.JwtTokenService;
import org.lite.project.cashiersaas.enums.Role;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtTokenServiceTest {

    @Test
    void generatesAccessTokenWithRequiredIdentityAndJwtClaims() {
        JwtEncoder encoder = mock(JwtEncoder.class);
        JwtProperties properties = jwtProperties();
        JwtTokenService service = new JwtTokenService(encoder, properties);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Jwt encodedJwt = jwt("encoded-token", Instant.now());
        when(encoder.encode(any(JwtEncoderParameters.class))).thenReturn(encodedJwt);

        String token = service.generateAccessToken(userId, tenantId, "owner@example.com",
                List.of(Role.OWNERMERCHANT, Role.CASHIER, Role.CASHIER));

        ArgumentCaptor<JwtEncoderParameters> parametersCaptor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(parametersCaptor.capture());
        JwtEncoderParameters parameters = parametersCaptor.getValue();

        assertEquals("encoded-token", token);
        assertEquals(MacAlgorithm.HS256, parameters.getJwsHeader().getAlgorithm());
        assertEquals("JWT", parameters.getJwsHeader().getType());
        assertEquals("https://issuer.example", parameters.getClaims().getIssuer().toString());
        assertEquals(List.of("cashier-api"), parameters.getClaims().getAudience());
        assertEquals(userId.toString(), parameters.getClaims().getSubject());
        assertEquals(tenantId.toString(), parameters.getClaims().getClaim("tenant_id"));
        assertEquals("owner@example.com", parameters.getClaims().getClaim("email"));
        assertEquals(List.of("OWNERMERCHANT", "CASHIER"), parameters.getClaims().getClaim("roles"));
        assertNotNull(parameters.getClaims().getIssuedAt());
        assertNotNull(parameters.getClaims().getExpiresAt());
        assertEquals(properties.getAccessTokenTtl(), Duration.between(
                parameters.getClaims().getIssuedAt(), parameters.getClaims().getExpiresAt()));
        assertNotNull(parameters.getClaims().getId());
        assertDoesNotThrowUuidParsing(parameters.getClaims().getId());
    }

    @Test
    void rejectsBlankEmailWithoutEncodingToken() {
        JwtEncoder encoder = mock(JwtEncoder.class);
        JwtTokenService service = new JwtTokenService(encoder, jwtProperties());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.generateAccessToken(UUID.randomUUID(), UUID.randomUUID(), "   ", List.of(Role.CASHIER)));

        assertEquals("email is required.", exception.getMessage());
        verify(encoder, never()).encode(any(JwtEncoderParameters.class));
    }

    @Test
    void rejectsEmptyRolesWithoutEncodingToken() {
        JwtEncoder encoder = mock(JwtEncoder.class);
        JwtTokenService service = new JwtTokenService(encoder, jwtProperties());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.generateAccessToken(UUID.randomUUID(), UUID.randomUUID(), "cashier@example.com", List.of()));

        assertEquals("At least one role is required.", exception.getMessage());
        verify(encoder, never()).encode(any(JwtEncoderParameters.class));
    }

    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("https://issuer.example");
        properties.setAudience("cashier-api");
        properties.setAccessTokenTtl(Duration.ofMinutes(20));
        return properties;
    }

    private Jwt jwt(String tokenValue, Instant issuedAt) {
        return new Jwt(tokenValue, issuedAt, issuedAt.plusSeconds(60), Map.of("alg", "HS256"), Map.of("sub", "test"));
    }

    private void assertDoesNotThrowUuidParsing(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new AssertionError("Expected a UUID, but was " + value, exception);
        }
    }
}
