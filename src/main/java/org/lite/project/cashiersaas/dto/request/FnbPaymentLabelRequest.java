package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FnbPaymentLabelRequest {
    @NotBlank
    @Size(max = 40)
    private String name;
    @Min(0)
    @Max(9999)
    private Integer displayOrder;
    private Boolean active;
}
