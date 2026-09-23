package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateCashierRequest {
    @NotBlank
    @Email
    @Size(max = 320)
    private String email;
    @NotBlank
    @Size(min = 8, max = 72)
    private String password;
    @NotBlank
    @Size(max = 160)
    private String fullName;
}
