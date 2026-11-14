package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class FnbShiftOpeningSuggestionResponse {
    private BigDecimal openingCash;
    private UUID sourceShiftId;
}
