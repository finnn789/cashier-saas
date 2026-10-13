package org.lite.project.cashiersaas.component;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class FnbOrderChangedListener {
    private final FnbRealtimeWebSocketHandler handler;
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void afterCommit(FnbOrderChangedEvent event) {
        try { handler.enqueue(event); } catch (RuntimeException ignored) { /* committed operation remains successful */ }
    }
}
