package org.lite.project.cashiersaas.unit.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.component.FnbOrderChangedEvent;
import org.lite.project.cashiersaas.dto.FnbRealtimeMapper;
import org.lite.project.cashiersaas.dto.response.FnbRealtimeEventResponse;
import org.lite.project.cashiersaas.dto.response.FnbRealtimeTicketResponse;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class FnbRealtimeMapperTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void mapsSnapshotExactlyAndSerializesOnlyPublicResyncMetadata() {
        FnbOrderChangedEvent event = event(OrderStatus.READY, PaymentStatus.PAID);
        FnbRealtimeEventResponse response = FnbRealtimeMapper.event(event);
        assertEquals(event.getEventId(), response.getEventId());
        assertEquals(event.getType(), response.getType());
        assertEquals(event.getOrderId(), response.getOrderId());
        assertEquals(event.getOutletId(), response.getOutletId());
        assertEquals(event.getOrderStatus(), response.getOrderStatus());
        assertEquals(event.getPaymentStatus(), response.getPaymentStatus());
        assertEquals(event.getOccurredAt(), response.getOccurredAt());
        assertTrue(response.isResyncRequired());
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals(Set.of("eventId", "type", "orderId", "outletId", "orderStatus", "paymentStatus", "occurredAt", "resyncRequired"),
                Set.copyOf(json.propertyNames()));
        for (String sensitive : new String[] {"tenantId", "customerLabel", "items", "subtotal", "taxAmount", "total", "token", "sessionId"}) {
            assertFalse(json.has(sensitive), sensitive + " must not be disclosed");
        }
    }

    @Test
    void nullStatesAreAbsentFromActualJackson3Json() {
        FnbRealtimeEventResponse response = FnbRealtimeMapper.event(event(null, null));
        assertNull(response.getOrderStatus());
        assertNull(response.getPaymentStatus());
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));
        assertFalse(json.has("orderStatus"));
        assertFalse(json.has("paymentStatus"));
        assertEquals(Set.of("eventId", "type", "orderId", "outletId", "occurredAt", "resyncRequired"),
                Set.copyOf(json.propertyNames()));
        assertTrue(json.get("resyncRequired").asBoolean());
    }

    @Test
    void nullTicketPropertiesAreAbsentFromSerializedResponse() {
        FnbRealtimeTicketResponse response = new FnbRealtimeTicketResponse();
        response.setProtocol("cashier-fnb-v1");
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals(Set.of("protocol"), Set.copyOf(json.propertyNames()));
    }

    private FnbOrderChangedEvent event(OrderStatus status, PaymentStatus payment) {
        return new FnbOrderChangedEvent("ORDER_CHANGED", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                status, payment, Instant.parse("2030-01-01T00:00:00Z"));
    }
}
