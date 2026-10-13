package org.lite.project.cashiersaas.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import java.util.UUID;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbCancellationResponse { private UUID orderId; private String orderNumber; private OrderStatus status; private PaymentStatus paymentStatus; private String reason; }
