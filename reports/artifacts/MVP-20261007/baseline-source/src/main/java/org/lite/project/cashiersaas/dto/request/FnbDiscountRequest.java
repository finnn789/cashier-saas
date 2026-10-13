package org.lite.project.cashiersaas.dto.request;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.DiscountType;
import java.math.BigDecimal;
@Getter @Setter
public class FnbDiscountRequest {
    @NotNull private DiscountType type;
    @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 17, fraction = 2) private BigDecimal value;
    @NotBlank @Size(max = 255) private String reason;
}
