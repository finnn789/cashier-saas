package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.PaymentMethod;

import java.math.BigDecimal;

@Getter
@Setter
public class FnbShiftMethodTotalResponse {
    private PaymentMethod method;
    private String methodLabel;
    private long count;
    private BigDecimal amount;
}
