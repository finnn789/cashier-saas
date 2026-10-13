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
    @Column(name="movement_type",nullable=false,length=16)
    private String movementType;
    @Column(name="quantity_delta",nullable=false,precision=14,scale=3)
    private BigDecimal quantityDelta;
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
}
