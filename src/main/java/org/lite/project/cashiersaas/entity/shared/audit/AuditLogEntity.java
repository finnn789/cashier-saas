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
}
