package org.lite.project.cashiersaas.dto.request.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {

    @NotBlank(message = "Kata sandi saat ini wajib diisi.")
    private String currentPassword;

    @NotBlank(message = "Kata sandi wajib diisi.")
    @Size(min = 8, max = 72, message = "Kata sandi harus 8 sampai 72 karakter.")
    private String newPassword;
}
