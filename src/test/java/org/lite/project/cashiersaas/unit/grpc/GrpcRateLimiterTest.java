package org.lite.project.cashiersaas.unit.grpc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.grpc.policy.GrpcRateLimiter;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class GrpcRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    @Test
    void rejectsRequestsPerSecondBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new GrpcRateLimiter(0, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new GrpcRateLimiter(-3, clock::get));
    }
}
