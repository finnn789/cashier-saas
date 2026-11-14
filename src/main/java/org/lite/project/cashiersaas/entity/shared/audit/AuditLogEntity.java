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
        CASHIER_ITEM_ARCHIVED("CASHIER_CATALOG_ITEM"),
        FNB_PRODUCT_PRICE_CHANGED("PRODUCT"),
        FNB_OPTION_PRICE_CHANGED("PRODUCT_OPTION"),
        FNB_RECIPE_CHANGED("RECIPE"),
        FNB_PAYMENT_RECORDED("SALES_ORDER"),
        FNB_DISCOUNT_GRANTED("SALES_ORDER"),
        FNB_ORDER_CREATED("SALES_ORDER"),
        FNB_ORDER_CANCELLED("SALES_ORDER"),
        FNB_ORDER_STATUS_CHANGED("SALES_ORDER"),
        FNB_ORDER_ITEMS_REPLACED("SALES_ORDER"),
        FNB_GATEWAY_ATTEMPT_CREATED("FNB_GATEWAY_ATTEMPT"),
        FNB_GATEWAY_ATTEMPT_RESOLVED("FNB_GATEWAY_ATTEMPT"),
        BUSINESS_SETTINGS_CHANGED("BUSINESS_SETTINGS"),
        STAFF_DEACTIVATED("TENANT_MEMBERSHIP"),
        STAFF_REACTIVATED("TENANT_MEMBERSHIP"),
        STAFF_PASSWORD_RESET("STAFF_USER"),
        SHIFT_OPENED("CASH_SHIFT"),
        SHIFT_CLOSED("CASH_SHIFT"),
        FNB_ORDER_REFUNDED("SALES_ORDER"),
        PAYMENT_LABEL_CHANGED("PAYMENT_LABEL"),
        CASH_EXPENSE_RECORDED("CASH_EXPENSE");

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
