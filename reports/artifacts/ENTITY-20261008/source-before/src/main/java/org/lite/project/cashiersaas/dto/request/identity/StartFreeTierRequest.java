package org.lite.project.cashiersaas.dto.request.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StartFreeTierRequest {
    @NotBlank(message = "Tipe usaha wajib dipilih.")
    @Size(max = 40, message = "Kode tipe usaha maksimal 40 karakter.")
    private String businessTypeCode;
}
