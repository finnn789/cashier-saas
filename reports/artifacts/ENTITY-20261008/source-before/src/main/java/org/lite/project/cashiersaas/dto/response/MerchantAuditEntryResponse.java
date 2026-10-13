package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MerchantAuditEntryResponse {
    private UUID id;
    private Instant createdAt;
    private OffsetDateTime localCreatedAt;
    private String action;
    private String entityType;
    private UUID entityId;
    private UUID actorId;
    private String actorDisplayName;
    private String reason;
    private String details;
}
