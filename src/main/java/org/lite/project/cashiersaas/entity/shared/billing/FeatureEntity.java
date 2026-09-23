package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="features")
@NoArgsConstructor
public class FeatureEntity extends BaseEntity {
    @Column(nullable=false,unique=true,length=80)
    private String code;
    @Column(nullable=false,length=120)
    private String name;
    @Column(columnDefinition="TEXT")
    private String description;
}
