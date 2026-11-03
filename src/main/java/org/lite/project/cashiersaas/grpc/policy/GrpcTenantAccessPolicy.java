package org.lite.project.cashiersaas.grpc.policy;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side tenant allowlist and input limits for gRPC calls. An empty allowlist denies every
 * tenant (fail closed).
 */
@Component
public class GrpcTenantAccessPolicy {

    private final Set<UUID> allowedTenants;
    private final int defaultLimit;
    private final int maxLimit;

    public GrpcTenantAccessPolicy(GrpcSecurityProperties properties) {
        this.allowedTenants = parseAllowedTenants(properties.getAllowedTenantIds());
        this.defaultLimit = properties.getDefaultCatalogLimit();
        this.maxLimit = properties.getMaxCatalogLimit();
    }

    public UUID requireAllowedTenant(String raw) {
        if (raw == null || raw.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription("tenant_id must not be blank").asRuntimeException();
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw Status.INVALID_ARGUMENT.withDescription("tenant_id must be a valid UUID").asRuntimeException();
        }
        if (!allowedTenants.contains(tenantId)) {
            throw Status.PERMISSION_DENIED.withDescription("Tenant is not permitted for this service")
                    .asRuntimeException();
        }
        return tenantId;
    }

    public int clampLimit(int requested) {
        if (requested <= 0) {
            return Math.min(defaultLimit, maxLimit);
        }
        return Math.min(requested, maxLimit);
    }

    public static String escapeLikePattern(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' || current == '%' || current == '_') {
                escaped.append('\\');
            }
            escaped.append(current);
        }
        return escaped.toString();
    }

    /** Short, non-reversible-in-logs reference for a tenant id (first 8 characters). */
    public static String shortTenantRef(String raw) {
        if (raw == null || raw.isBlank()) {
            return "none";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= 8 ? trimmed : trimmed.substring(0, 8);
    }

    private static Set<UUID> parseAllowedTenants(List<String> configured) {
        Set<UUID> parsed = new HashSet<>();
        if (configured == null) {
            return Set.copyOf(parsed);
        }
        for (String entry : configured) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                parsed.add(UUID.fromString(entry.trim()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("app.grpc.allowed-tenant-ids contains an invalid UUID.", ex);
            }
        }
        return Set.copyOf(parsed);
    }
}
