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
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@Entity
@Table(name = "cash_shifts")
@NoArgsConstructor
public class CashShiftEntity extends MutableBaseEntity {
    public enum Status { OPEN, CLOSED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private TenantMembershipEntity membership;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opened_by_staff_user_id", nullable = false)
    private StaffUserEntity openedBy;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;
    @Column(name = "opening_cash", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingCash;
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by_staff_user_id")
    private StaffUserEntity closedBy;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "expected_cash", precision = 19, scale = 2)
    private BigDecimal expectedCash;
    @Column(name = "counted_cash", precision = 19, scale = 2)
    private BigDecimal countedCash;
    @Column(name = "cash_difference", precision = 19, scale = 2)
    private BigDecimal cashDifference;
    @Column(name = "closing_note", length = 500)
    private String closingNote;

    public static CashShiftEntity open(TenantEntity tenant, OutletEntity outlet, TenantMembershipEntity membership,
                                       BigDecimal openingCash) {
        if (openingCash == null || openingCash.signum() < 0 || openingCash.scale() > 2)
            throw new InvalidOperationException("Modal awal tidak valid.");
        CashShiftEntity shift = new CashShiftEntity();
        shift.tenant = tenant;
        shift.outlet = outlet;
        shift.membership = membership;
        shift.openedBy = membership.getUser();
        shift.openingCash = openingCash.setScale(2, RoundingMode.UNNECESSARY);
        shift.openedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return shift;
    }

    public void close(StaffUserEntity closedBy, BigDecimal expectedCash, BigDecimal countedCash, String note) {
        if (!isOpen()) throw new OperationConflictException("Shift sudah ditutup.");
        if (countedCash == null || countedCash.signum() < 0 || countedCash.scale() > 2)
            throw new InvalidOperationException("Uang tunai hitung tidak valid.");
        this.closedBy = closedBy;
        this.expectedCash = expectedCash.setScale(2, RoundingMode.HALF_UP);
        this.countedCash = countedCash.setScale(2, RoundingMode.UNNECESSARY);
        this.cashDifference = this.countedCash.subtract(this.expectedCash);
        this.closingNote = note == null || note.isBlank() ? null : note.trim();
        this.closedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.status = Status.CLOSED;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }
}
