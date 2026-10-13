package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class FnbTableRequest {
    @NotBlank @Size(max = 80) private String name;
    private boolean active = true;
}
