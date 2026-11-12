package org.lite.project.cashiersaas.unit.ratelimit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.component.HttpRateLimiter;
import org.lite.project.cashiersaas.component.HttpRateLimiter.Decision;
import org.lite.project.cashiersaas.component.HttpRateLimiter.Group;
import org.lite.project.cashiersaas.configuration.RateLimitProperties;
import org.lite.project.cashiersaas.shared.RateLimitConstants;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class HttpRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final RateLimitProperties properties = RateLimitTestProperties.defaults();

    private HttpRateLimiter limiter() {
        return new HttpRateLimiter(properties, clock::get);
    }

    private static int drain(HttpRateLimiter limiter, Group group, String key, int attempts) {
        int granted = 0;
        for (int index = 0; index < attempts; index++) {
            if (limiter.check(group, key).isAllowed()) {
                granted++;
            }
        }
        return granted;
    }

    @Test
    void everyGroupAllowsExactlyItsDefaultBurst() {
        HttpRateLimiter limiter = limiter();

        assertEquals(10, drain(limiter, Group.AUTH, "k", 50));
        assertEquals(60, drain(limiter, Group.PUBLIC_READ, "k", 300));
        assertEquals(10, drain(limiter, Group.PUBLIC_ORDER_WRITE, "k", 50));
        assertEquals(120, drain(limiter, Group.QR_TOKEN, "k", 300));
        assertEquals(5, drain(limiter, Group.REALTIME_TICKET, "k", 50));
        assertEquals(60, drain(limiter, Group.AUTHENTICATED, "k", 300));
        assertEquals(30, drain(limiter, Group.ANONYMOUS_OTHER, "k", 300));
    }

    @Test
    void configuredLimitsAreUsed() {
        properties.setAuth(new RateLimitProperties.Limit(60, 2));
        HttpRateLimiter limiter = limiter();

        assertEquals(2, drain(limiter, Group.AUTH, "k", 10));
        // 60 per minute = 1 per second: 1.5 s later one more request is allowed.
        clock.addAndGet(1_500 * 1_000_000L);
        assertEquals(1, drain(limiter, Group.AUTH, "k", 10));
    }

    @Test
    void differentKeysAreIndependent() {
        HttpRateLimiter limiter = limiter();
        assertEquals(10, drain(limiter, Group.AUTH, "1.1.1.1", 20));

        assertFalse(limiter.check(Group.AUTH, "1.1.1.1").isAllowed());
        assertTrue(limiter.check(Group.AUTH, "2.2.2.2").isAllowed());
    }

    @Test
    void sameKeyInDifferentGroupsIsIndependent() {
        HttpRateLimiter limiter = limiter();
        assertEquals(10, drain(limiter, Group.AUTH, "1.1.1.1", 20));

        assertTrue(limiter.check(Group.PUBLIC_READ, "1.1.1.1").isAllowed());
        assertTrue(limiter.check(Group.ANONYMOUS_OTHER, "1.1.1.1").isAllowed());
    }

    @Test
    void denyCarriesRetryAfterOfAtLeastOneSecond() {
        HttpRateLimiter limiter = limiter();
        drain(limiter, Group.AUTH, "k", 10);

        Decision decision = limiter.check(Group.AUTH, "k");

        assertFalse(decision.isAllowed());
        // 10 per minute = one token every 6 seconds (7 allowed for floating point rounding).
        assertTrue(decision.getRetryAfterSeconds() >= 6 && decision.getRetryAfterSeconds() <= 7);

        properties.setRealtimeTicket(new RateLimitProperties.Limit(6000, 1));
        HttpRateLimiter fast = limiter();
        fast.check(Group.REALTIME_TICKET, "k");
        assertTrue(fast.check(Group.REALTIME_TICKET, "k").getRetryAfterSeconds() >= 1);
    }

    @Test
    void allowDecisionHasNoRetryAfter() {
        Decision decision = limiter().check(Group.AUTH, "k");

        assertTrue(decision.isAllowed());
        assertEquals(0, decision.getRetryAfterSeconds());
    }

    @Test
    void reportsDisabledWhenConfiguredOff() {
        assertTrue(limiter().isEnabled());

        properties.setEnabled(false);

        assertFalse(limiter().isEnabled());
    }

    @Test
    void idleEntriesAreEvictedWhenTheMapIsFull() {
        properties.setMaxBuckets(2);
        properties.setAuth(new RateLimitProperties.Limit(1, 1));
        HttpRateLimiter limiter = limiter();

        assertTrue(limiter.check(Group.AUTH, "a").isAllowed());
        assertTrue(limiter.check(Group.AUTH, "b").isAllowed());

        // Map is full and nothing is idle yet: "c" cannot be tracked and fails open.
        assertTrue(limiter.check(Group.AUTH, "c").isAllowed());
        assertTrue(limiter.check(Group.AUTH, "c").isAllowed());

        clock.addAndGet(TimeUnit.SECONDS.toNanos(RateLimitConstants.IDLE_BUCKET_SECONDS + 1));

        // Inserting "c" now sweeps the idle "a" and "b"; "c" is tracked, so its second request is throttled.
        assertTrue(limiter.check(Group.AUTH, "c").isAllowed());
        assertFalse(limiter.check(Group.AUTH, "c").isAllowed());
    }

    @Test
    void failsOpenWhenTheMapIsFullOfActiveEntries() {
        properties.setMaxBuckets(1);
        properties.setAuth(new RateLimitProperties.Limit(1, 1));
        HttpRateLimiter limiter = limiter();

        assertTrue(limiter.check(Group.AUTH, "a").isAllowed());
        assertFalse(limiter.check(Group.AUTH, "a").isAllowed());

        // "b" cannot be tracked (map full, nothing idle), so it is never throttled.
        assertTrue(limiter.check(Group.AUTH, "b").isAllowed());
        assertTrue(limiter.check(Group.AUTH, "b").isAllowed());
        assertTrue(limiter.check(Group.AUTH, "b").isAllowed());
    }
}
