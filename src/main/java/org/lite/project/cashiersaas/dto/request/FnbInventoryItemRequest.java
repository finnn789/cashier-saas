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
public class FnbInventoryItemRequest {
    @NotBlank
    @Size(max = 140)
    private String name;
    @Size(max = 80)
    private String sku;
    @NotBlank
    @Size(max = 30)
    private String unit;
    @NotNull
    @DecimalMin("0")
    @Digits(integer = 11, fraction = 3)
    private BigDecimal reorderLevel;
    @DecimalMin("0")
    @Digits(integer = 11, fraction = 3)
    private BigDecimal initialQuantity;
}
