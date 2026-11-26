package org.lite.project.cashiersaas.dto.response.platform;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.PlanCode;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class PlatformSubscriptionResponse {
    private UUID id;
    private PlanCode planCode;
    private String status;
    private Instant startsAt;
    private Instant endsAt;
    private int extraOutlets;
}
