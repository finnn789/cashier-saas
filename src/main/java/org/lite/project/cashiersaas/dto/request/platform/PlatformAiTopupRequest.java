package org.lite.project.cashiersaas.dto.request.platform;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlatformAiTopupRequest extends PlatformReasonRequest {
    @NotNull(message = "Jumlah pertanyaan wajib diisi.")
    @Min(value = 1, message = "Jumlah pertanyaan minimal 1.")
    @Max(value = 1000, message = "Jumlah pertanyaan maksimal 1000.")
    private Integer questions;
}
