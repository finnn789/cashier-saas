package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="business_types")
@NoArgsConstructor
public class BusinessTypeEntity extends BaseEntity {
    @Column(nullable=false,unique=true,length=40)
    private String code;
    @Column(nullable=false,length=80)
    private String name;
    @Column(name="is_active",nullable=false)
    private boolean active=true;
}
