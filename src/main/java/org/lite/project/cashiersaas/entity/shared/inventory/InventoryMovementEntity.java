package org.lite.project.cashiersaas.entity.shared.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="inventory_movements",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","idempotency_key"}))
@NoArgsConstructor
public class InventoryMovementEntity extends BaseEntity {
    public enum Type { RESTOCK, WASTE, ADJUSTMENT, SALE, SALE_REVERSAL }

    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @Column(name="outlet_id",nullable=false)
    private UUID outletId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="inventory_item_id")
    private InventoryItemEntity inventoryItem;
    @Column(name="order_id")
    private UUID orderId;
    @Column(name="reversal_of_movement_id")
    private UUID reversalOfMovementId;
    @Enumerated(EnumType.STRING)
    @Column(name="movement_type",nullable=false,length=16)
    private Type type;
    @Column(name="quantity_delta",nullable=false,precision=14,scale=3)
    private BigDecimal quantityDelta;
    @Column(name="unit_cost",precision=19,scale=4)
    private BigDecimal unitCost;
    @Column(nullable=false,columnDefinition="TEXT")
    private String reason;
    @Column(name="idempotency_key",nullable=false,length=128)
    private String idempotencyKey;
    @Column(name="request_fingerprint",nullable=false,length=64)
    private String requestFingerprint;
    @Column(name="actor_membership_id",nullable=false)
    private UUID actorMembershipId;
    @Column(name="occurred_at",nullable=false)
    private Instant occurredAt;

    public static InventoryMovementEntity record(UUID tenantId, UUID outletId, InventoryItemEntity item, UUID orderId,
                                                 Type type, BigDecimal quantityDelta, String reason, String idempotencyKey,
                                                 String requestFingerprint, UUID actorMembershipId) {
        return record(tenantId, outletId, item, orderId, type, quantityDelta, reason, idempotencyKey,
                requestFingerprint, actorMembershipId, null, null);
    }

    public static InventoryMovementEntity record(UUID tenantId, UUID outletId, InventoryItemEntity item, UUID orderId,
                                                 Type type, BigDecimal quantityDelta, String reason, String idempotencyKey,
                                                 String requestFingerprint, UUID actorMembershipId, UUID reversalOfMovementId,
                                                 BigDecimal unitCost) {
        InventoryMovementEntity movement = new InventoryMovementEntity();
        movement.tenantId = tenantId;
        movement.outletId = outletId;
        movement.inventoryItem = item;
        movement.orderId = orderId;
        movement.reversalOfMovementId = reversalOfMovementId;
        movement.unitCost = unitCost;
        movement.type = type;
        movement.quantityDelta = quantityDelta;
        movement.reason = reason;
        movement.idempotencyKey = idempotencyKey;
        movement.requestFingerprint = requestFingerprint;
        movement.actorMembershipId = actorMembershipId;
        movement.occurredAt = Instant.now();
        return movement;
    }
}
