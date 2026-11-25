package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class FnbPurchaseRequest {
    @NotBlank
    @Size(max = 128)
    private String idempotencyKey;
    @Size(max = 120)
    private String supplierName;
    @NotNull
    private LocalDate purchasedOn;
    @Size(max = 500)
    private String note;
    @NotEmpty
    @Size(max = 50)
    @Valid
    private List<FnbPurchaseLineRequest> lines;
}
