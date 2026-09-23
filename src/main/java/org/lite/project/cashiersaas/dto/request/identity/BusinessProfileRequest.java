package org.lite.project.cashiersaas.dto.request.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BusinessProfileRequest {

    @NotBlank(message = "Nama bisnis wajib diisi.")
    @Size(max = 120, message = "Nama bisnis maksimal 120 karakter.")
    private String businessName;
}
