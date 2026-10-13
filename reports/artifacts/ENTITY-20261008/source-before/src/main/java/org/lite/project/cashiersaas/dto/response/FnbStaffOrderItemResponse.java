package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbStaffOrderItemResponse {
    private UUID itemId;
    private String productName;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal lineTotal;
    private String note;
    private List<FnbStaffOrderOptionResponse> options;
}
