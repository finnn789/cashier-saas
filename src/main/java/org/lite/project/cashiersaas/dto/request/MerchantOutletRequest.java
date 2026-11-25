package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MerchantOutletRequest {
    @NotBlank @Size(max = 120) private String name;
    @NotBlank @Size(max = 64) private String timeZone;
    @Size(max = 255) private String address;
    @Size(max = 32) private String phoneNumber;
}
