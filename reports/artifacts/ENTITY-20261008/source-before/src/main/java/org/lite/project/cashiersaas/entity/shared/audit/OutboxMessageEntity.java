package org.lite.project.cashiersaas.entity.shared.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="outbox_messages",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","deduplication_key"}))
@NoArgsConstructor
public class OutboxMessageEntity extends BaseEntity {
    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @Column(name="aggregate_type",nullable=false,length=80)
    private String aggregateType;
    @Column(name="aggregate_id",nullable=false)
    private UUID aggregateId;
    @Column(name="deduplication_key",nullable=false,length=200)
    private String deduplicationKey;
    @Column(name="event_type",nullable=false,length=100)
    private String eventType;
    @Column(nullable=false,columnDefinition="JSONB")
    private String payload;
    @Column(name="occurred_at",nullable=false)
    private Instant occurredAt;
    private Instant publishedAt;
    @Column(nullable=false)
    private int attempts;
}
