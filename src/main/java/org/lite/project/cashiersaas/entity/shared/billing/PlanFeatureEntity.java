package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="plan_features",uniqueConstraints=@UniqueConstraint(columnNames={"plan_version_id","feature_id"}))
@NoArgsConstructor
public class PlanFeatureEntity extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="plan_version_id")
    private PlanVersionEntity planVersion;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="feature_id")
    private FeatureEntity feature;
    @Column(name="is_enabled",nullable=false)
    private boolean enabled;
    @Column(name="limit_value")
    private Integer limitValue;
}
