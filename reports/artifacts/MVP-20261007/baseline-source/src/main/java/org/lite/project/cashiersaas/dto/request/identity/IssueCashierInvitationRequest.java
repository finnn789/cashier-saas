package org.lite.project.cashiersaas.dto.request.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssueCashierInvitationRequest {
    @NotBlank(message = "Email kasir wajib diisi.")
    @Email(message = "Format email tidak valid.")
    @Size(max = 320, message = "Email maksimal 320 karakter.")
    private String email;
}
