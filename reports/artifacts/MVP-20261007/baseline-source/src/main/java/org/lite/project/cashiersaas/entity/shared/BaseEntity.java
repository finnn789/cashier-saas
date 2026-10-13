package org.lite.project.cashiersaas.entity.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import java.time.Instant;
import java.util.UUID;

@Getter
@MappedSuperclass
public class BaseEntity {
    @Id
    private UUID id;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @PrePersist
    protected void initializeCreationMetadata() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
