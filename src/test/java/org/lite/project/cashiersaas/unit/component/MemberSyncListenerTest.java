package org.lite.project.cashiersaas.unit.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.component.MemberSyncListener;
import org.lite.project.cashiersaas.component.MemberSyncRequestedEvent;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.service.MemberSyncClient;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class MemberSyncListenerTest {
    private final MemberSyncClient client = mock(MemberSyncClient.class);
    private final CommitCountingTransactionManager manager = new CommitCountingTransactionManager();
    private final TransactionTemplate transaction = new TransactionTemplate(manager);
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor queueingExecutor = queued::add;
    private GenericApplicationContext context;

    @BeforeEach
    void registerRealSpringTransactionalListener() {
        when(client.isEnabled()).thenReturn(true);
        context = new GenericApplicationContext();
        context.registerBean("transactionalFactory", TransactionalEventListenerFactory.class);
        context.registerBean("eventListenerProcessor", EventListenerMethodProcessor.class);
        context.registerBean("memberSyncListener", MemberSyncListener.class,
                () -> new MemberSyncListener(client, queueingExecutor));
        context.refresh();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void listenerRunsOnlyAfterCommit() throws NoSuchMethodException {
        TransactionalEventListener annotation = MemberSyncListener.class
                .getMethod("afterCommit", MemberSyncRequestedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }

    @Test
    void committedTransactionDispatchesTheSyncOnceAndOffTheCallingFlow() {
        MemberSyncRequestedEvent event = event();

        transaction.executeWithoutResult(status -> {
            context.publishEvent(event);
            assertTrue(queued.isEmpty(), "nothing may be dispatched before the commit");
        });

        assertEquals(1, manager.commits);
        assertEquals(1, queued.size(), "the push must be handed to the executor, not run inline");
        verify(client, never()).sync(any(MemberSyncRequestedEvent.class));
        queued.get(0).run();
        verify(client).sync(event);
    }

    @Test
    void rollbackNeverDispatchesTheSync() {
        transaction.executeWithoutResult(status -> {
            context.publishEvent(event());
            status.setRollbackOnly();
        });

        assertEquals(0, manager.commits);
        assertEquals(1, manager.rollbacks);
        assertTrue(queued.isEmpty());
    }

    @Test
    void publishingWithoutATransactionIsSkipped() {
        context.publishEvent(event());

        assertTrue(queued.isEmpty());
    }

    @Test
    void disabledClientDispatchesNothing() {
        when(client.isEnabled()).thenReturn(false);

        transaction.executeWithoutResult(status -> context.publishEvent(event()));

        assertEquals(1, manager.commits);
        assertTrue(queued.isEmpty());
    }

    @Test
    void schedulingFailureCannotUndoTheCommittedOperation() {
        Executor rejecting = runnable -> {
            throw new RejectedExecutionException("queue full");
        };
        MemberSyncListener listener = new MemberSyncListener(client, rejecting);

        assertDoesNotThrow(() -> listener.afterCommit(event()));
    }

    private MemberSyncRequestedEvent event() {
        return new MemberSyncRequestedEvent(UUID.randomUUID(), "Kopi Senja", UUID.randomUUID(), "Budi Santoso",
                "6281234567890", Role.OWNERMERCHANT, "ACTIVE");
    }

    private static final class CommitCountingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
