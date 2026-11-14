package org.lite.project.cashiersaas.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Rate limits of the REST API, supplied only through application.properties (CASHIER_RATE_LIMIT_* variables).
 * {@code perMinute} is the refill rate and {@code burst} the bucket capacity.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled;

    @Min(1)
    private int maxBuckets;

    @NotNull
    @Valid
    private Limit auth;

    @NotNull
    @Valid
    private Limit authenticated;

    @NotNull
    @Valid
    private Limit anonymousOther;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Limit {

        @Min(1)
        private int perMinute;

        @Min(1)
        private int burst;

        public Limit(int perMinute, int burst) {
            this.perMinute = perMinute;
            this.burst = burst;
        }
    }
}
