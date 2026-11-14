package org.lite.project.cashiersaas.entity.fnb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Immutable petty-cash expense taken from the drawer during a shift. */
@Getter
@Entity
@Table(name = "cash_expenses", uniqueConstraints = @UniqueConstraint(name = "uk_cash_expenses_tenant_idempotency",
        columnNames = {"tenant_id", "idempotency_key"}))
@NoArgsConstructor
public class CashExpenseEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cash_shift_id", nullable = false)
    private CashShiftEntity cashShift;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 200)
    private String description;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by_staff_user_id", nullable = false)
    private StaffUserEntity recordedBy;
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    public static CashExpenseEntity record(TenantEntity tenant, OutletEntity outlet, CashShiftEntity cashShift,
                                           BigDecimal amount, String description, StaffUserEntity recordedBy,
                                           String idempotencyKey, String fingerprint) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2)
            throw new InvalidOperationException("Nominal pengeluaran tidak valid.");
        String trimmed = description == null ? "" : description.trim();
        if (trimmed.isEmpty() || trimmed.length() > 200)
            throw new InvalidOperationException("Keterangan pengeluaran tidak valid.");
        CashExpenseEntity expense = new CashExpenseEntity();
        expense.tenant = tenant;
        expense.outlet = outlet;
        expense.cashShift = cashShift;
        expense.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        expense.description = trimmed;
        expense.recordedBy = recordedBy;
        expense.recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        expense.idempotencyKey = idempotencyKey;
        expense.requestFingerprint = fingerprint;
        return expense;
    }
}
