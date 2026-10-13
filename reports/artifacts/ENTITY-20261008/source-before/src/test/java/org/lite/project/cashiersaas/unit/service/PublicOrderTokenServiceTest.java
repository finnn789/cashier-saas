package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.service.PublicOrderTokenService;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PublicOrderTokenServiceTest {
    private final PublicOrderTokenService service = new PublicOrderTokenService(
            new SecretKeySpec("unit-test-public-order-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

    @Test
    void tokenIsDeterministicAndUrlSafeForSameTenantAndKey() {
        UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        String first = service.token(tenantId, "checkout-key-0001");
        String second = service.token(tenantId, "checkout-key-0001");

        assertEquals(first, second);
        assertEquals(43, first.length());
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    void tokenIsScopedByTenantAndIdempotencyKey() {
        UUID tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID tenantB = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertNotEquals(service.token(tenantA, "checkout-key-0001"),
                service.token(tenantB, "checkout-key-0001"));
        assertNotEquals(service.token(tenantA, "checkout-key-0001"),
                service.token(tenantA, "checkout-key-0002"));
    }

    @Test
    void digestIsStableAndProducesAStoredLengthDigest() {
        String token = service.token(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "checkout-key-0001");

        assertEquals(service.digest(token), service.digest(token));
        assertEquals(64, service.digest(token).length());
        assertTrue(service.digest(token).matches("[0-9a-f]{64}"));
    }
}
