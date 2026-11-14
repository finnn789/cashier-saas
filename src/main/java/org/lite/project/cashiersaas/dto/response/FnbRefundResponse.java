package org.lite.project.cashiersaas.dto.response;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
@Getter @Setter
public class FnbRefundResponse {
    private UUID orderId; private String orderNumber; private PaymentStatus paymentStatus; private BigDecimal amount;
    private PaymentMethod method; private String reason; private Instant refundedAt; private String refundedBy;
    private List<String> stockWarnings = new ArrayList<>();
}
