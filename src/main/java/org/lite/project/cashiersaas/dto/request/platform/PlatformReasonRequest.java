package org.lite.project.cashiersaas.dto.request.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Every platform write carries the operator's reason; it goes to the audit log. */
@Getter
@Setter
public class PlatformReasonRequest {
    @NotBlank(message = "Alasan wajib diisi.")
    @Size(min = 3, max = 500, message = "Alasan harus 3 sampai 500 karakter.")
    private String reason;
}
