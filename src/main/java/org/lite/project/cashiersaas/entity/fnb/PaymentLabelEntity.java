package org.lite.project.cashiersaas.entity.fnb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;

@Getter
@Entity
@Table(name = "payment_labels")
@NoArgsConstructor
public class PaymentLabelEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @Column(nullable = false, length = 40)
    private String name;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public static PaymentLabelEntity create(TenantEntity tenant, OutletEntity outlet, String name, int displayOrder) {
        PaymentLabelEntity label = new PaymentLabelEntity();
        label.tenant = tenant;
        label.outlet = outlet;
        label.name = validName(name);
        label.displayOrder = displayOrder;
        return label;
    }

    public void update(String name, int displayOrder, boolean active) {
        this.name = validName(name);
        this.displayOrder = displayOrder;
        this.active = active;
    }

    private static String validName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 40) throw new InvalidOperationException("Nama label tidak valid.");
        return trimmed;
    }
}
