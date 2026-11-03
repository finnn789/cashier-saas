package org.lite.project.cashiersaas.unit.grpc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.grpc.policy.GrpcRateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    void partialRefillAccumulatesAcrossCalls() {
        GrpcRateLimiter limiter = new GrpcRateLimiter(10, clock::get);
        assertEquals(10, drain(limiter, 10));

        clock.addAndGet(50 * MILLI);
        assertFalse(limiter.tryAcquire(), "0.5 token is not enough");

        clock.addAndGet(100 * MILLI);
        assertTrue(limiter.tryAcquire(), "1.5 accumulated tokens allow one request");
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void neverExceedsCapacityAfterLongIdlePeriod() {
        GrpcRateLimiter limiter = new GrpcRateLimiter(4, clock::get);

        clock.addAndGet(TimeUnit.HOURS.toNanos(1));

        assertEquals(4, drain(limiter, 20));
    }

    @Test
    void idleTimeAfterPartialUseDoesNotAddTokensBeyondCapacity() {
        GrpcRateLimiter limiter = new GrpcRateLimiter(3, clock::get);
        assertEquals(1, drain(limiter, 1));

        clock.addAndGet(TimeUnit.SECONDS.toNanos(30));

        assertEquals(3, drain(limiter, 10));
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

    @Test
    void grantsExactlyCapacityUnderConcurrentAccessWithFrozenClock() throws Exception {
        int capacity = 100;
        int threads = 8;
        int attemptsPerThread = 50;
        GrpcRateLimiter limiter = new GrpcRateLimiter(capacity, clock::get);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                Callable<Integer> task = () -> {
                    startGate.await();
                    return drain(limiter, attemptsPerThread);
                };
                futures.add(executor.submit(task));
            }
            startGate.countDown();

            int granted = 0;
            for (Future<Integer> future : futures) {
                granted += future.get(10, TimeUnit.SECONDS);
            }

            assertEquals(capacity, granted);
            assertFalse(limiter.tryAcquire());
        } finally {
            executor.shutdownNow();
        }
    }
}
