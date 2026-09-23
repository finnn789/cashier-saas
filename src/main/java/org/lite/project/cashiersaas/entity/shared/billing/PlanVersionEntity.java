package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="plan_versions",uniqueConstraints=@UniqueConstraint(columnNames={"plan_id","version_no"}))
@NoArgsConstructor
public class PlanVersionEntity extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="plan_id")
    private PlanEntity plan;
    @Column(name="version_no",nullable=false)
    private int versionNo;
    @Column(nullable=false,length=16)
    private String status;
    @Column(name="effective_from")
    private Instant effectiveFrom;
    @Column(name="effective_until")
    private Instant effectiveUntil;
}
