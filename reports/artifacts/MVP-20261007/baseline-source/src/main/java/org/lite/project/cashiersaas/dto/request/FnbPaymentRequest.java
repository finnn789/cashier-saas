package org.lite.project.cashiersaas.dto.request;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import java.math.BigDecimal;
@Getter @Setter
public class FnbPaymentRequest {
    @NotNull private PaymentMethod method;
    @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) private BigDecimal amountTendered;
    @NotBlank @Size(max = 128) private String idempotencyKey;
}
