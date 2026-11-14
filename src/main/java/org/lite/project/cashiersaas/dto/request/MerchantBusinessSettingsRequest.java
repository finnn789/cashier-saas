package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class MerchantBusinessSettingsRequest {
    @NotBlank @Size(max = 120) private String businessName;
    @Size(max = 512) private String logoUrl;
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") private String currency;
    @Size(max = 255) private String address;
    @Size(max = 32) private String phoneNumber;
    @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) private BigDecimal taxRate;
    @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) private BigDecimal serviceChargeRate;
    @NotBlank @Size(max = 120) private String outletName;
    @Size(max = 255) private String outletAddress;
    @Size(max = 32) private String outletPhoneNumber;
    @NotBlank @Size(max = 64) private String timeZone;
    @Size(max = 200) private String receiptFooter;
}
