package org.lite.project.cashiersaas.component;

import lombok.Getter;
import org.lite.project.cashiersaas.enums.Role;

import java.util.UUID;

/**
 * Immutable snapshot of a staff member that must be pushed to Ai-services after the owning transaction commits.
 * It carries a phone number, so {@link #toString()} deliberately exposes identifiers only.
 */
@Getter
public final class MemberSyncRequestedEvent {
    private final UUID tenantId;
    private final String tenantName;
    private final UUID userId;
    private final String fullName;
    private final String phoneNumber;
    private final Role role;
    /** {@code ACTIVE} or {@code DISABLED}, see docs/contracts/member-sync.md. */
    private final String status;

    public MemberSyncRequestedEvent(UUID tenantId, String tenantName, UUID userId, String fullName,
                                    String phoneNumber, Role role, String status) {
        this.tenantId = tenantId;
        this.tenantName = tenantName;
        this.userId = userId;
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.role = role;
        this.status = status;
    }

    @Override
    public String toString() {
        return "MemberSyncRequestedEvent[tenantId=" + tenantId + ", userId=" + userId + ", role=" + role + ", status=" + status + "]";
    }
}
