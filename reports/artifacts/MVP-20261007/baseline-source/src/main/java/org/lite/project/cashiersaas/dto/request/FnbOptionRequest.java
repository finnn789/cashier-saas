package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter
public class FnbOptionRequest {
    @NotBlank @Size(max = 120) private String name;
    @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) private BigDecimal priceAdjustment;
    private boolean available = true;
    private int displayOrder;
    @Size(max = 255) private String priceChangeReason;
}
