package org.lite.project.cashiersaas.component;

import lombok.Getter;

import java.util.UUID;

/** Published inside the shift-close transaction; the dispatch runs only after that transaction commits. */
@Getter
public final class DailySummaryRequestedEvent {
    private final UUID dispatchId;

    public DailySummaryRequestedEvent(UUID dispatchId) {
        this.dispatchId = dispatchId;
    }
}
