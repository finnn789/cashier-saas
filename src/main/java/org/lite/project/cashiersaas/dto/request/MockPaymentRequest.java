package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MockPaymentRequest {
    @NotBlank
    @Pattern(regexp = "SUCCEEDED|FAILED")
    private String outcome;
}
