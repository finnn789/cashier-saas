package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.service.DigestService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DigestServiceTest {
    private final DigestService service = new DigestService();

    @Test
    void digestIsStableProducesAStoredLengthDigestAndDiffersForDifferentInput() {
        String value = "checkout-key-0001";

        assertEquals(service.digest(value), service.digest(value));
        assertEquals(64, service.digest(value).length());
        assertTrue(service.digest(value).matches("[0-9a-f]{64}"));
        assertNotEquals(service.digest("checkout-key-0001"), service.digest("checkout-key-0002"));
    }
}
