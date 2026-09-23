package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CashierCatalogItemResponse {
    private UUID id;
    private String name;
    private String billingUnit;
    private BigDecimal unitPrice;
    private boolean active;
}
