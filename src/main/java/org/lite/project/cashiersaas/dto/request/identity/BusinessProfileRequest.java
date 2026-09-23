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

    @NotBlank(message = "Nama outlet wajib diisi.")
    @Size(max = 120, message = "Nama outlet maksimal 120 karakter.")
    private String outletName;

    @Size(max = 255, message = "Alamat maksimal 255 karakter.")
    private String address;

    @Size(max = 32, message = "Nomor telepon maksimal 32 karakter.")
    private String phoneNumber;

    @NotBlank(message = "Zona waktu outlet wajib diisi.")
    @Size(max = 64, message = "Zona waktu maksimal 64 karakter.")
    private String timeZone;
}
