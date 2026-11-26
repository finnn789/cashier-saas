package org.lite.project.cashiersaas.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Local-disk image storage settings (CASHIER_MEDIA_* variables). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.media")
public class MediaProperties {
    private String dir;
    /** Absolute base URL without trailing slash; stored URLs are {publicBaseUrl}/media/{tenantId}/{file}. */
    private String publicBaseUrl;
    private long maxTenantBytes;
    private Duration orphanGrace;
}
