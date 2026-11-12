package org.lite.project.cashiersaas.component;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;

@Getter
public final class FnbOrderChangedEvent {
    private final UUID eventId;
    private final String type;
    private final UUID tenantId;
    private final UUID outletId;
    private final UUID orderId;
    private final OrderStatus orderStatus;
    private final PaymentStatus paymentStatus;
    private final Instant occurredAt;

    public FnbOrderChangedEvent(String type, UUID tenantId, UUID outletId, UUID orderId,
                                OrderStatus orderStatus, PaymentStatus paymentStatus, Instant occurredAt) {
        this.eventId = UUID.randomUUID();
        this.type = type;
        this.tenantId = tenantId;
        this.outletId = outletId;
        this.orderId = orderId;
        this.orderStatus = orderStatus;
        this.paymentStatus = paymentStatus;
        this.occurredAt = occurredAt;
    }
}
