package org.lite.project.cashiersaas.dto.response.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.Role;

import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MerchantOutletChoiceResponse {
    private UUID tenantId;
    private String businessName;
    private UUID outletId;
    private String outletName;
    private Role role;
}
