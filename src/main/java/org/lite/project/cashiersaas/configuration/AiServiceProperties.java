package org.lite.project.cashiersaas.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Settings for pushing staff members to Ai-services (contract: docs/contracts/member-sync.md). The sync is
 * disabled while {@code base-url} is blank. The API key is supplied only through the environment
 * (AI_INTERNAL_API_KEY) and must never be logged.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ai-service")
public class AiServiceProperties {

    /** Base URL of Ai-services, for example http://ai-services:8000. Blank disables the sync. */
    private String baseUrl = "";

    /** Shared secret sent as X-Internal-Service-Key. */
    private String apiKey = "";

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(5);

    /** Read timeout of the daily summary request (Ai-services builds the text, including one LLM call). */
    private Duration dailySummaryReadTimeout = Duration.ofSeconds(30);

    /** Total attempts per sync (first call plus retries). */
    private int maxAttempts = 3;

    /** Pause before retry number n (the last value is reused when there are more retries than values). */
    private List<Duration> retryBackoff = List.of(Duration.ofMillis(200), Duration.ofMillis(800));

    public boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
