package org.lite.project.cashiersaas.entity.shared.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

import java.time.Instant;
import java.util.UUID;

/** Header of one stock opname; the per-item numbers are in {@link StockCountLineEntity}. */
@Getter
@Entity
@Table(name="stock_counts",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","idempotency_key"}))
@NoArgsConstructor
public class StockCountEntity extends BaseEntity {
    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @Column(name="outlet_id",nullable=false)
    private UUID outletId;
    @Column(name="counted_at",nullable=false)
    private Instant countedAt;
    @Column(name="actor_membership_id",nullable=false)
    private UUID actorMembershipId;
    @Column(length=500)
    private String note;
    @Column(name="idempotency_key",nullable=false,length=128)
    private String idempotencyKey;
    @Column(name="request_fingerprint",nullable=false,length=64)
    private String requestFingerprint;

    public static StockCountEntity record(UUID tenantId, UUID outletId, String note, String idempotencyKey,
                                          String requestFingerprint, UUID actorMembershipId) {
        StockCountEntity count = new StockCountEntity();
        count.tenantId = tenantId;
        count.outletId = outletId;
        count.countedAt = Instant.now();
        count.actorMembershipId = actorMembershipId;
        count.note = note;
        count.idempotencyKey = idempotencyKey;
        count.requestFingerprint = requestFingerprint;
        return count;
    }
}
