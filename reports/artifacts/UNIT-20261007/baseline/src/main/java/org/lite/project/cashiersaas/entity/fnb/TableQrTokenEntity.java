package org.lite.project.cashiersaas.entity.fnb;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name="table_qr_tokens", uniqueConstraints=@UniqueConstraint(name="uk_table_qr_tokens_token",columnNames="token"))
@NoArgsConstructor
public class TableQrTokenEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id",nullable=false)
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id",nullable=false)
    private OutletEntity outlet;
    @OneToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="dining_table_id",unique=true)
    private DiningTableEntity diningTable;
    @Column(name="qr_type",nullable=false,length=16)
    private String qrType = "TABLE";
    @Column(nullable=false,length=128)
    private String token;
    @Column(nullable=false)
    private boolean active = true;

    public static TableQrTokenEntity create(TenantEntity tenant, OutletEntity outlet, DiningTableEntity table, String token) {
        TableQrTokenEntity qr = new TableQrTokenEntity();
        qr.tenant = tenant;
        qr.outlet = outlet;
        qr.diningTable = table;
        qr.token = token;
        return qr;
    }
    public void rotate(String replacement) { this.token = replacement; }
    public static TableQrTokenEntity outlet(TenantEntity tenant, OutletEntity outlet, String token) {
        TableQrTokenEntity qr = create(tenant, outlet, null, token);
        qr.qrType = "OUTLET";
        return qr;
    }
}
