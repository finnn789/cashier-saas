package org.lite.project.cashiersaas.unit.ratelimit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.shared.TokenBucket;

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
class TokenBucketTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    private static int drain(TokenBucket bucket, int attempts) {
        int granted = 0;
        for (int index = 0; index < attempts; index++) {
            if (bucket.tryAcquire()) {
                granted++;
            }
        }
        return granted;
    }

    @Test
    void startsFullThenRejectsWhenExhausted() {
        TokenBucket bucket = new TokenBucket(3, 1, clock::get);

        assertEquals(3, drain(bucket, 10));
        assertFalse(bucket.tryAcquire());
    }

    @Test
    void refillsOverTimeButNeverBeyondCapacity() {
        TokenBucket bucket = new TokenBucket(2, 1, clock::get);
        assertEquals(2, drain(bucket, 2));

        clock.addAndGet(1_500 * 1_000_000L);
        assertEquals(1, drain(bucket, 5), "1.5 s at 1 token per second");

        clock.addAndGet(TimeUnit.HOURS.toNanos(1));
        assertEquals(2, drain(bucket, 10), "capped at capacity");
    }

    @Test
    void supportsFractionalRefillRate() {
        // 6 per minute = 0.1 token per second: one token needs 10 seconds.
        TokenBucket bucket = new TokenBucket(1, 6 / 60.0, clock::get);
        assertTrue(bucket.tryAcquire());

        clock.addAndGet(TimeUnit.SECONDS.toNanos(9));
        assertFalse(bucket.tryAcquire());

        clock.addAndGet(1_500 * 1_000_000L);
        assertTrue(bucket.tryAcquire());
    }

    @Test
    void secondsUntilNextTokenIsZeroWhenAvailableAndDoesNotConsume() {
        TokenBucket bucket = new TokenBucket(1, 1, clock::get);

        assertEquals(0, bucket.secondsUntilNextToken());
        assertEquals(0, bucket.secondsUntilNextToken());
        assertTrue(bucket.tryAcquire(), "the token must still be there");
    }

    @Test
    void secondsUntilNextTokenIsCeilingAndAtLeastOneWhenEmpty() {
        // 0.1 token per second: an empty bucket needs 10 s for the next token.
        TokenBucket bucket = new TokenBucket(1, 0.1, clock::get);
        assertTrue(bucket.tryAcquire());
        assertEquals(10, bucket.secondsUntilNextToken());

        // 2.5 s later 0.25 token is back: 0.75 / 0.1 = 7.5 -> ceil 8.
        clock.addAndGet(2_500 * 1_000_000L);
        assertEquals(8, bucket.secondsUntilNextToken());

        // Fast bucket (10/s) empty: needs 0.1 s, never reported below 1 s.
        TokenBucket fast = new TokenBucket(1, 10, clock::get);
        assertTrue(fast.tryAcquire());
        assertEquals(1, fast.secondsUntilNextToken());
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0.5, 1, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(5, 0, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(5, -1, clock::get));
    }

    @Test
    void grantsAtMostCapacityUnderConcurrentAccessWithFrozenClock() throws Exception {
        int capacity = 50;
        int threads = 8;
        int attemptsPerThread = 40;
        TokenBucket bucket = new TokenBucket(capacity, 1, clock::get);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                Callable<Integer> task = () -> {
                    startGate.await();
                    return drain(bucket, attemptsPerThread);
                };
                futures.add(executor.submit(task));
            }
            startGate.countDown();

            int granted = 0;
            for (Future<Integer> future : futures) {
                granted += future.get(10, TimeUnit.SECONDS);
            }

            assertEquals(capacity, granted);
            assertFalse(bucket.tryAcquire());
        } finally {
            executor.shutdownNow();
        }
    }
}
