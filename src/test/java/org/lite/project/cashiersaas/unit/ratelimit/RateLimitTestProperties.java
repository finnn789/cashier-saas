package org.lite.project.cashiersaas.unit.ratelimit;

import org.lite.project.cashiersaas.configuration.RateLimitProperties;
import org.lite.project.cashiersaas.configuration.RateLimitProperties.Limit;

/** Builds RateLimitProperties with the production defaults of application.properties (no Spring context). */
final class RateLimitTestProperties {

    private RateLimitTestProperties() {
    }

    static RateLimitProperties defaults() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxBuckets(100000);
        properties.setAuth(new Limit(10, 10));
        properties.setPublicRead(new Limit(240, 60));
        properties.setPublicOrderWrite(new Limit(20, 10));
        properties.setQrToken(new Limit(600, 120));
        properties.setRealtimeTicket(new Limit(10, 5));
        properties.setAuthenticated(new Limit(300, 60));
        properties.setAnonymousOther(new Limit(120, 30));
        return properties;
    }
}
