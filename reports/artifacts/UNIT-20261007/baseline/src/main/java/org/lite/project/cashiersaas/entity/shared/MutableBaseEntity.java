package org.lite.project.cashiersaas.entity.shared;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import lombok.Getter;
import java.time.Instant;

@Getter
@MappedSuperclass
public class MutableBaseEntity extends BaseEntity {
    @Version
    private long version;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @PrePersist
    protected void initializeUpdateTimestamp() {
        updatedAt = Instant.now();
    }
    @PreUpdate
    protected void updateTimestamp() {
        updatedAt = Instant.now();
    }
}
