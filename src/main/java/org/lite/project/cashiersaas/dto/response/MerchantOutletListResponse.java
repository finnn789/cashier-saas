package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class MerchantOutletListResponse {
    private List<MerchantOutletResponse> outlets;
    private int allowedOutlets;
    private int activeOutlets;
    private BigDecimal extraOutletPrice;
}
