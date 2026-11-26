package org.lite.project.cashiersaas.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.service.MediaStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.media.orphan-sweep.enabled", havingValue = "true", matchIfMissing = true)
public class MediaOrphanSweepScheduler {
    private final MediaStorageService service;

    @Scheduled(cron = "${app.media.orphan-sweep-cron:0 30 3 * * *}")
    public void run() {
        try {
            int deleted = service.sweepOrphans(Instant.now());
            log.info("Orphan image sweep finished: deleted={}", deleted);
        } catch (RuntimeException exception) {
            log.warn("Orphan image sweep failed: error={}", exception.getClass().getSimpleName());
        }
    }
}
