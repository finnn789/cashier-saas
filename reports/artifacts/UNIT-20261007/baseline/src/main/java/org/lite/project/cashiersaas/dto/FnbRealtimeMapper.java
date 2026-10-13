package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.component.FnbOrderChangedEvent;
import org.lite.project.cashiersaas.dto.response.FnbRealtimeEventResponse;

public final class FnbRealtimeMapper {
    private FnbRealtimeMapper() { }
    public static FnbRealtimeEventResponse event(FnbOrderChangedEvent event) {
        FnbRealtimeEventResponse response = new FnbRealtimeEventResponse();
        response.setEventId(event.getEventId()); response.setType(event.getType()); response.setOrderId(event.getOrderId());
        response.setOutletId(event.getOutletId()); response.setOrderStatus(event.getOrderStatus());
        response.setPaymentStatus(event.getPaymentStatus()); response.setOccurredAt(event.getOccurredAt());
        response.setResyncRequired(true); return response;
    }
}
