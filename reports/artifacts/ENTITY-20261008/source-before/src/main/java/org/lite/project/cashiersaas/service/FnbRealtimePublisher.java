package org.lite.project.cashiersaas.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.FnbOrderChangedEvent;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FnbRealtimePublisher {
    private final ApplicationEventPublisher events;
    public void orderChanged(String type, SalesOrderEntity order) {
        events.publishEvent(new FnbOrderChangedEvent(type, order.getTenant().getId(), order.getOutlet().getId(), order.getId(),
                order.getStatus(), order.getPaymentStatus(), Instant.now()));
    }
}
