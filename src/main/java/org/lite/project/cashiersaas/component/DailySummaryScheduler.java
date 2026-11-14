package org.lite.project.cashiersaas.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.service.DailySummaryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.daily-summary.enabled", havingValue = "true", matchIfMissing = true)
public class DailySummaryScheduler {
    private final DailySummaryService service;

    @Scheduled(fixedDelayString = "${app.daily-summary.fallback-interval:PT10M}")
    public void run() {
        try {
            service.runFallback();
        } catch (RuntimeException exception) {
            log.warn("Daily summary fallback sweep failed: error={}", exception.getClass().getSimpleName());
        }
    }
}
