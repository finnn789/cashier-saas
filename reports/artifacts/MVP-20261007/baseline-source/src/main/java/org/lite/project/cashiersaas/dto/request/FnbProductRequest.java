package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Getter @Setter
public class FnbProductRequest {
    @NotNull private UUID categoryId;
    @NotBlank @Size(max = 160) private String name;
    @Size(max = 1000) private String description;
    @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) private BigDecimal price;
    @Size(max = 512) private String imageUrl;
    private boolean available = true;
    private int displayOrder;
    @Size(max = 255) private String priceChangeReason;
}
