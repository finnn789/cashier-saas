package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MerchantBusinessSettingsResponse {
    private UUID outletId;
    private String businessName;
    private String logoUrl;
    private String currency;
    private String address;
    private String phoneNumber;
    private BigDecimal taxRate;
    private BigDecimal serviceChargeRate;
    private String outletName;
    private String outletAddress;
    private String outletPhoneNumber;
    private String timeZone;
}
