package org.lite.project.cashiersaas.entity.fnb;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.FnbGatewayOutcome;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Entity
@Table(name = "fnb_gateway_attempts", uniqueConstraints = @UniqueConstraint(name = "uk_fnb_gateway_attempt_key", columnNames = {"tenant_id", "idempotency_key"}))
@NoArgsConstructor
public class FnbGatewayAttemptEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id", nullable = false) private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "outlet_id", nullable = false) private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "sales_order_id", nullable = false) private SalesOrderEntity salesOrder;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "initiated_by_staff_user_id", nullable = false) private StaffUserEntity initiatedBy;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private FnbGatewayOutcome outcome;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(name = "idempotency_key", nullable = false, length = 128) private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64) private String requestFingerprint;
    @Column(length = 128) private String providerReference;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "resolved_by_staff_user_id") private StaffUserEntity resolvedBy;

    public static FnbGatewayAttemptEntity pending(TenantEntity tenant, OutletEntity outlet, SalesOrderEntity order,
            StaffUserEntity actor, BigDecimal amount, String key, String fingerprint, String reference) {
        FnbGatewayAttemptEntity attempt = new FnbGatewayAttemptEntity();
        attempt.tenant = tenant; attempt.outlet = outlet; attempt.salesOrder = order; attempt.initiatedBy = actor;
        attempt.amount = amount; attempt.idempotencyKey = key; attempt.requestFingerprint = fingerprint;
        attempt.providerReference = reference; attempt.outcome = FnbGatewayOutcome.PENDING;
        return attempt;
    }
    public void resolve(FnbGatewayOutcome value, StaffUserEntity actor) {
        if (value == null || value == FnbGatewayOutcome.PENDING) {
            throw new InvalidOperationException("Outcome resolusi harus terminal.");
        }
        if (outcome != FnbGatewayOutcome.PENDING) {
            throw new OperationConflictException("Gateway attempt sudah memiliki outcome terminal.");
        }
        outcome = value; resolvedBy = actor; resolvedAt = Instant.now();
    }
}
