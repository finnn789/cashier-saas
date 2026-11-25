package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class OutletOverAllowanceResponse {
    public static final String REASON = "OUTLET_OVER_ALLOWANCE";

    private String reason = REASON;
    private List<UUID> usableOutletIds;
}
