package org.lite.project.cashiersaas.dto.request.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "Email wajib diisi.")
    @Email(message = "Format email tidak valid.")
    private String email;

    @NotBlank(message = "Kata sandi wajib diisi.")
    private String password;
}
