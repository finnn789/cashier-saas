package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class FnbStockCountRequest {
    @NotBlank
    @Size(max = 128)
    private String idempotencyKey;
    @Size(max = 500)
    private String note;
    @NotEmpty
    @Size(max = 200)
    @Valid
    private List<FnbStockCountLineRequest> lines;
}
