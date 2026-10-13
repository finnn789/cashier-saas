package org.lite.project.cashiersaas.dto.request.identity;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClaimCashierInvitationRequest {
    @NotBlank(message = "Kode undangan wajib diisi.")
    private String code;
}
