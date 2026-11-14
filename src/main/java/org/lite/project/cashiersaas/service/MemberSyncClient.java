package org.lite.project.cashiersaas.service;

import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.component.MemberSyncRequestedEvent;
import org.lite.project.cashiersaas.configuration.AiServiceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pushes one staff member to Ai-services (contract: docs/contracts/member-sync.md section 2).
 * Disabled when {@code app.ai-service.base-url} is blank. Retries only I/O errors and 5xx responses. Never throws
 * and never logs the phone number, the API key or the request body.
 */
@Slf4j
@Service
public class MemberSyncClient {
    static final String KEY_HEADER = "X-Internal-Service-Key";

    private final AiServiceProperties properties;
    private final RestClient restClient;
    private final boolean enabled;

    @Autowired
    public MemberSyncClient(AiServiceProperties properties) {
        this(properties, properties.isEnabled() ? buildRestClient(properties) : null);
    }

    /** Test seam: a pre-built client (for example bound to a MockRestServiceServer). */
    public MemberSyncClient(AiServiceProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
        if (!properties.isEnabled() || restClient == null) {
            this.enabled = false;
            log.info("Member sync to Ai-services is disabled (app.ai-service.base-url is blank).");
        } else if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            this.enabled = false;
            log.warn("Member sync to Ai-services is disabled: app.ai-service.base-url is set but "
                    + "app.ai-service.api-key (AI_INTERNAL_API_KEY) is blank.");
        } else {
            this.enabled = true;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Upserts the member in Ai-services. Blocking; never throws. */
    public void sync(MemberSyncRequestedEvent member) {
        if (!enabled) {
            return;
        }
        try {
            push(member);
        } catch (RuntimeException exception) {
            log.warn("Member sync failed unexpectedly: tenantId={} userId={} error={}", member.getTenantId(),
                    member.getUserId(), exception.getClass().getSimpleName());
        }
    }

    private void push(MemberSyncRequestedEvent member) {
        URI uri = URI.create(trimTrailingSlash(properties.getBaseUrl().trim()) + "/api/v1/internal/tenants/"
                + member.getTenantId() + "/members/" + member.getUserId());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("tenant_name", member.getTenantName());
        body.put("full_name", member.getFullName());
        body.put("phone_number", member.getPhoneNumber());
        body.put("role", member.getRole().name());
        body.put("status", member.getStatus());
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            String failure;
            boolean retryable;
            try {
                ResponseEntity<Void> response = restClient.put().uri(uri)
                        .header(KEY_HEADER, properties.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.debug("Member synced: tenantId={} userId={} attempt={}", member.getTenantId(),
                            member.getUserId(), attempt);
                    return;
                }
                failure = "HTTP " + response.getStatusCode().value();
                retryable = false;
            } catch (HttpServerErrorException exception) {
                failure = "HTTP " + exception.getStatusCode().value();
                retryable = true;
            } catch (RestClientResponseException exception) {
                failure = "HTTP " + exception.getStatusCode().value();
                retryable = false;
            } catch (ResourceAccessException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                failure = "I/O error " + cause.getClass().getSimpleName();
                retryable = true;
            }
            if (!retryable || attempt >= maxAttempts) {
                log.warn("Member sync failed: tenantId={} userId={} attempts={} reason={}", member.getTenantId(),
                        member.getUserId(), attempt, failure);
                return;
            }
            log.debug("Member sync attempt failed, retrying: tenantId={} userId={} attempt={} reason={}",
                    member.getTenantId(), member.getUserId(), attempt, failure);
            if (!pauseBeforeRetry(attempt)) {
                log.warn("Member sync interrupted: tenantId={} userId={}", member.getTenantId(), member.getUserId());
                return;
            }
        }
    }

    private boolean pauseBeforeRetry(int failedAttempt) {
        List<Duration> backoff = properties.getRetryBackoff();
        if (backoff == null || backoff.isEmpty()) {
            return true;
        }
        Duration delay = backoff.get(Math.min(failedAttempt - 1, backoff.size() - 1));
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static RestClient buildRestClient(AiServiceProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
