package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter @Setter
public class FnbRecipeRequest {
    @Valid
    @NotNull
    @Size(max = 50)
    private List<Line> lines;

    @Getter @Setter
    public static class Line {
        @NotNull
        private UUID inventoryItemId;
        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 11, fraction = 3)
        private BigDecimal quantityPerUnit;
    }
}
