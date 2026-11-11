package org.lite.project.cashiersaas.shared;

/**
 * Static, non-tunable values of the REST API rate limiter. Operator-tunable limits live in
 * application.properties (CASHIER_RATE_LIMIT_*), not here.
 */
public final class RateLimitConstants {

    /** A bucket that was not used for this long may be evicted when the bucket map is full. */
    public static final long IDLE_BUCKET_SECONDS = 600L;
    /** The bucket map is also swept for idle entries on every Nth check. */
    public static final long SWEEP_EVERY_CALLS = 1000L;
    /** The "map is full, failing open" warning is logged at most once per this interval. */
    public static final long FULL_MAP_WARN_INTERVAL_SECONDS = 60L;
    public static final double SECONDS_PER_MINUTE = 60.0d;
    public static final double NANOS_PER_SECOND = 1_000_000_000d;
    public static final String RETRY_AFTER_HEADER = "Retry-After";
    public static final String API_PREFIX = "/api/";

    private RateLimitConstants() {
    }
}
