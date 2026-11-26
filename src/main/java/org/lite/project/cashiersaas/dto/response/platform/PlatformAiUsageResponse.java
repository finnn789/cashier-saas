package org.lite.project.cashiersaas.dto.response.platform;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlatformAiUsageResponse {
    private int monthlyLimit;
    private int monthlyUsed;
    private int dailyUsed;
}
