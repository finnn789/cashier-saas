package org.lite.project.cashiersaas.configuration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Security settings for the internal gRPC server. The service key is supplied only through the
 * environment (CASHIER_GRPC_SERVICE_KEY); an empty tenant allowlist denies every tenant.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.grpc")
public class GrpcSecurityProperties {

    @NotBlank
    @Size(min = 16)
    private String serviceKey;

    private List<String> allowedTenantIds = new ArrayList<>();

    @Min(1)
    private int maxCatalogLimit = 50;

    @Min(1)
    private int defaultCatalogLimit = 10;

    @Min(1)
    private int requestsPerSecond = 50;
}
