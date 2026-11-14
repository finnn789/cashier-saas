package org.lite.project.cashiersaas.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbPaymentResponse {
    private UUID orderId; private PaymentMethod method; private BigDecimal amountPaid;; private BigDecimal changeAmount;
    private String methodLabel; private String receiptNumber; private Instant paidAt;
    private List<String> stockWarnings = new ArrayList<>();
}
