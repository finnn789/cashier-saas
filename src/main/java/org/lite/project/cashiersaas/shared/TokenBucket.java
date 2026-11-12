package org.lite.project.cashiersaas.shared;

import java.util.function.LongSupplier;

/**
 * Thread-safe token bucket. It starts full, refills continuously up to its capacity and each
 * successful {@link #tryAcquire()} consumes one token.
 */
public class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private final double tokensPerNano;
    private final LongSupplier nanoClock;
    private final Object lock = new Object();

    private double availableTokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1.");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be greater than 0.");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokensPerNano = refillPerSecond / RateLimitConstants.NANOS_PER_SECOND;
        this.nanoClock = nanoClock;
        this.availableTokens = capacity;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    public boolean tryAcquire() {
        synchronized (lock) {
            refill();
            if (availableTokens >= 1.0d) {
                availableTokens -= 1.0d;
                return true;
            }
            return false;
        }
    }

    /** Returns 0 when a token is available now (nothing is consumed), otherwise at least 1 second. */
    public long secondsUntilNextToken() {
        synchronized (lock) {
            refill();
            if (availableTokens >= 1.0d) {
                return 0L;
            }
            return Math.max(1L, (long) Math.ceil((1.0d - availableTokens) / refillPerSecond));
        }
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            availableTokens = Math.min(capacity, availableTokens + elapsed * tokensPerNano);
            lastRefillNanos = now;
        }
    }
}
