package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class FnbCategoryRequest {
    @NotBlank @Size(max = 120)
    private String name;
    private int displayOrder;
}
