package org.lite.project.cashiersaas.unit.component;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.component.FnbOrderChangedEvent;
import org.lite.project.cashiersaas.component.FnbOrderChangedListener;
import org.lite.project.cashiersaas.component.FnbRealtimeWebSocketHandler;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class FnbOrderChangedListenerTest {
    private final FnbRealtimeWebSocketHandler handler = mock(FnbRealtimeWebSocketHandler.class);
    private final ResourceFreeTransactionManager manager = new ResourceFreeTransactionManager();
    private final TransactionTemplate transaction = new TransactionTemplate(manager);
    private GenericApplicationContext context;

    @BeforeEach
    void registerRealSpringTransactionalListener() {
        context = new GenericApplicationContext();
        context.registerBean("transactionalFactory", TransactionalEventListenerFactory.class);
        context.registerBean("eventListenerProcessor", EventListenerMethodProcessor.class);
        context.registerBean("orderChangedListener", FnbOrderChangedListener.class,
                () -> new FnbOrderChangedListener(handler));
        context.refresh();
        assertTrue(context.getApplicationListeners().stream()
                .anyMatch(listener -> listener instanceof TransactionalApplicationListenerMethodAdapter),
                "Spring must register the production annotated method through its actual adapter");
    }

    @AfterEach
    void closeContextAndCheckThreadState() {
        context.close();
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void eventIsNotDeliveredInsideTransactionAndIsDeliveredOnceAfterActualCommit() {
        FnbOrderChangedEvent event = event();
        transaction.executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            context.publishEvent(event);
            verifyNoInteractions(handler);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            assertEquals(0, manager.commits);
        });
        assertEquals(1, manager.commits);
        assertEquals(0, manager.rollbacks);
        verify(handler).enqueue(event);
        verifyNoMoreInteractions(handler);
    }

    @Test
    void rollbackOnlySkipsProductionListenerDelivery() {
        transaction.executeWithoutResult(status -> {
            context.publishEvent(event());
            verifyNoInteractions(handler);
            status.setRollbackOnly();
        });
        assertEquals(0, manager.commits);
        assertEquals(1, manager.rollbacks);
        verifyNoInteractions(handler);
    }

    @Test
    void exceptionRollbackSkipsDeliveryAndRetainsOriginalFailure() {
        IllegalStateException failure = new IllegalStateException("business rollback");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                transaction.executeWithoutResult(status -> {
                    context.publishEvent(event());
                    verifyNoInteractions(handler);
                    throw failure;
                })));
        assertEquals(1, manager.rollbacks);
        verifyNoInteractions(handler);
    }

    @Test
    void publishingWithoutActiveTransactionIsSkipped() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        context.publishEvent(event());
        verifyNoInteractions(handler);
        assertEquals(0, manager.commits);
    }

    @Test
    void postCommitDeliveryFailureCannotUndoCommittedOperation() {
        FnbOrderChangedEvent event = event();
        doThrow(new IllegalStateException("delivery failure")).when(handler).enqueue(event);
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> context.publishEvent(event)));
        assertEquals(1, manager.commits);
        assertEquals(0, manager.rollbacks);
        verify(handler).enqueue(event);
    }

    @Test
    void listenerContainsDirectEnqueueFailureSeparatelyFromTransactionWiring() {
        FnbOrderChangedEvent event = event();
        doThrow(new IllegalStateException("queue failure")).when(handler).enqueue(event);
        assertDoesNotThrow(() -> new FnbOrderChangedListener(handler).afterCommit(event));
        verify(handler).enqueue(event);
    }

    private FnbOrderChangedEvent event() {
        return new FnbOrderChangedEvent("ORDER_CHANGED", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OrderStatus.CONFIRMED, PaymentStatus.PAID, Instant.parse("2030-01-01T00:00:00Z"));
    }

    // No resource or custom synchronization: Spring manages all transaction callbacks.
    private static final class ResourceFreeTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
