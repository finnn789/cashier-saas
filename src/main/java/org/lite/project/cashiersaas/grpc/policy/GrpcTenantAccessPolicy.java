package org.lite.project.cashiersaas.grpc.policy;

import io.grpc.Status;
import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.repository.TenantRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Server-side tenant authorization and input limits for gRPC calls. A tenant is allowed only when it exists
 * in the database, is active and has F&B enabled; a missing tenant and an inactive tenant are rejected with the
 * identical status and message so the caller cannot learn which tenants exist (fail closed).
 */
@Component
public class GrpcTenantAccessPolicy {

    static final String TENANT_NOT_PERMITTED_MESSAGE = "Tenant is not permitted for this service";

    private final TenantRepository tenantRepository;
    private final MembershipOutletAccessRepository outletAccessRepository;
    private final TenantMembershipRepository membershipRepository;
    private final int defaultLimit;
    private final int maxLimit;

    public GrpcTenantAccessPolicy(TenantRepository tenantRepository, MembershipOutletAccessRepository outletAccessRepository,
                                  TenantMembershipRepository membershipRepository, GrpcSecurityProperties properties) {
        this.tenantRepository = tenantRepository;
        this.outletAccessRepository = outletAccessRepository;
        this.membershipRepository = membershipRepository;
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
        if (!tenantRepository.existsActiveFnbTenant(tenantId)) {
            throw Status.PERMISSION_DENIED.withDescription(TENANT_NOT_PERMITTED_MESSAGE).asRuntimeException();
        }
        return tenantId;
    }

    /**
     * Outlets the calling staff user may read inside an already validated tenant. Blank or malformed ids are
     * INVALID_ARGUMENT; an unknown user, an inactive user or membership and a user without outlet access are all
     * rejected with the same PERMISSION_DENIED as a rejected tenant (fail closed, no existence evidence).
     */
    public List<UUID> requireMemberOutlets(UUID tenantId, String rawUserId) {
        return requireOutlets(tenantId, parseUserId(rawUserId), false);
    }

    /** Same checks as {@link #requireMemberOutlets}, and the active membership must be OWNERMERCHANT (identical PERMISSION_DENIED otherwise). */
    public List<UUID> requireOwnerOutlets(UUID tenantId, String rawUserId) {
        return requireOutlets(tenantId, parseUserId(rawUserId), true);
    }

    private static UUID parseUserId(String rawUserId) {
        if (rawUserId == null || rawUserId.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription("user_id must not be blank").asRuntimeException();
        }
        try {
            return UUID.fromString(rawUserId.trim());
        } catch (IllegalArgumentException ex) {
            throw Status.INVALID_ARGUMENT.withDescription("user_id must be a valid UUID").asRuntimeException();
        }
    }

    private List<UUID> requireOutlets(UUID tenantId, UUID userId, boolean ownerOnly) {
        List<UUID> outletIds = outletAccessRepository.findActiveOutletIds(tenantId, userId);
        if (outletIds.isEmpty()
                || (ownerOnly && membershipRepository.findActive(userId, tenantId)
                        .filter(membership -> membership.getRole() == Role.OWNERMERCHANT).isEmpty())) {
            throw Status.PERMISSION_DENIED.withDescription(TENANT_NOT_PERMITTED_MESSAGE).asRuntimeException();
        }
        return outletIds;
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
}
