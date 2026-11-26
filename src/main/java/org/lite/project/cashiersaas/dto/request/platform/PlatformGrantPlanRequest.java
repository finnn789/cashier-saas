package org.lite.project.cashiersaas.dto.request.platform;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.PlanCode;

/** PRO or PREMIUM only (checked in the service); a missing extraOutlets means 0. */
@Getter
@Setter
public class PlatformGrantPlanRequest extends PlatformReasonRequest {
    @NotNull(message = "Paket wajib dipilih.")
    private PlanCode planCode;
    @NotNull(message = "Jumlah bulan wajib diisi.")
    @Min(value = 1, message = "Jumlah bulan minimal 1.")
    @Max(value = 24, message = "Jumlah bulan maksimal 24.")
    private Integer months;
    @Min(value = 0, message = "Outlet tambahan minimal 0.")
    @Max(value = 20, message = "Outlet tambahan maksimal 20.")
    private Integer extraOutlets;
}
