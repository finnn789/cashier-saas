package org.lite.project.cashiersaas.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.FnbGatewayOutcome;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbPaymentResponse {
    private UUID orderId; private PaymentMethod method; private BigDecimal amountPaid; private BigDecimal amountRequested; private BigDecimal changeAmount;
    private String receiptNumber; private Instant paidAt; private String provider; private UUID attemptId;
    private FnbGatewayOutcome gatewayOutcome; private String providerReference;
    private List<String> stockWarnings = new ArrayList<>();
}
