package org.lite.project.cashiersaas.dto.request.identity;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.OnboardingIntent;

@Getter
@Setter
public class OnboardingIntentRequest {
    @NotNull(message = "Jalur pendaftaran wajib dipilih.")
    private OnboardingIntent intent;
}
