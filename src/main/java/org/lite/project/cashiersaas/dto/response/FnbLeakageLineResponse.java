package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class FnbLeakageLineResponse {
    private UUID inventoryItemId;
    private String name;
    private String unit;
    /** Null when the item had no earlier count: the period starts at its first movement. */
    private Instant periodStart;
    private Instant periodEnd;
    private BigDecimal theoreticalUsage;
    private BigDecimal waste;
    private BigDecimal restock;
    private BigDecimal manualAdjustment;
    private BigDecimal previousQuantity;
    private BigDecimal countedQuantity;
    private BigDecimal shortage;
    private BigDecimal variancePct;
    private BigDecimal unitCost;
    private BigDecimal lossAmount;
    private boolean flagged;
}
