package org.lite.project.cashiersaas.dto.request.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlatformMarkPaidRequest extends PlatformReasonRequest {
    @NotBlank(message = "Referensi transfer wajib diisi.")
    @Size(max = 160, message = "Referensi transfer maksimal 160 karakter.")
    private String transferReference;
}
