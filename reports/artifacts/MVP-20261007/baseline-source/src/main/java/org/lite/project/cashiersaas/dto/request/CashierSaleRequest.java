package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class CashierSaleRequest {
    @NotBlank
    @Size(max = 160)
    private String customerName;
    @NotEmpty
    @Valid
    private List<CashierSaleLineRequest> items;
    @NotBlank
    @Pattern(regexp = "CASH|NON_CASH")
    private String paymentMethod;
    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal tenderedAmount;
    @Size(max = 120)
    private String paymentReference;
}
