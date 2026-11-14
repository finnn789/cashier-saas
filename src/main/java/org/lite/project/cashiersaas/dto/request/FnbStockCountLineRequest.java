package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class FnbStockCountLineRequest {
    @NotNull
    private UUID inventoryItemId;
    @NotNull
    @DecimalMin("0")
    @Digits(integer = 11, fraction = 3)
    private BigDecimal countedQuantity;
}
