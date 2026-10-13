package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbRealtimeEventResponse {
    private UUID eventId;
    private String type;
    private UUID orderId;
    private UUID outletId;
    private OrderStatus orderStatus;
    private PaymentStatus paymentStatus;
    private Instant occurredAt;
    private boolean resyncRequired;
}
