package org.lite.project.cashiersaas.grpc.policy;

import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;
import org.lite.project.cashiersaas.shared.TokenBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Global in-memory token bucket. Capacity equals the configured requests per second and tokens
 * refill continuously, so short bursts up to one second of traffic are allowed.
 */
@Component
public class GrpcRateLimiter {

    private final TokenBucket bucket;

    @Autowired
    public GrpcRateLimiter(GrpcSecurityProperties properties) {
        this(properties.getRequestsPerSecond(), System::nanoTime);
    }

    public GrpcRateLimiter(int requestsPerSecond, LongSupplier nanoClock) {
        if (requestsPerSecond < 1) {
            throw new IllegalArgumentException("requestsPerSecond must be at least 1.");
        }
        this.bucket = new TokenBucket(requestsPerSecond, requestsPerSecond, nanoClock);
    }

    public boolean tryAcquire() {
        return bucket.tryAcquire();
    }
}
