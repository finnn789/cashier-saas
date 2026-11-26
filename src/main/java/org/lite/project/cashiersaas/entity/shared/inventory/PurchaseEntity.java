package org.lite.project.cashiersaas.entity.shared.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Header of one manual ingredient purchase; the per-item numbers are in {@link PurchaseLineEntity}. */
@Getter
@Entity
@Table(name="purchases",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","idempotency_key"}))
@NoArgsConstructor
public class PurchaseEntity extends BaseEntity {
    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @Column(name="outlet_id",nullable=false)
    private UUID outletId;
    @Column(name="supplier_name",length=120)
    private String supplierName;
    @Column(name="purchased_on",nullable=false)
    private LocalDate purchasedOn;
    @Column(length=500)
    private String note;
    @Column(nullable=false,precision=19,scale=2)
    private BigDecimal total;
    @Column(name="actor_membership_id",nullable=false)
    private UUID actorMembershipId;
    @Column(name="idempotency_key",nullable=false,length=128)
    private String idempotencyKey;
    @Column(name="request_fingerprint",nullable=false,length=64)
    private String requestFingerprint;

    public static PurchaseEntity record(UUID tenantId, UUID outletId, String supplierName, LocalDate purchasedOn, String note,
                                        BigDecimal total, String idempotencyKey, String requestFingerprint, UUID actorMembershipId) {
        PurchaseEntity purchase = new PurchaseEntity();
        purchase.tenantId = tenantId;
        purchase.outletId = outletId;
        purchase.supplierName = supplierName;
        purchase.purchasedOn = purchasedOn;
        purchase.note = note;
        purchase.total = total;
        purchase.actorMembershipId = actorMembershipId;
        purchase.idempotencyKey = idempotencyKey;
        purchase.requestFingerprint = requestFingerprint;
        return purchase;
    }
}
