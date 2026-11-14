package org.lite.project.cashiersaas.dto.response.identity;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class MerchantStaffPasswordResetResponse {
    private UUID userId;
    /** Shown once; only the BCrypt hash is stored. */
    private String temporaryPassword;
}
