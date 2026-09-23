package org.lite.project.cashiersaas.entity.laundry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.sales.OrderEntity;

@Getter
@Entity
@Table(name="laundry_orders")
@NoArgsConstructor
public class LaundryOrderEntity extends MutableBaseEntity {
    @MapsId
    @OneToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="id")
    private OrderEntity order;
    @Column(name="estimated_ready_at")
    private Instant estimatedReadyAt;
    @Column(name="intake_notes",columnDefinition="TEXT")
    private String intakeNotes;
}
