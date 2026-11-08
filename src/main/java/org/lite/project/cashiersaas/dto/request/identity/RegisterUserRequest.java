package org.lite.project.cashiersaas.dto.request.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterUserRequest {

    @NotBlank(message = "Nama lengkap wajib diisi.")
    @Size(max = 160, message = "Nama lengkap maksimal 160 karakter.")
    private String fullName;

    @NotBlank(message = "Email wajib diisi.")
    @Email(message = "Format email tidak valid.")
    @Size(max = 254, message = "Email maksimal 254 karakter.")
    private String email;

    @NotBlank(message = "Nomor telepon wajib diisi.")
    @Size(max = 32, message = "Nomor telepon maksimal 32 karakter.")
    private String phoneNumber;

    @NotBlank(message = "Kata sandi wajib diisi.")
    @Size(min = 8, max = 72, message = "Kata sandi harus 8 sampai 72 karakter.")
    private String password;
}
