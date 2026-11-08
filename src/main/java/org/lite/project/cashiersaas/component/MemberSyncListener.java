package org.lite.project.cashiersaas.component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.service.MemberSyncClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pushes staff members to Ai-services after the owning transaction commits. The push runs on a small bounded
 * pool so retries and timeouts never delay the HTTP response of the business operation; the client itself never
 * throws and this listener swallows any dispatch failure because the committed operation must stay successful.
 */
@Slf4j
@Component
public class MemberSyncListener {
    private static final int POOL_SIZE = 2;
    private static final int QUEUE_CAPACITY = 200;

    private final MemberSyncClient client;
    private final Executor executor;
    private final ExecutorService ownedExecutor;

    @Autowired
    public MemberSyncListener(MemberSyncClient client) {
        this.client = client;
        AtomicInteger counter = new AtomicInteger();
        this.ownedExecutor = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "member-sync-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.executor = ownedExecutor;
    }

    /** Test seam: lets a test run the push on the calling thread. */
    public MemberSyncListener(MemberSyncClient client, Executor executor) {
        this.client = client;
        this.executor = executor;
        this.ownedExecutor = null;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void afterCommit(MemberSyncRequestedEvent event) {
        if (!client.isEnabled()) {
            return;
        }
        try {
            executor.execute(() -> client.sync(event));
        } catch (RuntimeException exception) {
            log.warn("Member sync could not be scheduled: tenantId={} userId={} error={}", event.getTenantId(),
                    event.getUserId(), exception.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }
}
