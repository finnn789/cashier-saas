package org.lite.project.cashiersaas.grpc.policy;

import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Global in-memory token bucket. Capacity equals the configured requests per second and tokens
 * refill continuously, so short bursts up to one second of traffic are allowed.
 */
@Component
public class GrpcRateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final double capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoClock;
    private final Object lock = new Object();

    private double availableTokens;
    private long lastRefillNanos;

    @Autowired
    public GrpcRateLimiter(GrpcSecurityProperties properties) {
        this(properties.getRequestsPerSecond(), System::nanoTime);
    }

    public GrpcRateLimiter(int requestsPerSecond, LongSupplier nanoClock) {
        if (requestsPerSecond < 1) {
            throw new IllegalArgumentException("requestsPerSecond must be at least 1.");
        }
        this.capacity = requestsPerSecond;
        this.tokensPerNano = (double) requestsPerSecond / NANOS_PER_SECOND;
        this.nanoClock = nanoClock;
        this.availableTokens = requestsPerSecond;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    public boolean tryAcquire() {
        synchronized (lock) {
            long now = nanoClock.getAsLong();
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                availableTokens = Math.min(capacity, availableTokens + elapsed * tokensPerNano);
                lastRefillNanos = now;
            }
            if (availableTokens >= 1.0d) {
                availableTokens -= 1.0d;
                return true;
            }
            return false;
        }
    }
}
