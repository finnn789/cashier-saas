package org.lite.project.cashiersaas.dto.response.platform;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.PlanCode;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class PlatformTenantItemResponse {
    private UUID id;
    private String name;
    private String ownerName;
    private String ownerEmail;
    private String phoneNumber;
    private String status;
    private PlanCode effectivePlan;
    /** End of the running paid period or trial; null on FREETIER or an open-ended row. */
    private Instant activeUntil;
    private boolean trialActive;
    private int outletsActive;
    private int outletsAllowed;
    private Instant createdAt;
}
