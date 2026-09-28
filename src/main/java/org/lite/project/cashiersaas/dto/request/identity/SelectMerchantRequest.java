package org.lite.project.cashiersaas.dto.request.identity;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class SelectMerchantRequest {
    @NotNull(message = "Merchant wajib dipilih.")
    private UUID tenantId;

    @NotNull(message = "Outlet wajib dipilih.")
    private UUID outletId;
}
