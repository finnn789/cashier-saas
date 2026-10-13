package org.lite.project.cashiersaas.dto.response.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CashierInvitationResponse {
    private UUID tenantId;
    private UUID outletId;
    private String email;
    private String code;
    private Instant expiresAt;
}
