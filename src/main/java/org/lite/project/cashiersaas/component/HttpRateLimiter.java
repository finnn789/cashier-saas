package org.lite.project.cashiersaas.component;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.configuration.RateLimitProperties;
import org.lite.project.cashiersaas.shared.RateLimitConstants;
import org.lite.project.cashiersaas.shared.TokenBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory token buckets for the REST API, one bucket per group and key (IP, user or QR token).
 * // shortcut: in-memory buckets are per instance, move to Bucket4j + Redis when more than one instance runs.
 */
@Slf4j
@Component
public class HttpRateLimiter {

    public enum Group {
        AUTH, PUBLIC_READ, PUBLIC_ORDER_WRITE, QR_TOKEN, REALTIME_TICKET, AUTHENTICATED, ANONYMOUS_OTHER
    }

    @Getter
    public static final class Decision {

        private static final Decision ALLOWED = new Decision(true, 0L);

        private final boolean allowed;
        private final long retryAfterSeconds;

        private Decision(boolean allowed, long retryAfterSeconds) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public static Decision allow() {
            return ALLOWED;
        }

        public static Decision deny(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    private static final class Entry {
        private final TokenBucket bucket;
        private volatile long lastSeenNanos;

        private Entry(TokenBucket bucket, long lastSeenNanos) {
            this.bucket = bucket;
            this.lastSeenNanos = lastSeenNanos;
        }
    }

    private final RateLimitProperties properties;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Entry> buckets = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong lastFullWarnNanos = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public HttpRateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    public HttpRateLimiter(RateLimitProperties properties, LongSupplier nanoClock) {
        this.properties = properties;
        this.nanoClock = nanoClock;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Decision check(Group group, String key) {
        long now = nanoClock.getAsLong();
        if (calls.incrementAndGet() % RateLimitConstants.SWEEP_EVERY_CALLS == 0) {
            sweep(now);
        }
        String mapKey = group.name() + ":" + key;
        Entry entry = buckets.get(mapKey);
        if (entry == null) {
            if (buckets.size() >= properties.getMaxBuckets()) {
                sweep(now);
                if (buckets.size() >= properties.getMaxBuckets()) {
                    warnFull(now);
                    // shortcut: fail open when the map is full, move to a bounded cache if this ever logs.
                    return Decision.allow();
                }
            }
            entry = buckets.computeIfAbsent(mapKey, ignored -> new Entry(newBucket(group), now));
        }
        entry.lastSeenNanos = now;
        if (entry.bucket.tryAcquire()) {
            return Decision.allow();
        }
        return Decision.deny(Math.max(1L, entry.bucket.secondsUntilNextToken()));
    }

    private TokenBucket newBucket(Group group) {
        RateLimitProperties.Limit limit = limitOf(group);
        return new TokenBucket(limit.getBurst(), limit.getPerMinute() / RateLimitConstants.SECONDS_PER_MINUTE, nanoClock);
    }

    private RateLimitProperties.Limit limitOf(Group group) {
        return switch (group) {
            case AUTH -> properties.getAuth();
            case PUBLIC_READ -> properties.getPublicRead();
            case PUBLIC_ORDER_WRITE -> properties.getPublicOrderWrite();
            case QR_TOKEN -> properties.getQrToken();
            case REALTIME_TICKET -> properties.getRealtimeTicket();
            case AUTHENTICATED -> properties.getAuthenticated();
            case ANONYMOUS_OTHER -> properties.getAnonymousOther();
        };
    }

    private void sweep(long now) {
        long idleNanos = TimeUnit.SECONDS.toNanos(RateLimitConstants.IDLE_BUCKET_SECONDS);
        buckets.values().removeIf(entry -> now - entry.lastSeenNanos > idleNanos);
    }

    private void warnFull(long now) {
        long last = lastFullWarnNanos.get();
        long warnIntervalNanos = TimeUnit.SECONDS.toNanos(RateLimitConstants.FULL_MAP_WARN_INTERVAL_SECONDS);
        if ((last == Long.MIN_VALUE || now - last >= warnIntervalNanos)
                && lastFullWarnNanos.compareAndSet(last, now)) {
            log.warn("Rate limiter bucket map is full, failing open: buckets={}, max={}",
                    buckets.size(), properties.getMaxBuckets());
        }
    }
}
