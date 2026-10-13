package org.lite.project.cashiersaas.dto.response.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.OnboardingIntent;

import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnboardingStatusResponse {
    private OnboardingIntent intent;
    private String accountStatus;
    private UUID pendingTenantId;
    private String nextStep;
}
