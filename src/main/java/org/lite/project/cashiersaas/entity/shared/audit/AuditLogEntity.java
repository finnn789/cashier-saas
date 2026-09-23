package org.lite.project.cashiersaas.entity.shared.audit;

import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Getter
@Entity
@Table(name = "audit_logs")
@NoArgsConstructor
public class AuditLogEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "outlet_id")
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_staff_user_id")
    private StaffUserEntity actor;
    @Column(nullable = false, length = 80)
    private String action;
    @Column(nullable = false, length = 80)
    private String entityType;
    @Column(nullable = false)
    private UUID entityId;
    @Column(length = 1000)
    private String reason;
    @Column(columnDefinition = "TEXT")
    private String details;

    public enum Action {
        CASHIER_PAYMENT_RECORDED("CASHIER_SALE"),
        CASHIER_PRICE_CHANGED("CASHIER_CATALOG_ITEM"),
        CASHIER_ITEM_ARCHIVED("CASHIER_CATALOG_ITEM");

        private final String entityType;

        Action(String entityType) {
            this.entityType = entityType;
        }
    }

    public static AuditLogEntity recordAction(TenantEntity tenant, OutletEntity outlet, StaffUserEntity actor,
                                              Action action, UUID entityId, String reason, String details) {
        AuditLogEntity log = new AuditLogEntity();
        log.tenant = tenant;
        log.outlet = outlet;
        log.actor = actor;
        log.action = action.name();
        log.entityType = action.entityType;
        log.entityId = entityId;
        log.reason = reason;
        log.details = details;
        return log;
    }
}
