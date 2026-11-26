package org.lite.project.cashiersaas.dto.response.platform;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class PlatformAuditItemResponse {
    private UUID id;
    private UUID tenantId;
    private String tenantName;
    private String actorName;
    private String action;
    private String entityType;
    private UUID entityId;
    private String reason;
    private String details;
    private Instant createdAt;
}
