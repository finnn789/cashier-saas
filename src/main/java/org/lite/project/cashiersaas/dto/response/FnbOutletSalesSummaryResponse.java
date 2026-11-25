package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class FnbOutletSalesSummaryResponse {
    private UUID outletId;
    private String outletName;
    private String timeZone;
    private FnbSalesSummaryResponse summary;
}
