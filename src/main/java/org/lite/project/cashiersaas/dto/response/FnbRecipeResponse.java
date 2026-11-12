package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter @Setter
public class FnbRecipeResponse {
    private List<Line> lines;

    @Getter @Setter
    public static class Line {
        private UUID inventoryItemId;
        private String inventoryItemName;
        private String unit;
        private BigDecimal quantityPerUnit;
    }
}
