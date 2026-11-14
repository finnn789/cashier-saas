package org.lite.project.cashiersaas.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.component.DailySummaryRequestedEvent;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.fnb.DailySummaryDispatchEntity;
import org.lite.project.cashiersaas.repository.DailySummaryDispatchRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Once-per-outlet-per-business-day owner summary (TASK-080 D1): last shift close, with a 23:00 fallback. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySummaryService {
    static final LocalTime FALLBACK_TIME = LocalTime.of(23, 0);
    static final int MAX_ATTEMPTS = 3;
    static final int RETRY_LIMIT = 50;
    static final long RETRY_AFTER_MINUTES = 10;

    private final DailySummaryDispatchRepository dispatches;
    private final DailySummaryClient client;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    /** Runs inside the shift-close transaction, after the close was flushed. */
    public void onShiftClosed(MerchantAccessContext context, CashShiftEntity shift) {
        UUID tenantId = context.getTenant().getId();
        UUID outletId = context.getOutlet().getId();
        if (dispatches.countOpenShifts(tenantId, outletId) > 0) {
            return;
        }
        LocalDate businessDate = shift.getOpenedAt().atZone(zone(context.getOutlet().getTimeZone())).toLocalDate();
        dispatches.insertIfAbsent(tenantId, outletId, businessDate, DailySummaryDispatchEntity.Trigger.SHIFT_CLOSED)
                .ifPresent(id -> events.publishEvent(new DailySummaryRequestedEvent(id)));
    }

    public void runFallback() {
        runFallback(Instant.now());
    }

    void runFallback(Instant now) {
        if (!client.isEnabled()) {
            return;
        }
        // Fetch retryable rows before inserting new ones: rows created in this sweep must not be retried in the same sweep.
        Instant olderThan = now.minus(RETRY_AFTER_MINUTES, ChronoUnit.MINUTES);
        LocalDate minBusinessDate = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(2);
        List<UUID> retryable = transactions.execute(
                status -> dispatches.retryable(MAX_ATTEMPTS, olderThan, minBusinessDate, RETRY_LIMIT));
        // shortcut: loads every active F&B outlet per sweep and filters by local time in memory, upgrade when outlets reach thousands.
        for (DailySummaryDispatchRepository.OutletRef outlet : dispatches.activeFnbOutlets()) {
            ZonedDateTime local = now.atZone(zone(outlet.getTimeZone()));
            if (local.toLocalTime().isBefore(FALLBACK_TIME)) {
                continue;
            }
            transactions.execute(status -> dispatches.insertIfAbsent(outlet.getTenantId(), outlet.getOutletId(),
                    local.toLocalDate(), DailySummaryDispatchEntity.Trigger.FALLBACK)).ifPresent(this::dispatch);
        }
        if (retryable != null) {
            retryable.forEach(this::dispatch);
        }
    }

    /** Sends one summary request and records the outcome. Never throws. */
    public void dispatch(UUID dispatchId) {
        try {
            if (!client.isEnabled()) {
                return;
            }
            Target target = transactions.execute(status -> dispatches.byId(dispatchId)
                    .filter(row -> row.getStatus() != DailySummaryDispatchEntity.Status.SENT)
                    .map(row -> new Target(row.getTenant().getId(), row.getOutlet().getId(), row.getBusinessDate()))
                    .orElse(null));
            if (target == null) {
                return;
            }
            boolean sent = client.send(target.getTenantId(), target.getOutletId(), target.getBusinessDate());
            transactions.executeWithoutResult(status -> dispatches.byId(dispatchId).ifPresent(row -> {
                if (sent) row.markSent(); else row.markFailed();
            }));
        } catch (RuntimeException exception) {
            log.warn("Daily summary dispatch failed: dispatchId={} error={}", dispatchId, exception.getClass().getSimpleName());
        }
    }

    private static ZoneId zone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            return ZoneOffset.UTC; // an invalid stored zone must not break shift close
        }
    }

    @Getter
    @RequiredArgsConstructor
    private static class Target {
        private final UUID tenantId;
        private final UUID outletId;
        private final LocalDate businessDate;
    }
}
