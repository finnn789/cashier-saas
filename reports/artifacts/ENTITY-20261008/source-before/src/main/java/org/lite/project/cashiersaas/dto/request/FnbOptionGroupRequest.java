package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class FnbOptionGroupRequest {
    @NotBlank @Size(max = 120) private String name;
    private boolean required;
    @Min(0) @Max(20) private int minimumSelections;
    @Min(1) @Max(20) private int maximumSelections = 1;
    private int displayOrder;
}
