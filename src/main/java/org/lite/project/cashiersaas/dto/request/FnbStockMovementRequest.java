package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;

import java.math.BigDecimal;

@Getter
@Setter
public class FnbStockMovementRequest {
    @NotNull
    private InventoryMovementEntity.Type type;
    @NotNull
    @Digits(integer = 11, fraction = 3)
    private BigDecimal quantity;
    @Size(max = 1000)
    private String reason;
    @NotBlank
    @Size(max = 128)
    private String idempotencyKey;
}
