package org.lite.project.cashiersaas.service;

import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.configuration.AiServiceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Asks Ai-services to build and deliver one outlet's daily summary. Single attempt (retries come from the
 * scheduler); never throws; never logs the API key. Disabled when the AI base URL or key is blank or
 * {@code app.daily-summary.enabled} is false.
 */
@Slf4j
@Service
public class DailySummaryClient {
    private final AiServiceProperties properties;
    private final RestClient restClient;
    private final boolean enabled;

    @Autowired
    public DailySummaryClient(AiServiceProperties properties,
                              @Value("${app.daily-summary.enabled:true}") boolean featureEnabled) {
        this(properties, featureEnabled, featureEnabled && properties.isEnabled() ? buildRestClient(properties) : null);
    }

    /** Test seam: a pre-built client (for example bound to a MockRestServiceServer). */
    public DailySummaryClient(AiServiceProperties properties, boolean featureEnabled, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
        this.enabled = featureEnabled && restClient != null && properties.isEnabled()
                && properties.getApiKey() != null && !properties.getApiKey().isBlank();
        if (featureEnabled && !enabled) {
            log.info("Daily summary dispatch is disabled (app.ai-service.base-url or api-key is blank).");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True on a 2xx response; false on anything else, including I/O errors. */
    public boolean send(UUID tenantId, UUID outletId, LocalDate businessDate) {
        if (!enabled) {
            return false;
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("tenant_id", tenantId.toString());
        body.put("outlet_id", outletId.toString());
        body.put("business_date", businessDate.toString());
        try {
            String base = properties.getBaseUrl().trim();
            URI uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                    + "/api/v1/internal/daily-summaries");
            ResponseEntity<Void> response = restClient.post().uri(uri)
                    .header(MemberSyncClient.KEY_HEADER, properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (RuntimeException exception) {
            log.warn("Daily summary request failed: tenantId={} outletId={} error={}", tenantId, outletId,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private static RestClient buildRestClient(AiServiceProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getDailySummaryReadTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
