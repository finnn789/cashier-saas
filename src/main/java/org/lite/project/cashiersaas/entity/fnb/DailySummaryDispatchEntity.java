package org.lite.project.cashiersaas.entity.fnb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

import java.time.Instant;
import java.time.LocalDate;

/** Rows are created by {@code DailySummaryDispatchRepository.insertIfAbsent}; this entity only drives the status. */
@Getter
@Entity
@Table(name = "daily_summary_dispatches")
@NoArgsConstructor
public class DailySummaryDispatchEntity extends MutableBaseEntity {
    public enum Trigger { SHIFT_CLOSED, FALLBACK }

    public enum Status { PENDING, SENT, FAILED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 16)
    private Trigger triggerType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    public void markSent() {
        this.status = Status.SENT;
        recordAttempt();
    }

    public void markFailed() {
        this.status = Status.FAILED;
        recordAttempt();
    }

    private void recordAttempt() {
        this.attempts++;
        this.lastAttemptAt = Instant.now();
    }
}
