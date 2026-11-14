package org.lite.project.cashiersaas.component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.service.DailySummaryClient;
import org.lite.project.cashiersaas.service.DailySummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Dispatches the daily summary after the shift-close commit on a small pool so the HTTP response never waits. */
@Slf4j
@Component
public class DailySummaryListener {
    private final DailySummaryService service;
    private final DailySummaryClient client;
    private final Executor executor;
    private final ExecutorService ownedExecutor;

    @Autowired
    public DailySummaryListener(DailySummaryService service, DailySummaryClient client) {
        this.service = service;
        this.client = client;
        this.ownedExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(50), runnable -> {
            Thread thread = new Thread(runnable, "daily-summary");
            thread.setDaemon(true);
            return thread;
        });
        this.executor = ownedExecutor;
    }

    /** Test seam: lets a test run the dispatch on the calling thread. */
    public DailySummaryListener(DailySummaryService service, DailySummaryClient client, Executor executor) {
        this.service = service;
        this.client = client;
        this.executor = executor;
        this.ownedExecutor = null;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void afterCommit(DailySummaryRequestedEvent event) {
        if (!client.isEnabled()) {
            return;
        }
        try {
            executor.execute(() -> service.dispatch(event.getDispatchId()));
        } catch (RuntimeException exception) {
            log.warn("Daily summary could not be scheduled: dispatchId={} error={}", event.getDispatchId(),
                    exception.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }
}
