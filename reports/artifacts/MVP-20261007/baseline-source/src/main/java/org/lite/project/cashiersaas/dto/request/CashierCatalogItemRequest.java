package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CashierCatalogItemRequest {
    @NotBlank
    @Size(max = 140)
    private String name;
    @NotBlank
    @Pattern(regexp = "KG|PIECE")
    private String billingUnit;
    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal unitPrice;
}
