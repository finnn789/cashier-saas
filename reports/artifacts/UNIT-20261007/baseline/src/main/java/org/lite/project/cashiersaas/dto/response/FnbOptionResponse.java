package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Getter @Setter
public class FnbOptionResponse {
    private UUID id;
    private UUID optionGroupId;
    private String name;
    private BigDecimal priceAdjustment;
    private boolean available;
    private int displayOrder;
}
