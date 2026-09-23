package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CashierPriceChangeRequest {
    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal unitPrice;
    @NotBlank
    @Size(max = 1000)
    private String reason;
}
