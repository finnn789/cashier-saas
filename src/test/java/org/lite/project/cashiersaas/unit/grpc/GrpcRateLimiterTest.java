package org.lite.project.cashiersaas.unit.grpc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.grpc.policy.GrpcRateLimiter;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class GrpcRateLimiterTest {

    private static final long MILLI = 1_000_000L;

    private final AtomicLong clock = new AtomicLong(1_000L);

    private static int drain(GrpcRateLimiter limiter, int attempts) {
        int granted = 0;
        for (int index = 0; index < attempts; index++) {
            if (limiter.tryAcquire()) {
                granted++;
            }
        }
        return granted;
    }

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        GrpcRateLimiter limiter = new GrpcRateLimiter(5, clock::get);

        assertEquals(5, drain(limiter, 5));
        assertFalse(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void refillsContinuouslyAsTimePasses() {
        GrpcRateLimiter limiter = new GrpcRateLimiter(10, clock::get);
        assertEquals(10, drain(limiter, 10));
        assertFalse(limiter.tryAcquire());

        // 250 ms at 10 tokens/s refills 2.5 tokens: two acquisitions, then empty again.
        clock.addAndGet(250 * MILLI);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void clockMovingBackwardsNeitherRefillsNorFails() {
        GrpcRateLimiter limiter = new GrpcRateLimiter(2, clock::get);
        assertEquals(2, drain(limiter, 2));

        clock.addAndGet(-500 * MILLI);

        assertFalse(limiter.tryAcquire());
    }

    @Test
    void rejectsRequestsPerSecondBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new GrpcRateLimiter(0, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new GrpcRateLimiter(-3, clock::get));
    }
}
